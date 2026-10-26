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
    // Variant bits: advanced materials = 1, directional shadows = 2, indirect lighting = 4.
    private static final TerrainShader[] terrainVariants = new TerrainShader[8];
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
    private static WorldLightingState worldLighting;
    private static GpuTimer timer;
    private static boolean frozen, profiling;
    private static List<LightSourceManager.Light> dynamic = List.of();
    private static List<net.minecraft.world.phys.AABB> entityCasters = List.of();
    private static HistoryStamp historyStamp;
    private static long sourceRevision, historyRevision;
    private static long updateNanos;
    private static DirectionalLight sun = DirectionalLight.OFF;
    private static LightingRenderPipeline lightingPipeline;
    private static SunlightVolume sunlightVolume;
    private static boolean volumeFailed;
    private static boolean passFailed, deferredActive;
    // Span: AFTER_SKY (before uploads) to the end of opaque terrain and the deferred pass. Includes vanilla terrain draws.
    private static GpuTimer spanTimer, passTimer, indirectTimer;
    private static int spanSamples;
    private static long lastFrameNanos;
    private static final java.lang.management.ThreadMXBean cpuClock = java.lang.management.ManagementFactory.getThreadMXBean();
    private static long lastCpuNanos;
    private static double cpuWorkMs = Double.NaN;
    private static final PerformanceGovernor governor = new PerformanceGovernor();
    // Effective per-frame settings: user configuration capped by the governor's current level.
    private static int effSoftness = 2, effDrawDistance = 32, effLightsPerCell = 4, effSunDistance = 48, effRefresh = 1, effIndirectInterval = 1;
    private static boolean effIndirect;
    private record HistoryStamp(long grid, long sources, long distant, boolean shadows, int distance, int drawDistance, int sunDistance, int view, boolean materials, boolean indirect, int softness, boolean entities, int indirectSky) {}

    private RayvoxEngine() {}

    public static void registerShaders(RegisterShadersEvent event) {
        RenderSystem.assertOnRenderThread();
        closeGpuCache();
        worldCache = null;
        lights = new LightSourceManager();
        distant = new DistantLightCache();
        frozen = false;
        dynamic = List.of();
        entityCasters = List.of();
        terrain = null;
        lightingPipeline = null;
        sunlightVolume = null;
        volumeFailed = false;
        passFailed = false;
        java.util.Arrays.fill(terrainVariants, null);
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
        var candidates = new TerrainShader[terrainVariants.length];
        try {
            try (var reader = event.getResourceProvider().openAsReader(ResourceLocation.fromNamespaceAndPath("rayvox", "lights.json"))) {
                colors = new LightColors(LightDefinition.read(reader));
            }
            try (var reader = event.getResourceProvider().openAsReader(ResourceLocation.fromNamespaceAndPath("rayvox", "materials.json"))) {
                materials = MaterialTable.read(reader);
            }
            for (int i = 0; i < candidates.length; i++) {
                boolean directional = (i & 2) != 0, advanced = (i & 1) != 0, indirect = (i & 4) != 0;
                var candidate = candidates[i] = new TerrainShader(event.getResourceProvider(), directional, advanced, indirect);
                if (GL20.glGetProgrami(candidate.getId(), GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                    throw new IllegalStateException(GL20.glGetProgramInfoLog(candidate.getId()));
                for (String uniform : new String[]{"ModelViewMat", "ProjMat", "ChunkOffset", "ColorModulator",
                    "FogStart", "FogEnd", "FogColor", "FogShape", "ShadowsEnabled", "ShadowDistance", "ShadowDrawDistance", "AlphaCutoff",
                    "DebugMode", "CacheWidth", "CacheMinSlot", "CameraInCache", "CameraFraction"}) {
                    if (candidate.getUniform(uniform) == null) throw new IllegalStateException("Missing uniform " + uniform);
                }
                if (directional && candidate.getUniform("SunShadowDistance") == null)
                    throw new IllegalStateException("Directional shader lacks its range uniform");
            }
            // GameRenderer owns all variants and closes them on reload and shutdown.
            for (int i = 0; i < candidates.length; i++) {
                int variant = i;
                event.registerShader(candidates[i], loaded -> {
                    terrainVariants[variant] = (TerrainShader)loaded;
                    if (variant == 1) terrain = (TerrainShader)loaded;
                    if (variant == terrainVariants.length - 1) {
                        status = "Terrain shader ready";
                        LOGGER.info("Rayvox terrain shader variants compiled and linked");
                    }
                });
            }
        } catch (Exception failure) {
            for (var candidate : candidates) if (candidate != null) candidate.close();
            status = "Vanilla fallback: shader failed; see latest.log";
            LOGGER.error(status, failure);
            return;
        }
        // GameRenderer owns the pipeline and all its stages through the registered composition shader.
        LightingRenderPipeline pipeline = null;
        try {
            pipeline = new LightingRenderPipeline(event.getResourceProvider());
            event.registerShader(pipeline, loaded -> lightingPipeline = (LightingRenderPipeline)loaded);
        } catch (Exception failure) {
            if (pipeline != null) pipeline.close();
            passFailed = true;
            LOGGER.error("Rayvox deferred lighting pipeline unavailable; legacy pipeline remains active", failure);
        }
        SunlightVolume volume = null;
        try {
            volume = new SunlightVolume(event.getResourceProvider());
            event.registerShader(volume, loaded -> sunlightVolume = (SunlightVolume)loaded);
        } catch (Exception failure) {
            if (volume != null) volume.close();
            volumeFailed = true;
            LOGGER.error("Rayvox godray shaders unavailable; surface lighting remains active", failure);
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
            entityCasters = List.of();
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
        int radius = Rayvox.CACHE_RADIUS.get();
        if (Rayvox.SHADOWS.get())
            radius = Math.max(radius, (Rayvox.SHADOW_DRAW_DISTANCE.get() + 15) / 16);
        if (Rayvox.SUNLIGHT.get() && Rayvox.SHADOWS.get() && normalSky())
            radius = Math.max(radius, (Rayvox.SUN_SHADOW_DISTANCE.get() + 15) / 16);
        if (worldCache == null || worldCache.grid.radius != radius) {
            frozen = false;
            worldCache = new VoxelWorldCache(radius, colors, materials);
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
            renderSunlightVolume(event);
            LightDebugView.render(event, lights, worldCache.grid, debugMode);
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            finishOpaqueTerrain(event);
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || worldCache == null) return;
        try {
            long start = System.nanoTime();
            boolean timing = profiling || Rayvox.GOVERNOR_TARGET.get() > 0;
            if (timing && spanTimer == null && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) {
                spanTimer = new GpuTimer(); passTimer = new GpuTimer(); indirectTimer = new GpuTimer(); spanSamples = 0;
            }
            if (!timing && spanTimer != null) closeSpanTimers();
            if (spanTimer != null) spanTimer.begin();
            resolveQuality(start);
            if (gpuCache == null) gpuCache = new GpuVoxelCache(worldCache.grid.width);
            if (!frozen) gpuCache.upload(worldCache.grid, Rayvox.UPLOAD_BUDGET.get());
            if (gpuLights == null) { gpuLights = new GpuLightCache(worldCache.grid.slots.length); lights.invalidate(); }
            var camera = event.getCamera().getPosition();
            if (!frozen && lights.rebuild(worldCache.grid, (int)Math.floor(camera.x), (int)Math.floor(camera.y),
                    (int)Math.floor(camera.z), Rayvox.ACTIVE_LIGHTS.get(), effLightsPerCell,
                    level.getMinSection(), level.getMaxSection())) { gpuLights.upload(lights); sourceRevision++; }
            if (!frozen) dynamic = Rayvox.DYNAMIC.get() ? DynamicLights.collect(level, camera,
                    event.getPartialTick().getGameTimeDeltaPartialTick(false), colors) : List.of();
            if (!frozen) {
                var nextCasters = Rayvox.SHADOWS.get() && Rayvox.ENTITY_SHADOWS.get()
                        ? EntityShadows.collect(level, camera, event.getPartialTick().getGameTimeDeltaPartialTick(false))
                        : List.<net.minecraft.world.phys.AABB>of();
                if (!entityCasters.equals(nextCasters)) { entityCasters = nextCasters; historyRevision++; }
            }
            if (!frozen) sun = Rayvox.SUNLIGHT.get() && Rayvox.SHADOWS.get() && normalSky()
                    ? DirectionalLight.at(level.getSunAngle(event.getPartialTick().getGameTimeDeltaPartialTick(false)),
                            level.getMoonBrightness(), level.getRainLevel(1), true) : DirectionalLight.OFF;
            if (worldLighting == null) worldLighting = new WorldLightingState();
            if (!frozen) worldLighting.update(worldCache.grid, distant.revision, lights, dynamic, sun, entityCasters);
            // shortcut: any scene edit invalidates all history; use regional keys if profiling justifies their complexity.
            var stamp = new HistoryStamp(worldCache.grid.snapshotRevision, sourceRevision, distant.revision,
                    Rayvox.SHADOWS.get(), Rayvox.SHADOW_DISTANCE.get(), effDrawDistance, effSunDistance, debugMode, Rayvox.MATERIALS.get(), effIndirect, effSoftness, Rayvox.ENTITY_SHADOWS.get(), effIndirect ? sun.epoch() : 0);
            if (!stamp.equals(historyStamp)) { historyStamp = stamp; historyRevision++; }
            boolean historyView = debugMode == 0 || debugMode == 5 || debugMode == 6 || debugMode == 7;
            deferredActive = Rayvox.PIPELINE.get() == LightingPipeline.DEFERRED && lightingPipeline != null && !passFailed
                    && Rayvox.HISTORY.get() && historyView
                    // shortcut: the pass stores scalar sky visibility; retain RGB glass transmission via legacy until it has RGB storage.
                    && !(Rayvox.MATERIALS.get() && sun.strength() > 0);
            if (Rayvox.HISTORY.get() && historyView) {
                if (history == null) history = new LightingHistory();
                var target = Minecraft.getInstance().getMainRenderTarget();
                history.camera(camera.x, camera.y, camera.z, sun.strength() > 0,
                        event.getModelViewMatrix(), event.getProjectionMatrix());
                if (org.lwjgl.opengl.GL30.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING) == target.frameBufferId)
                    history.begin(target.width, target.height, historyRevision, sun.epoch(), effIndirect, deferredActive);
                else history.invalidate();
                deferredActive &= history.deferredAvailable();
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
        if (changedLevel == level && worldLighting != null) {
            var slot = worldCache == null ? null : worldCache.grid.get(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
            if (slot == null || !slot.uploaded) worldLighting.geometryUnavailable();
        }
    }

    public static void columnChanged(ClientLevel changedLevel, int x, int z, boolean unload) {
        if (frozen) return;
        if (changedLevel == level) distant.changed(x, z, level.getMinSection(), unload);
        if (changedLevel == level && worldCache != null) worldCache.columnChanged(x, z, unload);
        if (changedLevel == level && worldLighting != null) worldLighting.geometryUnavailable();
    }

    /** Stage B after opaque terrain: trace new/stale deferred pixels, then close the GPU span measurement. */
    private static void finishOpaqueTerrain(RenderLevelStageEvent event) {
        try {
            if (deferredActive && history != null && lightingPipeline != null && worldCache != null && gpuCache != null
                    && gpuLights != null && history.passReady()) {
                lightingPipeline.renderFrame(history, lightingFrame(event), passTimer, indirectTimer);
            }
        } catch (RuntimeException failure) {
            passFailed = true;
            deferredActive = false;
            if (history != null) history.invalidate();
            LOGGER.error("Rayvox deferred lighting pass failed; reverting to the legacy pipeline", failure);
        }
        if (spanTimer != null) spanTimer.end();
    }

    private static boolean normalSky() {
        return level != null && level.effects().skyType() == net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.NORMAL;
    }

    private static LightingPass.Frame lightingFrame(RenderLevelStageEvent event) {
        var camera = event.getCamera().getPosition();
        return new LightingPass.Frame(worldCache.grid, gpuCache, gpuLights, lights.budgetBoundary,
                camera.x, camera.y, camera.z, sun, materials, entityCasters, Rayvox.SHADOWS.get(),
                Rayvox.SHADOW_DISTANCE.get(), effDrawDistance, effSunDistance, effSoftness, effRefresh, effIndirectInterval,
                dynamic, effIndirect, debugMode, RenderSystem.getShaderFogColor());
    }

    private static void renderSunlightVolume(RenderLevelStageEvent event) {
        if (sunlightVolume == null) return;
        if (volumeFailed || !Rayvox.GODRAYS.get() || !Rayvox.SUNLIGHT.get() || !Rayvox.SHADOWS.get()
                || sun.strength() <= 0 || debugMode != 0 || gpuCache == null || !normalSky()
                || event.getCamera().getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE
                || RenderSystem.getShaderFogEnd() <= 4) {
            sunlightVolume.releaseTargets();
            return;
        }
        try {
            sunlightVolume.render(Minecraft.getInstance().getMainRenderTarget(), lightingFrame(event),
                    event.getModelViewMatrix(), event.getProjectionMatrix(), level.getRainLevel(1), 8 + governor.level() * 2);
        } catch (RuntimeException failure) {
            volumeFailed = true;
            sunlightVolume.releaseTargets();
            LOGGER.error("Rayvox godray pass failed; surface lighting remains active", failure);
        }
    }

    private static void resolveQuality(long now) {
        governor.configure(Rayvox.GOVERNOR_TARGET.get(), Rayvox.GOVERNOR_MIN.get(), Rayvox.GOVERNOR_MAX.get());
        double frameMs = lastFrameNanos == 0 ? Double.NaN : (now - lastFrameNanos) / 1_000_000.0;
        lastFrameNanos = now;
        long cpuNanos = (profiling || Rayvox.GOVERNOR_TARGET.get() > 0) && cpuClock.isCurrentThreadCpuTimeSupported()
                ? cpuClock.getCurrentThreadCpuTime() : 0;
        cpuWorkMs = cpuNanos > 0 && lastCpuNanos > 0 ? (cpuNanos - lastCpuNanos) / 1_000_000.0 : Double.NaN;
        lastCpuNanos = Math.max(0, cpuNanos);
        double gpuMs = Double.NaN;
        if (spanTimer != null && spanTimer.samples() != spanSamples) {
            spanSamples = spanTimer.samples();
            gpuMs = spanTimer.millis() + (sunlightVolume == null ? 0 : sunlightVolume.millis());
        }
        var cap = PerformanceGovernor.limits(frozen ? governor.level() : governor.update(now, frameMs, gpuMs, cpuWorkMs));
        effSoftness = Math.min(Rayvox.SHADOW_SOFTNESS.get(), cap.shadowSoftness());
        effDrawDistance = Math.max(16, Math.min(Rayvox.SHADOW_DRAW_DISTANCE.get(), cap.shadowDrawDistance()));
        effLightsPerCell = Math.max(1, Math.min(Rayvox.LIGHTS_PER_SECTION.get(), cap.lightsPerCell()));
        effSunDistance = Math.max(16, Math.min(Rayvox.SUN_SHADOW_DISTANCE.get(), cap.sunShadowDistance()));
        effRefresh = Math.max(Rayvox.REFRESH_INTERVAL.get(), cap.refreshInterval());
        effIndirect = Rayvox.INDIRECT.get() && cap.indirect();
        effIndirectInterval = Math.max(Rayvox.INDIRECT_INTERVAL.get(), cap.indirectInterval());
    }

    private static void closeSpanTimers() {
        if (spanTimer != null) { spanTimer.close(); spanTimer = null; }
        if (passTimer != null) { passTimer.close(); passTimer = null; }
        if (indirectTimer != null) { indirectTimer.close(); indirectTimer = null; }
        spanSamples = 0;
    }

    private static void closeGpuCache() {
        if (sunlightVolume != null) sunlightVolume.releaseTargets();
        closeWorldLighting();
        if (history != null) { history.close(); history = null; }
        if (timer != null) { timer.close(); timer = null; }
        closeSpanTimers();
        governor.reset();
        lastFrameNanos = 0;
        lastCpuNanos = 0;
        cpuWorkMs = Double.NaN;
        deferredActive = false;
        historyStamp = null;
        if (gpuLights != null) { gpuLights.close(); gpuLights = null; }
        if (gpuCache != null) {
            gpuCache.close();
            gpuCache = null;
        }
    }

    private static void closeWorldLighting() {
        if (worldLighting != null) { worldLighting.close(); worldLighting = null; }
    }

    public static void close() {
        closeGpuCache();
        worldCache = null;
        level = null;
        lights = new LightSourceManager();
        distant = new DistantLightCache();
        frozen = false;
        dynamic = List.of();
        entityCasters = List.of();
    }

    public static void sectionLights(int x, int y, int z, double cameraX, double cameraY, double cameraZ) {
        TerrainShader drawing = null;
        for (var candidate : terrainVariants) if (candidate != null && candidate.drawing) { drawing = candidate; break; }
        if (drawing == null || !drawing.drawing) return;
        int sx = Math.floorDiv(x, 16), sy = Math.floorDiv(y, 16), sz = Math.floorDiv(z, 16);
        boolean ready = distant.ready(sx, sz);
        var selected = ready ? distant.section(sx, sy, sz, lights) : List.<LightSourceManager.Light>of();
        if (ready && !lights.testLights.isEmpty()) {
            var combined = new java.util.ArrayList<>(selected);
            combined.addAll(lights.testLights);
            selected = DistantLightCache.select(combined, x, y, z, lights::definition);
        }
        drawing.section(selected, lights, ready, cameraX, cameraY, cameraZ);
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
        int variant = (Rayvox.MATERIALS.get() ? 1 : 0) | (sun.strength() > 0 ? 2 : 0) | (effIndirect ? 4 : 0);
        var shader = terrainVariants[variant];
        if (shader == null) return vanilla;
        shader.getUniform("ShadowsEnabled").set(Rayvox.SHADOWS.get() ? 1 : 0);
        shader.getUniform("ShadowDistance").set((float)Rayvox.SHADOW_DISTANCE.get());
        shader.getUniform("ShadowDrawDistance").set((float)effDrawDistance);
        shader.budgetBoundary = lights.budgetBoundary;
        var sunDistance = shader.getUniform("SunShadowDistance");
        if (sunDistance != null) sunDistance.set((float)effSunDistance);
        shader.getUniform("AlphaCutoff").set(layer == RenderType.cutout() ? 0.1f : layer == RenderType.cutoutMipped() ? 0.5f : 0.0f);
        shader.getUniform("DebugMode").set(debugMode);
        var grid = worldCache.grid;
        int minX = grid.centerX - grid.radius, minY = grid.centerY - grid.radius, minZ = grid.centerZ - grid.radius;
        int bx = (int)Math.floor(cameraX), by = (int)Math.floor(cameraY), bz = (int)Math.floor(cameraZ);
        shader.cache = gpuCache;
        shader.lights = gpuLights;
        shader.history = history;
        shader.timer = timer;
        shader.dynamic = dynamic;
        shader.entityCasters = entityCasters;
        shader.sun = sun;
        shader.materials = materials;
        shader.cameraX = cameraX; shader.cameraY = cameraY; shader.cameraZ = cameraZ;
        shader.getUniform("CacheWidth").set(grid.width);
        shader.getUniform("CacheMinSlot").set(Math.floorMod(minX, grid.width), Math.floorMod(minY, grid.width), Math.floorMod(minZ, grid.width));
        shader.getUniform("CameraInCache").set(bx - minX * 16, by - minY * 16, bz - minZ * 16);
        shader.getUniform("CameraFraction").set((float)(cameraX - bx), (float)(cameraY - by), (float)(cameraZ - bz));
        return shader;
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
            closeWorldLighting();
        }
        if (history != null) history.invalidate();
    }
    public static boolean profiling() { return profiling; }
    public static void toggleProfiling() { profiling = !profiling; }
    public static String historyStats() { return history == null ? "Lighting history OFF" : history.status; }
    static int shadowSoftness() { return effSoftness; }
    static boolean indirectActive() { return effIndirect; }
    public static boolean deferredActive() { return deferredActive; }
    public static long godrayDraws() { return sunlightVolume == null ? 0 : sunlightVolume.draws; }
    public static double godrayMillis() { return sunlightVolume == null ? 0 : sunlightVolume.millis(); }
    public static String pipelineStats() {
        String mode = Rayvox.PIPELINE.get() == LightingPipeline.LEGACY ? "LEGACY (inline terrain tracing)"
                : deferredActive ? "DEFERRED (same-frame composition; static refresh 1/" + effRefresh + ")"
                : "DEFERRED requested, inactive: " + (passFailed || lightingPipeline == null ? "pass unavailable"
                        : !Rayvox.HISTORY.get() ? "requires lighting history"
                        : Rayvox.MATERIALS.get() && sun.strength() > 0 ? "RGB directional transmission uses legacy"
                        : "diagnostic view or history unavailable");
        return "Pipeline: " + mode + "; preset " + Rayvox.PRESET.get();
    }
    public static String governorStats() {
        return governor.status() + String.format(java.util.Locale.ROOT, "; effective softness %d, shadow draw %d, sky %d, lights/cell %d, GI %s (cadence %d)",
                effSoftness, effDrawDistance, effSunDistance, effLightsPerCell, effIndirect ? "on" : "off", effIndirectInterval);
    }
    public static String spanStats() {
        if (spanTimer == null) return "Lighting span GPU timing OFF (enable profiler or governor)";
        return String.format(java.util.Locale.ROOT, "GPU span sky→opaque terrain %.3f ms (incl. vanilla draws); deferred pass %s; indirect pass %s",
                spanTimer.millis(), passTimer == null || passTimer.samples() == 0 ? "n/a"
                        : String.format(java.util.Locale.ROOT, "%.3f ms", passTimer.millis()),
                indirectTimer == null || indirectTimer.samples() == 0 ? "n/a"
                        : String.format(java.util.Locale.ROOT, "%.3f ms", indirectTimer.millis()));
    }
    public static double spanMillis() { return spanTimer == null || spanTimer.samples() == 0 ? Double.NaN : spanTimer.millis(); }
    public static double passMillis() { return passTimer == null || passTimer.samples() == 0 ? Double.NaN : passTimer.millis(); }
    public static int governorLevel() { return governor.level(); }
    public static double cpuWorkMillis() { return cpuWorkMs; }
    public static long lightingPassDraws() { return lightingPipeline == null ? 0 : lightingPipeline.direct.draws; }
    public static long indirectPassDraws() { return lightingPipeline == null ? 0 : lightingPipeline.indirect.draws; }
    public static long compositionPassDraws() { return lightingPipeline == null ? 0 : lightingPipeline.draws; }
    public static long dynamicPassDraws() { return lightingPipeline == null ? 0 : lightingPipeline.dynamic.draws; }
    public static int dynamicLightCount() { return dynamic.size(); }
    public static double indirectPassMillis() { return indirectTimer == null || indirectTimer.samples() == 0 ? Double.NaN : indirectTimer.millis(); }
    public static String worldLightingStats() { return worldLighting == null ? "World lighting bookkeeping inactive" : worldLighting.status(); }
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
                + " KiB; uploaded " + gpuCache.uploadedLastFrame + " sections / "
                + gpuCache.uploadedBytesLastFrame / 1024 + " KiB this frame";
    }

    public static String status() { return status; }
    public static int entityShadowCount() { return Math.min(EntityShadows.MAX_CASTERS, entityCasters.size()); }
    public static String gpu() { return gpu; }
    public static boolean computeAvailable() { return compute; }
}
