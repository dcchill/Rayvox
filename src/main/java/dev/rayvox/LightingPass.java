package dev.rayvox;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Stage B of the deferred pipeline. After opaque terrain, traces static block-light shadows and sun visibility
 * once per visible new or stale pixel, or progressively gathers indirect light into its separate history.
 * Only the full-screen triangle is drawn; no geometry is rendered twice and nothing is read back.
 */
class LightingPass extends ShaderInstance {
    enum Kind { STATIC, INDIRECT, DYNAMIC, COMPOSITION, PROBE }
    private static final String[] SAMPLERS = {"VoxelData", "VoxelState", "VoxelShapes", "LightData", "LightLists", "PassKey", "PassInputs", "PassAlbedo"};
    private static final int FIRST_UNIT = 3;
    private final String[] samplers;
    private final int[] samplerLocations, savedTextures;
    private final int[] passTextures;
    private final Kind kind;
    private final int cacheWidth, cacheMinSlot, cameraInCache, cameraFraction, shadowsEnabled, shadowDistance,
            shadowDrawDistance, shadowBudget, sunShadowDistance, sunDirection, sunRadiance, advanced, transmission,
            refreshInterval, frameIndex, entityCount, entityData, softness,
            inverseClip, dynamicCount, dynamicRecords, indirectEnabled, debugMode, fogColor, probeOrigin, worldIndirectEnabled, worldProbeChanged;
    private final float[] moving = new float[32];
    private final int culledLocation;
    private final int cullingDisabledLocation, metalLocation;
    private final int occupancyLocation;
    private final LightRadianceCache radiances = new LightRadianceCache();
    private final int[] viewport = new int[4];
    private final java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(4);
    private int vao, frame;
    private MaterialTable publishedMaterials;
    long draws;

    LightingPass(ResourceProvider resources) throws IOException {
        this(resources, Kind.STATIC);
    }

    LightingPass(ResourceProvider resources, Kind kind) throws IOException {
        super(resources, ResourceLocation.fromNamespaceAndPath("rayvox", switch (kind) {
            case STATIC -> "lighting_pass"; case INDIRECT -> "indirect_pass";
            case DYNAMIC -> "dynamic_pass"; case COMPOSITION -> "composition_pass";
            case PROBE -> "world_probe_pass";
        }), DefaultVertexFormat.POSITION);
        this.kind = kind;
        if (glGetProgrami(getId(), GL_LINK_STATUS) != GL_TRUE) {
            String failure = glGetProgramInfoLog(getId()); close(); throw new IllegalStateException(failure);
        }
        samplers = switch (kind) {
            case STATIC -> SAMPLERS;
            case INDIRECT -> new String[]{"VoxelData", "VoxelState", "VoxelShapes", "LightData", "LightLists", "PassKey", "PassInputs", "PassIndirect", "WorldIndirect", "WorldProbeState"};
            case PROBE -> new String[]{"VoxelData", "VoxelState", "VoxelShapes", "LightData", "LightLists"};
            case DYNAMIC -> new String[]{"VoxelData", "VoxelState", "VoxelShapes", "PassKey", "PassDepth", "PassAlbedo"};
            case COMPOSITION -> new String[]{"VoxelData", "VoxelState", "VoxelShapes", "LightData", "LightLists", "SceneColor", "PassKey", "PassLighting", "PassIndirect", "PassDynamic", "PassBase", "PassEmission", "PassAlbedo", "PassInputs", "PassDepth"};
        };
        samplerLocations = new int[samplers.length];
        savedTextures = new int[samplers.length];
        passTextures = new int[samplers.length];
        int program = getId();
        culledLocation = glGetUniformLocation(program, "CulledLights");
        cullingDisabledLocation = glGetUniformLocation(program, "CullingDisabled");
        metalLocation = glGetUniformLocation(program, "MetalReflections");
        occupancyLocation = glGetUniformLocation(program, "VoxelOccupancy");
        for (int i = 0; i < samplers.length; i++) samplerLocations[i] = glGetUniformLocation(program, samplers[i]);
        cacheWidth = glGetUniformLocation(program, "CacheWidth");
        cacheMinSlot = glGetUniformLocation(program, "CacheMinSlot");
        cameraInCache = glGetUniformLocation(program, "CameraInCache");
        cameraFraction = glGetUniformLocation(program, "CameraFraction");
        shadowsEnabled = glGetUniformLocation(program, "ShadowsEnabled");
        shadowDistance = glGetUniformLocation(program, "ShadowDistance");
        shadowDrawDistance = glGetUniformLocation(program, "ShadowDrawDistance");
        shadowBudget = glGetUniformLocation(program, "ShadowBudget");
        sunShadowDistance = glGetUniformLocation(program, "SunShadowDistance");
        sunDirection = glGetUniformLocation(program, "SunDirection");
        sunRadiance = glGetUniformLocation(program, "SunRadiance");
        advanced = glGetUniformLocation(program, "AdvancedMaterials");
        transmission = glGetUniformLocation(program, "MaterialTransmission[0]");
        refreshInterval = glGetUniformLocation(program, "RefreshInterval");
        frameIndex = glGetUniformLocation(program, "FrameIndex");
        entityCount = glGetUniformLocation(program, "EntityCount");
        entityData = glGetUniformLocation(program, "EntityData");
        softness = glGetUniformLocation(program, "ShadowSoftness");
        inverseClip = glGetUniformLocation(program, "InverseClipMat");
        dynamicCount = glGetUniformLocation(program, "DynamicCount");
        dynamicRecords = glGetUniformLocation(program, "DynamicRecords[0]");
        indirectEnabled = glGetUniformLocation(program, "IndirectEnabled");
        debugMode = glGetUniformLocation(program, "DebugMode");
        fogColor = glGetUniformLocation(program, "FogColor");
        probeOrigin = glGetUniformLocation(program, "ProbeOrigin");
        worldIndirectEnabled = glGetUniformLocation(program, "WorldIndirectEnabled");
        worldProbeChanged = glGetUniformLocation(program, "WorldProbeChanged");
        for (int i = 0; i < samplers.length; i++) {
            if (samplerLocations[i] < 0) {
                close();
                throw new IllegalStateException("Lighting pass lacks sampler " + samplers[i]);
            }
        }
    }

    @Override public void attachToProgram() {
        super.attachToProgram();
        glBindFragDataLocation(getId(), 0, "lighting");
    }

    record Frame(VoxelGrid grid, GpuVoxelCache cache, GpuLightCache lights, LightSourceManager.Light budgetBoundary,
            double cameraX, double cameraY, double cameraZ, DirectionalLight sun, MaterialTable materials,
            GpuEntityShadows entities, boolean shadows, float shadowDistance,
            float shadowDrawDistance, float sunShadowDistance, int softness, int refreshInterval, int indirectInterval,
            java.util.List<LightSourceManager.Light> dynamic, boolean indirect, int debugMode, float[] fogColor,
            GpuWorldIndirect worldIndirect) {}

    void render(LightingHistory history, Frame f, GpuTimer timer) {
        render(history, null, null, f, timer);
    }

    void renderProbes(GpuWorldIndirect target, WorldLightingCache.Region region, Frame f, GpuTimer timer) {
        if (kind != Kind.PROBE) throw new IllegalStateException("Not a probe producer");
        render(null, target, region, f, timer);
    }

    private void render(LightingHistory history, GpuWorldIndirect target, WorldLightingCache.Region region, Frame f, GpuTimer timer) {
        RenderSystem.assertOnRenderThread();
        if (history != null && !history.passReady()) return;
        int previousFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE18);
        int previousEntities=glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(previousActive);
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST), depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        glGetBooleanv(GL_COLOR_WRITEMASK, mask);
        boolean r = mask.get(0) != 0, g = mask.get(1) != 0, b = mask.get(2) != 0, a = mask.get(3) != 0;
        // Global state uses RenderSystem; indexed blend/masks stay scoped to draw buffer zero.
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        glDisablei(GL_BLEND, 0);
        RenderSystem.disableCull();
        com.mojang.blaze3d.platform.GlStateManager._disableScissorTest();
        glColorMaski(0, true, true, true, true);
        boolean applied = false;
        int boundTextures = 0;
        int previousOccupancy = 0;
        boolean occupancyBound = false;
        try {
            boolean ready = switch (kind) {
                case STATIC -> history.bindPassTarget(); case INDIRECT -> history.bindIndirectPassTarget();
                case DYNAMIC -> history.bindDynamicPassTarget(); case COMPOSITION -> history.bindCompositionTarget();
                case PROBE -> { target.bind(region); yield true; }
            };
            if (!ready)
                throw new IllegalStateException("Incomplete deferred lighting framebuffer");
            apply();
            applied = true;
            publish(f);
            glActiveTexture(GL_TEXTURE18);
            glBindTexture(GL_TEXTURE_2D,f.entities()==null?0:f.entities().texture);
            // Composition has no entity occluders, so keep its 2D sampler off the occupancy unit.
            glUniform1i(entityData, kind == Kind.COMPOSITION ? 2 : 18);
            if (history != null) history.uploadInverseClip(inverseClip);
            if (kind == Kind.PROBE) glUniform3i(probeOrigin,
                    (region.x() - f.grid().centerX + f.grid().radius) * 16,
                    (region.y() - f.grid().centerY + f.grid().radius) * 16,
                    (region.z() - f.grid().centerZ + f.grid().radius) * 16);
            fillTextures(history, f);
            for (int i = 0; i < samplers.length; i++) {
                int textureType = textureTarget(i);
                glActiveTexture(GL_TEXTURE0 + FIRST_UNIT + i);
                savedTextures[i] = glGetInteger(textureType == GL_TEXTURE_3D ? GL_TEXTURE_BINDING_3D : GL_TEXTURE_BINDING_2D);
                boundTextures = i + 1;
                glBindTexture(textureType, passTextures[i]);
                glUniform1i(samplerLocations[i], FIRST_UNIT + i);
            }
            int occupancyUnit = FIRST_UNIT + samplers.length;
            glActiveTexture(GL_TEXTURE0 + occupancyUnit);
            previousOccupancy = glGetInteger(GL_TEXTURE_BINDING_3D);
            glBindTexture(GL_TEXTURE_3D, f.cache().occupancy);
            occupancyBound = true;
            glUniform1i(occupancyLocation, occupancyUnit);
            glActiveTexture(previousActive);
            if (vao == 0) vao = glGenVertexArrays();
            glBindVertexArray(vao);
            if (history != null) glViewport(0, 0, history.width(), history.height());
            if (timer != null) timer.begin();
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (kind == Kind.INDIRECT) history.finishIndirectPass();
            if (kind == Kind.COMPOSITION) history.finishComposition();
            draws++;
        } finally {
            if (timer != null) timer.end();
            if (applied) clear();
            for (int i = 0; i < boundTextures; i++) {
                glActiveTexture(GL_TEXTURE0 + FIRST_UNIT + i);
                glBindTexture(textureTarget(i), savedTextures[i]);
            }
            if (occupancyBound) {
                glActiveTexture(GL_TEXTURE0 + FIRST_UNIT + samplers.length);
                glBindTexture(GL_TEXTURE_3D, previousOccupancy);
            }
            glActiveTexture(GL_TEXTURE18); glBindTexture(GL_TEXTURE_2D,previousEntities);
            glActiveTexture(previousActive);
            glBindVertexArray(previousVao);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (depth) RenderSystem.enableDepthTest();
            RenderSystem.depthMask(depthMask);
            if (blend) glEnablei(GL_BLEND, 0);
            if (cull) RenderSystem.enableCull();
            if (scissor) com.mojang.blaze3d.platform.GlStateManager._enableScissorTest();
            glColorMaski(0, r, g, b, a);
        }
    }

    private int textureTarget(int sampler) { return sampler < 3 ? GL_TEXTURE_3D : GL_TEXTURE_2D; }

    private void fillTextures(LightingHistory history, Frame f) {
        passTextures[0] = f.cache().voxels; passTextures[1] = f.cache().states; passTextures[2] = f.cache().shapes;
        switch (kind) {
            case STATIC -> {
                passTextures[3] = f.lights().data; passTextures[4] = f.lights().lists;
                passTextures[5] = history.passKey(); passTextures[6] = history.passInputs(); passTextures[7] = history.passAlbedo();
            }
            case INDIRECT -> {
                passTextures[3] = f.lights().data; passTextures[4] = f.lights().lists;
                passTextures[5] = history.passKey(); passTextures[6] = history.passInputs(); passTextures[7] = history.passIndirect();
                passTextures[8] = f.worldIndirect() == null ? 0 : f.worldIndirect().irradiance;
                passTextures[9] = f.worldIndirect() == null ? 0 : f.worldIndirect().validity;
            }
            case PROBE -> { passTextures[3] = f.lights().data; passTextures[4] = f.lights().lists; }
            case DYNAMIC -> {
                passTextures[3] = history.passKey(); passTextures[4] = history.sceneDepth(); passTextures[5] = history.passAlbedo();
            }
            case COMPOSITION -> {
                passTextures[3] = f.lights().data; passTextures[4] = f.lights().lists; passTextures[5] = history.sceneColor();
                passTextures[6] = history.passKey(); passTextures[7] = history.passLighting(); passTextures[8] = history.passIndirect();
                passTextures[9] = history.passDynamic(); passTextures[10] = history.passBase(); passTextures[11] = history.passEmission();
                passTextures[12] = history.passAlbedo(); passTextures[13] = history.passInputs(); passTextures[14] = history.sceneDepth();
            }
        }
    }

    private void publish(Frame f) {
        glUniform1i(cullingDisabledLocation, Rayvox.CULLING.get() ? 0 : 1);
        glUniform2i(culledLocation, RayvoxEngine.culledLightsLow(), RayvoxEngine.culledLightsHigh());
        var grid = f.grid();
        int minX = grid.centerX - grid.radius, minY = grid.centerY - grid.radius, minZ = grid.centerZ - grid.radius;
        int bx = (int)Math.floor(f.cameraX()), by = (int)Math.floor(f.cameraY()), bz = (int)Math.floor(f.cameraZ());
        glUniform1i(cacheWidth, grid.width);
        glUniform3i(cacheMinSlot, Math.floorMod(minX, grid.width), Math.floorMod(minY, grid.width), Math.floorMod(minZ, grid.width));
        glUniform3i(cameraInCache, bx - minX * 16, by - minY * 16, bz - minZ * 16);
        glUniform3f(cameraFraction, (float)(f.cameraX() - bx), (float)(f.cameraY() - by), (float)(f.cameraZ() - bz));
        glUniform1i(shadowsEnabled, f.shadows() ? 1 : 0);
        glUniform1f(shadowDistance, f.shadowDistance());
        glUniform1f(shadowDrawDistance, f.shadowDrawDistance());
        var boundary = f.budgetBoundary();
        if (boundary == null) glUniform4f(shadowBudget, 0, 0, 0, 0);
        else glUniform4f(shadowBudget, (float)(boundary.x() - f.cameraX()), (float)(boundary.y() - f.cameraY()),
                (float)(boundary.z() - f.cameraZ()), 1);
        glUniform1f(sunShadowDistance, f.sunShadowDistance());
        var sun = f.sun();
        glUniform4f(sunDirection, sun.x(), sun.y(), sun.z(), sun.strength());
        glUniform3f(sunRadiance, sun.red(), sun.green(), sun.blue());
        glUniform1i(advanced, Rayvox.MATERIALS.get() ? 1 : 0);
        if (publishedMaterials != f.materials()) { glUniform3fv(transmission, f.materials().transmission); publishedMaterials = f.materials(); }
        glUniform1i(refreshInterval, kind == Kind.INDIRECT ? f.indirectInterval() : f.refreshInterval());
        glUniform1i(frameIndex, frame++ & 0xffff);
        glUniform1i(softness, f.softness());
        glUniform1i(entityCount, kind == Kind.STATIC || kind == Kind.INDIRECT || kind == Kind.PROBE || f.entities()==null ? 0 : f.entities().count);
        int movingCount = TerrainShader.packDynamic(f.dynamic(), f.cameraX(), f.cameraY(), f.cameraZ(), radiances, moving);
        glUniform1i(dynamicCount, movingCount);
        if (movingCount > 0) glUniform4fv(dynamicRecords, moving);
        glUniform1i(metalLocation, Rayvox.METAL_REFLECTIONS.get() ? 1 : 0);
        glUniform1i(indirectEnabled, f.indirect() ? 1 : 0);
        glUniform1i(debugMode, f.debugMode());
        glUniform4fv(fogColor, f.fogColor());
        glUniform1i(worldIndirectEnabled, f.worldIndirect() == null ? 0 : 1);
        glUniform1i(worldProbeChanged, f.worldIndirect() != null && f.worldIndirect().changed() ? 1 : 0);
    }

    @Override public void close() {
        if (vao != 0) glDeleteVertexArrays(vao);
        vao = 0;
        super.close();
    }
}
