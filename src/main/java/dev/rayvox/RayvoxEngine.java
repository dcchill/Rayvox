package dev.rayvox;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;

public final class RayvoxEngine {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static TerrainShader terrain;
    private static ClientLevel level;
    private static LightColors colors = new LightColors(Map.of());
    private static MaterialTable materials;
    private static LightSourceManager lights = new LightSourceManager();
    private static GpuLightCache gpuLights;
    private static DistantLightCache distant = new DistantLightCache();
    private static String status = "Waiting for shader registration";
    private static String gpu = "Not detected";
    private static boolean compute;
    private static VoxelWorldCache worldCache;
    private static GpuVoxelCache gpuCache;
    private static int debugMode;
    private static LightingHistory history;
    private static GpuTimer timer;
    private static boolean frozen, profiling;
    private static List<LightSourceManager.Light> dynamic = List.of();
    private static HistoryStamp historyStamp;
    private static long sourceRevision, historyRevision;
    private static long updateNanos;
    private static DirectionalLight sun = new DirectionalLight(0, 1, 0, 0, 0);
    private record HistoryStamp(long grid, long sources, long distant, boolean shadows, int distance, int view, boolean materials, boolean indirect) {}

    private RayvoxEngine() {}

    public static void registerShaders(RegisterShadersEvent event) {
        RenderSystem.assertOnRenderThread();
        closeGpuCache();
        worldCache = null;
        lights = new LightSourceManager();
        distant = new DistantLightCache();
        frozen = false;
        dynamic = List.of();
        terrain = null;
        var caps = GL.getCapabilities();
        gpu = GL11.glGetString(GL11.GL_RENDERER) + " / OpenGL " + GL11.glGetString(GL11.GL_VERSION);
        compute = caps.OpenGL43;
        LOGGER.info("Rayvox GPU: {}; OpenGL 4.3 compute available: {} (unused)", gpu, compute);
        if (!caps.OpenGL32) {
            status = "Vanilla fallback: OpenGL 3.2 required";
            LOGGER.warn(status);
            return;
        }
        for (String mod : new String[]{"iris", "oculus", "embeddium", "sodium", "rubidium"}) {
            if (ModList.get().isLoaded(mod)) {
                status = "Vanilla fallback: unsupported renderer " + mod;
                LOGGER.warn(status);
                return;
            }
        }
        ShaderInstance candidate = null;
        try {
            try (var reader = event.getResourceProvider().openAsReader(ResourceLocation.fromNamespaceAndPath("rayvox", "lights.json"))) {
                colors = new LightColors(LightDefinition.read(reader));
            }
            try (var reader = event.getResourceProvider().openAsReader(ResourceLocation.fromNamespaceAndPath("rayvox", "materials.json"))) {
                materials = MaterialTable.read(reader);
            }
            candidate = new TerrainShader(event.getResourceProvider());
            if (GL20.glGetProgrami(candidate.getId(), GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(candidate.getId()));
            }
            for (String uniform : new String[]{"ModelViewMat", "ProjMat", "ChunkOffset", "ColorModulator",
                    "FogStart", "FogEnd", "FogColor", "FogShape", "ShadowsEnabled", "ShadowDistance", "AlphaCutoff",
                    "DebugMode", "CacheWidth", "CacheMinSlot", "CameraInCache", "CameraFraction"}) {
                if (candidate.getUniform(uniform) == null) throw new IllegalStateException("Missing uniform " + uniform);
            }
            // GameRenderer owns registered shaders and closes them on reload and shutdown.
            event.registerShader(candidate, loaded -> {
                terrain = (TerrainShader)loaded;
                status = "Terrain shader ready";
                LOGGER.info("Rayvox terrain shader compiled and linked");
            });
        } catch (Exception failure) {
            if (candidate != null) candidate.close();
            status = "Vanilla fallback: shader failed; see latest.log";
            LOGGER.error(status, failure);
        }
    }

    public static void updateLevel(ClientLevel current) {
        if (current != level) {
            LightDebugView.rayStatus = "Select a light, then aim at a receiving surface";
            level = current;
            lights = new LightSourceManager();
            distant = new DistantLightCache();
            worldCache = null;
            frozen = false;
            dynamic = List.of();
            closeGpuCache();
        }
    }

    public static void tickCache() {
        if (level == null || terrain == null || !Rayvox.ENABLED.get()) {
            if (worldCache != null) distant = new DistantLightCache();
            worldCache = null;
            closeGpuCache();
            return;
        }
        if (worldCache == null || worldCache.grid.radius != Rayvox.CACHE_RADIUS.get()) {
            frozen = false;
            worldCache = new VoxelWorldCache(Rayvox.CACHE_RADIUS.get(), colors, materials);
            lights.invalidate();
            closeGpuCache();
        }
        if (frozen) return;
        var position = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        try {
            worldCache.tick(level, (int)Math.floor(position.x), (int)Math.floor(position.y),
                    (int)Math.floor(position.z), Rayvox.EXTRACT_BUDGET.get());
            distant.tick(level, (int)Math.floor(position.x), (int)Math.floor(position.z),
                    Minecraft.getInstance().options.renderDistance().get(), colors);
        } catch (RuntimeException failure) {
            closeGpuCache();
            worldCache = null;
            distant = new DistantLightCache();
            terrain = null;
            status = "Vanilla fallback: voxel extraction failed; see latest.log";
            LOGGER.error(status, failure);
        }
    }

    public static void renderStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && worldCache != null && Rayvox.ENABLED.get()) {
            LightDebugView.render(event, lights, worldCache.grid, debugMode);
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || worldCache == null) return;
        try {
            long start = System.nanoTime();
            if (gpuCache == null) gpuCache = new GpuVoxelCache(worldCache.grid.width);
            if (!frozen) gpuCache.upload(worldCache.grid, Rayvox.UPLOAD_BUDGET.get());
            if (gpuLights == null) { gpuLights = new GpuLightCache(worldCache.grid.slots.length); lights.invalidate(); }
            var camera = event.getCamera().getPosition();
            if (!frozen && lights.rebuild(worldCache.grid, (int)Math.floor(camera.x), (int)Math.floor(camera.y),
                    (int)Math.floor(camera.z), Rayvox.ACTIVE_LIGHTS.get(), Rayvox.LIGHTS_PER_SECTION.get(),
                    level.getMinSection(), level.getMaxSection())) { gpuLights.upload(lights); sourceRevision++; }
            if (!frozen) dynamic = Rayvox.DYNAMIC.get() ? DynamicLights.collect(level, camera,
                    event.getPartialTick().getGameTimeDeltaPartialTick(false), colors) : List.of();
            if (!frozen) sun = DirectionalLight.at(level.getSunAngle(event.getPartialTick().getGameTimeDeltaPartialTick(false)),
                    level.getMoonBrightness(), level.getRainLevel(1), Rayvox.SUNLIGHT.get() && Rayvox.SHADOWS.get() && level.dimensionType().hasSkyLight());
            // shortcut: any scene edit invalidates all history; use regional keys if profiling justifies their complexity.
            var stamp = new HistoryStamp(worldCache.grid.revision, sourceRevision, distant.revision,
                    Rayvox.SHADOWS.get(), Rayvox.SHADOW_DISTANCE.get(), debugMode, Rayvox.MATERIALS.get(), Rayvox.INDIRECT.get());
            if (!stamp.equals(historyStamp)) { historyStamp = stamp; historyRevision++; }
            if (Rayvox.HISTORY.get() && (debugMode == 0 || debugMode == 5 || debugMode == 6 || debugMode == 7)) {
                if (history == null) history = new LightingHistory();
                var target = Minecraft.getInstance().getMainRenderTarget();
                if (org.lwjgl.opengl.GL30.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING) == target.frameBufferId)
                    history.begin(target.width, target.height, historyRevision, sun.epoch(), Rayvox.INDIRECT.get());
                else history.invalidate();
            } else if (history != null) { history.close(); history = null; }
            if (profiling && timer == null && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) timer = new GpuTimer();
            if (!profiling && timer != null) { timer.close(); timer = null; }
            updateNanos = System.nanoTime() - start;
        } catch (RuntimeException failure) {
            closeGpuCache();
            terrain = null;
            status = "Vanilla fallback: voxel upload failed; see latest.log";
            LOGGER.error(status, failure);
        }
    }

    public static void blockChanged(ClientLevel changedLevel, int x, int y, int z) {
        if (frozen) return;
        if (changedLevel == level) distant.changed(Math.floorDiv(x, 16), Math.floorDiv(z, 16), level.getMinSection(), false);
        if (changedLevel == level && worldCache != null) worldCache.grid.blockChanged(x, y, z);
    }

    public static void columnChanged(ClientLevel changedLevel, int x, int z, boolean unload) {
        if (frozen) return;
        if (changedLevel == level) distant.changed(x, z, level.getMinSection(), unload);
        if (changedLevel == level && worldCache != null) worldCache.columnChanged(x, z, unload);
    }

    private static void closeGpuCache() {
        if (history != null) { history.close(); history = null; }
        if (timer != null) { timer.close(); timer = null; }
        historyStamp = null;
        if (gpuLights != null) { gpuLights.close(); gpuLights = null; }
        if (gpuCache != null) {
            gpuCache.close();
            gpuCache = null;
        }
    }

    public static void close() {
        closeGpuCache();
        worldCache = null;
        level = null;
        lights = new LightSourceManager();
        distant = new DistantLightCache();
        frozen = false;
        dynamic = List.of();
    }

    public static void sectionLights(int x, int y, int z, double cameraX, double cameraY, double cameraZ) {
        if (terrain == null || !terrain.drawing) return;
        int sx = Math.floorDiv(x, 16), sy = Math.floorDiv(y, 16), sz = Math.floorDiv(z, 16);
        terrain.section(distant.section(sx, sy, sz), lights, distant.ready(sx, sz), cameraX, cameraY, cameraZ);
    }

    public static void toggleTestLights() {
        if (!lights.testLights.isEmpty()) { lights.testLights = List.of(); lights.invalidate(); return; }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var forward = camera.getLookVector();
        Vec3 center = camera.getPosition().add(forward.x() * 3.0, forward.y() * 3.0, forward.z() * 3.0);
        Vec3 right = new Vec3(forward.z(), 0, -forward.x()).normalize();
        if (right.lengthSqr() < 0.1) right = new Vec3(1, 0, 0);
        Vec3 red = center.add(right.scale(1.5)), blue = center.subtract(right.scale(1.5));
        lights.testLights = List.of(new LightSourceManager.Light("test:red", red.x, red.y, red.z,
                        new LightDefinition(1, 0, 0, 2, 8, true)),
                new LightSourceManager.Light("test:blue", blue.x, blue.y, blue.z, new LightDefinition(0, 0, 1, 2, 8, true)));
        lights.invalidate();
    }

    public static ShaderInstance terrainShader(ShaderInstance vanilla, RenderType layer,
            double cameraX, double cameraY, double cameraZ) {
        RenderSystem.assertOnRenderThread();
        updateLevel(Minecraft.getInstance().level);
        if (terrain == null || level == null || !Rayvox.ENABLED.get() || gpuCache == null || gpuLights == null || worldCache == null) return vanilla;
        if (layer != RenderType.solid() && layer != RenderType.cutout() && layer != RenderType.cutoutMipped()) return vanilla;
        terrain.getUniform("ShadowsEnabled").set(Rayvox.SHADOWS.get() ? 1 : 0);
        terrain.getUniform("ShadowDistance").set((float)Rayvox.SHADOW_DISTANCE.get());
        terrain.getUniform("AlphaCutoff").set(layer == RenderType.cutout() ? 0.1f : layer == RenderType.cutoutMipped() ? 0.5f : 0.0f);
        terrain.getUniform("DebugMode").set(debugMode);
        var grid = worldCache.grid;
        int minX = grid.centerX - grid.radius, minY = grid.centerY - grid.radius, minZ = grid.centerZ - grid.radius;
        int bx = (int)Math.floor(cameraX), by = (int)Math.floor(cameraY), bz = (int)Math.floor(cameraZ);
        terrain.cache = gpuCache;
        terrain.lights = gpuLights;
        terrain.history = history;
        terrain.timer = timer;
        terrain.dynamic = dynamic;
        terrain.sun = sun;
        terrain.materials = materials;
        terrain.cameraX = cameraX; terrain.cameraY = cameraY; terrain.cameraZ = cameraZ;
        terrain.getUniform("CacheWidth").set(grid.width);
        terrain.getUniform("CacheMinSlot").set(Math.floorMod(minX, grid.width), Math.floorMod(minY, grid.width), Math.floorMod(minZ, grid.width));
        terrain.getUniform("CameraInCache").set(bx - minX * 16, by - minY * 16, bz - minZ * 16);
        terrain.getUniform("CameraFraction").set((float)(cameraX - bx), (float)(cameraY - by), (float)(cameraZ - bz));
        return terrain;
    }

    public static void cycleDebugMode() { debugMode = (debugMode + 1) % 8; }
    public static String debugModeName() { return new String[]{"Final lighting", "Voxel occupancy", "Dirty regions", "Light volumes", "Shadow ray", "Direct light", "History reuse", "Indirect light (prototype)"}[debugMode]; }
    public static boolean frozen() { return frozen; }
    public static void toggleFreeze() {
        frozen = !frozen;
        if (!frozen && worldCache != null) {
            for (var slot : worldCache.grid.slots) worldCache.grid.markDirty(slot.x, slot.y, slot.z);
            distant = new DistantLightCache();
            lights.invalidate();
        }
        if (history != null) history.invalidate();
    }
    public static boolean profiling() { return profiling; }
    public static void toggleProfiling() { profiling = !profiling; }
    public static String historyStats() { return history == null ? "Lighting history OFF" : history.status; }
    public static String timingStats() { return (timer == null ? "GPU profiler OFF / unsupported" : timer.status())
            + String.format(java.util.Locale.ROOT, "; CPU frame update %.3f ms; last list build %.3f ms", updateNanos / 1_000_000.0, lights.buildNanos / 1_000_000.0); }
    public static String dynamicStats() { return "Moving held lights: " + dynamic.size() + "/4; lighting " + (frozen ? "FROZEN (world simulation continues)" : "live"); }
    public static LightSourceManager lights() { return lights; }
    public static void selectLight() {
        var previous = lights.selected;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var position = camera.getPosition(); var look = camera.getLookVector();
        lights.select(position.x, position.y, position.z, look.x(), look.y(), look.z());
        if (lights.selected == null) lights.selected = previous;
    }
    public static String lightStats() {
        return "Lights: " + lights.active.size() + "/" + lights.discovered + "; complete sections: " + lights.completeSections
                + "; truncated: " + lights.truncatedSections;
    }
    public static String distantStats() { return distant.status(); }
    public static String textureColorStats() { return colors.status(); }
    public static String cacheStats() {
        if (worldCache == null) return "Voxel cache inactive";
        var grid = worldCache.grid;
        return "Cache: " + grid.resident() + "/" + grid.slots.length + " uploaded; " + grid.pending() + " pending";
    }
    public static String uploadStats() {
        return gpuCache == null ? "GPU cache not allocated" : "GPU cache: " + gpuCache.bytes() / 1024
                + " KiB; uploaded " + gpuCache.uploadedLastFrame + " sections/frame";
    }

    public static String status() { return status; }
    public static String gpu() { return gpu; }
    public static boolean computeAvailable() { return compute; }
}
