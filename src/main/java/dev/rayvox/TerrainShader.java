package dev.rayvox;

import java.io.IOException;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import static org.lwjgl.opengl.GL33C.*;

/** ShaderInstance binds 2D samplers only; keep 3D bindings scoped to this terrain draw. */
final class TerrainShader extends ShaderInstance {
    GpuVoxelCache cache;
    GpuLightCache lights;
    boolean drawing, translucent;
    long draws;
    LightingHistory history;
    GpuTimer timer;
    java.util.List<LightSourceManager.Light> dynamic = java.util.List.of();
    GpuEntityShadows entities;
    double cameraX, cameraY, cameraZ;
    DirectionalLight sun = DirectionalLight.OFF;
    LightSourceManager.Light budgetBoundary;
    MaterialTable materials;
    private int previousShapes, previousSurface, previousEntities, previousOccupancy;
    private final int surfaceLocation, surfaceReadyLocation, bumpLocation, metalLocation, translucentLocation;
    private final float[] moving = new float[32];
    private final SectionLightUniforms sectionUniforms;
    private final int shapesLocation, occupancyLocation, advancedLocation, indirectLocation, transmissionLocation, budgetLocation;
    private final int sunLocation, sunRadianceLocation, dynamicCountLocation, dynamicRecordsLocation, historyValidLocation, deferredLocation;
    private final int entityCountLocation, entityDataLocation, softnessLocation;
    private final int culledLocation;
    private final int cullingDisabledLocation;
    private final int historyAvailableLocation;
    private final float[] distant = new float[LightSourceManager.PER_SECTION * 8];
    private final int[] locations = new int[4];
    private final int[] previous = new int[4];
    private final LightRadianceCache radiances = new LightRadianceCache();
    private MaterialTable publishedMaterials;

    TerrainShader(ResourceProvider resources, boolean directional, boolean advanced, boolean indirect) throws IOException {
        this(resources, (directional ? "terrain" : "terrain_point") + (advanced ? "" : "_basic")
                + (indirect ? "" : "_direct"), false);
    }

    TerrainShader(ResourceProvider resources, String name, boolean entity) throws IOException {
        super(resources, ResourceLocation.fromNamespaceAndPath("rayvox", name),
                entity ? DefaultVertexFormat.NEW_ENTITY : DefaultVertexFormat.BLOCK);
        String[] names = {"VoxelData", "VoxelState", "LightData", "LightLists"};
        for (int i = 0; i < 4; i++) locations[i] = glGetUniformLocation(getId(), names[i]);
        sectionUniforms = new SectionLightUniforms(getId());
        surfaceLocation = glGetUniformLocation(getId(), "SurfaceAtlas");
        surfaceReadyLocation = glGetUniformLocation(getId(), "SurfaceReady");
        bumpLocation = glGetUniformLocation(getId(), "TextureBump");
        metalLocation = glGetUniformLocation(getId(), "MetalReflections");
        translucentLocation = glGetUniformLocation(getId(), "TranslucentSurface");
        shapesLocation = glGetUniformLocation(getId(), "VoxelShapes");
        occupancyLocation = glGetUniformLocation(getId(), "VoxelOccupancy");
        advancedLocation = glGetUniformLocation(getId(), "AdvancedMaterials");
        indirectLocation = glGetUniformLocation(getId(), "IndirectEnabled");
        transmissionLocation = glGetUniformLocation(getId(), "MaterialTransmission[0]");
        sunLocation = glGetUniformLocation(getId(), "SunDirection");
        sunRadianceLocation = glGetUniformLocation(getId(), "SunRadiance");
        dynamicCountLocation = glGetUniformLocation(getId(), "DynamicCount");
        dynamicRecordsLocation = glGetUniformLocation(getId(), "DynamicRecords[0]");
        historyValidLocation = glGetUniformLocation(getId(), "HistoryValid");
        deferredLocation = glGetUniformLocation(getId(), "DeferredLighting");
        budgetLocation = glGetUniformLocation(getId(), "ShadowBudget");
        entityCountLocation = glGetUniformLocation(getId(), "EntityCount");
        entityDataLocation = glGetUniformLocation(getId(), "EntityData");
        softnessLocation = glGetUniformLocation(getId(), "ShadowSoftness");
        culledLocation = glGetUniformLocation(getId(), "CulledLights");
        cullingDisabledLocation = glGetUniformLocation(getId(), "CullingDisabled");
        historyAvailableLocation = glGetUniformLocation(getId(), "HistoryAvailable");
    }

    @Override public void attachToProgram() {
        super.attachToProgram();
        glBindFragDataLocation(getId(), 0, "fragColor");
        glBindFragDataLocation(getId(), 1, "cachedLight");
        glBindFragDataLocation(getId(), 2, "cachedKey");
        glBindFragDataLocation(getId(), 3, "cachedIndirect");
        glBindFragDataLocation(getId(), 4, "deferredInputs");
        glBindFragDataLocation(getId(), 5, "geometryBase");
        glBindFragDataLocation(getId(), 6, "geometryEmission");
        glBindFragDataLocation(getId(), 7, "geometryAlbedo");
    }

    @Override
    public void apply() {
        super.apply();
        glUniform1i(translucentLocation, translucent ? 1 : 0);
        glUniform1i(cullingDisabledLocation, Rayvox.CULLING.get() ? 0 : 1);
        glUniform2i(culledLocation, RayvoxEngine.culledLightsLow(), RayvoxEngine.culledLightsHigh());
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE12);
        previousEntities = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, entities == null ? 0 : entities.texture);
        glUniform1i(entityDataLocation,12);
        glUniform1i(entityCountLocation,entities == null ? 0 : entities.count);
        if (surfaceLocation >= 0) {
            glActiveTexture(GL_TEXTURE0);
            int surface = SurfaceAtlas.texture(glGetInteger(GL_TEXTURE_BINDING_2D));
            glActiveTexture(GL_TEXTURE11);
            previousSurface = glGetInteger(GL_TEXTURE_BINDING_2D);
            glBindTexture(GL_TEXTURE_2D, surface);
            glUniform1i(surfaceLocation, 11);
            glUniform1i(surfaceReadyLocation, surface != 0 ? 1 : 0);
            glUniform1i(bumpLocation, Rayvox.TEXTURE_BUMP.get() ? 1 : 0);
            glUniform1i(metalLocation, Rayvox.METAL_REFLECTIONS.get() ? 1 : 0);
        }
        for (int i = 0; i < 4; i++) {
            glActiveTexture(GL_TEXTURE3 + i);
            int target = i < 2 ? GL_TEXTURE_3D : GL_TEXTURE_2D;
            previous[i] = glGetInteger(i < 2 ? GL_TEXTURE_BINDING_3D : GL_TEXTURE_BINDING_2D);
            int texture = switch (i) { case 0 -> cache.voxels; case 1 -> cache.states; case 2 -> lights.data; default -> lights.lists; };
            glBindTexture(target, texture);
            glUniform1i(locations[i], 3 + i);
        }
        glActiveTexture(active);
        if (history != null) history.attach(getId());
        else {
            glUniform1i(historyAvailableLocation, 0);
            glUniform1i(historyValidLocation, 0);
            glUniform1i(deferredLocation, 0);
        }
        glActiveTexture(GL_TEXTURE9);
        previousShapes = glGetInteger(GL_TEXTURE_BINDING_3D);
        glBindTexture(GL_TEXTURE_3D, cache.shapes);
        glUniform1i(shapesLocation, 9);
        glActiveTexture(GL_TEXTURE13);
        previousOccupancy = glGetInteger(GL_TEXTURE_BINDING_3D);
        glBindTexture(GL_TEXTURE_3D, cache.occupancy);
        glUniform1i(occupancyLocation, 13);
        glActiveTexture(active);
        glUniform1i(advancedLocation, Rayvox.MATERIALS.get() ? 1 : 0);
        glUniform1i(indirectLocation, RayvoxEngine.indirectActive() ? 1 : 0);
        // A table is immutable after resource loading; raw uniforms survive program rebindings.
        if (publishedMaterials != materials) {
            glUniform3fv(transmissionLocation, materials.transmission);
            publishedMaterials = materials;
        }
        glUniform4f(sunLocation, sun.x(), sun.y(), sun.z(), sun.strength());
        glUniform3f(sunRadianceLocation, sun.red(), sun.green(), sun.blue());
        glUniform1i(softnessLocation, RayvoxEngine.shadowSoftness());
        if (budgetBoundary == null) glUniform4f(budgetLocation, 0, 0, 0, 0);
        else glUniform4f(budgetLocation, (float)(budgetBoundary.x() - cameraX),
                (float)(budgetBoundary.y() - cameraY), (float)(budgetBoundary.z() - cameraZ), 1);
        int dynamicCount = packDynamic(dynamic, cameraX, cameraY, cameraZ, radiances, moving);
        glUniform1i(dynamicCountLocation, dynamicCount);
        if (dynamicCount > 0) glUniform4fv(dynamicRecordsLocation, moving);
        if (timer != null) timer.begin();
        drawing = true;
        draws++;
    }

    static int packDynamic(java.util.List<LightSourceManager.Light> dynamic, double cameraX, double cameraY,
            double cameraZ, LightRadianceCache radiances, float[] moving) {
        int count = Math.min(4, dynamic.size());
        for (int i = 0; i < count; i++) {
            var source = dynamic.get(i); var def = source.definition(); int offset = i * 8;
            var rgb = radiances.get(def);
            moving[offset] = (float)(source.x() - cameraX);
            moving[offset + 1] = (float)(source.y() - cameraY);
            moving[offset + 2] = (float)(source.z() - cameraZ);
            moving[offset + 3] = def.radius();
            moving[offset + 4] = rgb[0];
            moving[offset + 5] = rgb[1];
            moving[offset + 6] = rgb[2];
            moving[offset + 7] = def.shadows() ? 1 : 0;
        }
        return count;
    }

    void section(java.util.List<LightSourceManager.Light> selected, LightSourceManager manager, boolean ready, double x, double y, double z) {
        int count = Math.min(LightSourceManager.PER_SECTION, selected.size());
        for (int i = 0; i < count; i++) {
            var light = selected.get(i); var def = manager.definition(light); int offset = i * 8;
            var rgb = radiances.get(def);
            distant[offset] = (float)(light.x() - x);
            distant[offset + 1] = (float)(light.y() - y);
            distant[offset + 2] = (float)(light.z() - z);
            distant[offset + 3] = def.radius();
            distant[offset + 4] = rgb[0];
            distant[offset + 5] = rgb[1];
            distant[offset + 6] = rgb[2];
            // Match the stable section light to its optional near-cache shadow record.
            distant[offset + 7] = manager.active.indexOf(light) + 1;
        }
        sectionUniforms.publish(count, ready, distant);
    }

    @Override
    public void clear() {
        if (timer != null) timer.end();
        if (history != null) history.detach();
        int savedActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE12);
        glBindTexture(GL_TEXTURE_2D,previousEntities);
        if (surfaceLocation >= 0) {
            glActiveTexture(GL_TEXTURE11);
            glBindTexture(GL_TEXTURE_2D, previousSurface);
        }
        glActiveTexture(GL_TEXTURE9);
        glBindTexture(GL_TEXTURE_3D, previousShapes);
        glActiveTexture(GL_TEXTURE13);
        glBindTexture(GL_TEXTURE_3D, previousOccupancy);
        glActiveTexture(savedActive);
        drawing = false;
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int i = 0; i < 4; i++) {
            glActiveTexture(GL_TEXTURE3 + i);
            glBindTexture(i < 2 ? GL_TEXTURE_3D : GL_TEXTURE_2D, previous[i]);
        }
        glActiveTexture(active);
        super.clear();
    }
}
