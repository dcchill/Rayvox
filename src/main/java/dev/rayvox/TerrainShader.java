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
    DirectionalLight sun = new DirectionalLight(0, 1, 0, 0, 0);
    MaterialTable materials;
    private int previousShapes;
    private final float[] moving = new float[32];
    private final int farCount, farReady, farRecords;
    private final float[] distant = new float[LightSourceManager.PER_SECTION * 8];
    private final int[] locations = new int[4];
    private final int[] previous = new int[4];

    TerrainShader(ResourceProvider resources) throws IOException {
        super(resources, ResourceLocation.fromNamespaceAndPath("rayvox", "terrain"), DefaultVertexFormat.BLOCK);
        String[] names = {"VoxelData", "VoxelState", "LightData", "LightLists"};
        for (int i = 0; i < 4; i++) locations[i] = glGetUniformLocation(getId(), names[i]);
        farCount = glGetUniformLocation(getId(), "FarLightCount");
        farReady = glGetUniformLocation(getId(), "FarReady");
        farRecords = glGetUniformLocation(getId(), "FarLightRecords[0]");
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
        else glUniform1i(glGetUniformLocation(getId(), "HistoryValid"), 0);
        glActiveTexture(GL_TEXTURE9);
        previousShapes = glGetInteger(GL_TEXTURE_BINDING_3D);
        glBindTexture(GL_TEXTURE_3D, cache.shapes);
        glUniform1i(glGetUniformLocation(getId(), "VoxelShapes"), 9);
        glActiveTexture(active);
        glUniform1i(glGetUniformLocation(getId(), "AdvancedMaterials"), Rayvox.MATERIALS.get() ? 1 : 0);
        glUniform1i(glGetUniformLocation(getId(), "IndirectEnabled"), Rayvox.INDIRECT.get() ? 1 : 0);
        glUniform3fv(glGetUniformLocation(getId(), "MaterialTransmission[0]"), materials.transmission);
        glUniform4f(glGetUniformLocation(getId(), "SunDirection"), sun.x(), sun.y(), sun.z(), sun.strength());
        glUniform1i(glGetUniformLocation(getId(), "DynamicCount"), dynamic.size());
        for (int i = 0; i < dynamic.size(); i++) {
            var source = dynamic.get(i); var def = source.definition(); int offset = i * 8;
            moving[offset] = (float)(source.x() - cameraX);
            moving[offset + 1] = (float)(source.y() - cameraY);
            moving[offset + 2] = (float)(source.z() - cameraZ);
            moving[offset + 3] = def.radius();
            moving[offset + 4] = LightDefinition.linear(def.red()) * def.intensity();
            moving[offset + 5] = LightDefinition.linear(def.green()) * def.intensity();
            moving[offset + 6] = LightDefinition.linear(def.blue()) * def.intensity();
            moving[offset + 7] = def.shadows() ? 1 : 0;
        }
        if (!dynamic.isEmpty()) glUniform4fv(glGetUniformLocation(getId(), "DynamicRecords[0]"), moving);
        if (timer != null) timer.begin();
        drawing = true;
    }

    void section(java.util.List<LightSourceManager.Light> selected, LightSourceManager manager, boolean ready, double x, double y, double z) {
        int count = Math.min(LightSourceManager.PER_SECTION, selected.size());
        for (int i = 0; i < count; i++) {
            var light = selected.get(i); var def = manager.definition(light); int offset = i * 8;
            distant[offset] = (float)(light.x() - x);
            distant[offset + 1] = (float)(light.y() - y);
            distant[offset + 2] = (float)(light.z() - z);
            distant[offset + 3] = def.radius();
            distant[offset + 4] = LightDefinition.linear(def.red()) * def.intensity();
            distant[offset + 5] = LightDefinition.linear(def.green()) * def.intensity();
            distant[offset + 6] = LightDefinition.linear(def.blue()) * def.intensity();
            distant[offset + 7] = 0;
        }
        glUniform1i(farCount, count);
        glUniform1i(farReady, ready ? 1 : 0);
        if (count > 0) glUniform4fv(farRecords, distant);
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
