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
    boolean drawing;
    LightingHistory history;
    GpuTimer timer;
    java.util.List<LightSourceManager.Light> dynamic = java.util.List.of();
    double cameraX, cameraY, cameraZ;
    DirectionalLight sun = DirectionalLight.OFF;
    MaterialTable materials;
    private int previousShapes;
    private final float[] moving = new float[32];
    private final SectionLightUniforms sectionUniforms;
    private final int shapesLocation, advancedLocation, indirectLocation, transmissionLocation;
    private final int sunLocation, dynamicCountLocation, dynamicRecordsLocation, historyValidLocation;
    private final float[] distant = new float[LightSourceManager.PER_SECTION * 8];
    private final int[] locations = new int[4];
    private final int[] previous = new int[4];
    private final LightRadianceCache radiances = new LightRadianceCache();
    private MaterialTable publishedMaterials;

    TerrainShader(ResourceProvider resources, boolean directional, boolean advanced, boolean indirect) throws IOException {
        super(resources, ResourceLocation.fromNamespaceAndPath("rayvox",
                (directional ? "terrain" : "terrain_point") + (advanced ? "" : "_basic") + (indirect ? "" : "_direct")), DefaultVertexFormat.BLOCK);
        String[] names = {"VoxelData", "VoxelState", "LightData", "LightLists"};
        for (int i = 0; i < 4; i++) locations[i] = glGetUniformLocation(getId(), names[i]);
        sectionUniforms = new SectionLightUniforms(getId());
        shapesLocation = glGetUniformLocation(getId(), "VoxelShapes");
        advancedLocation = glGetUniformLocation(getId(), "AdvancedMaterials");
        indirectLocation = glGetUniformLocation(getId(), "IndirectEnabled");
        transmissionLocation = glGetUniformLocation(getId(), "MaterialTransmission[0]");
        sunLocation = glGetUniformLocation(getId(), "SunDirection");
        dynamicCountLocation = glGetUniformLocation(getId(), "DynamicCount");
        dynamicRecordsLocation = glGetUniformLocation(getId(), "DynamicRecords[0]");
        historyValidLocation = glGetUniformLocation(getId(), "HistoryValid");
    }

    @Override public void attachToProgram() {
        super.attachToProgram();
        glBindFragDataLocation(getId(), 0, "fragColor");
        glBindFragDataLocation(getId(), 1, "cachedLight");
        glBindFragDataLocation(getId(), 2, "cachedKey");
        glBindFragDataLocation(getId(), 3, "cachedIndirect");
    }

    @Override
    public void apply() {
        super.apply();
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
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
        else glUniform1i(historyValidLocation, 0);
        glActiveTexture(GL_TEXTURE9);
        previousShapes = glGetInteger(GL_TEXTURE_BINDING_3D);
        glBindTexture(GL_TEXTURE_3D, cache.shapes);
        glUniform1i(shapesLocation, 9);
        glActiveTexture(active);
        glUniform1i(advancedLocation, Rayvox.MATERIALS.get() ? 1 : 0);
        glUniform1i(indirectLocation, Rayvox.INDIRECT.get() ? 1 : 0);
        // A table is immutable after resource loading; raw uniforms survive program rebindings.
        if (publishedMaterials != materials) {
            glUniform3fv(transmissionLocation, materials.transmission);
            publishedMaterials = materials;
        }
        glUniform4f(sunLocation, sun.x(), sun.y(), sun.z(), sun.strength());
        glUniform1i(dynamicCountLocation, dynamic.size());
        for (int i = 0; i < dynamic.size(); i++) {
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
        if (!dynamic.isEmpty()) glUniform4fv(dynamicRecordsLocation, moving);
        if (timer != null) timer.begin();
        drawing = true;
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
            distant[offset + 7] = 0;
        }
        sectionUniforms.publish(count, ready, distant);
    }

    @Override
    public void clear() {
        if (timer != null) timer.end();
        if (history != null) history.detach();
        int savedActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE9);
        glBindTexture(GL_TEXTURE_3D, previousShapes);
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
