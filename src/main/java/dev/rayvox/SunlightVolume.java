package dev.rayvox;

import java.io.IOException;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;

/** Bounded, quarter-resolution single scattering, independent of terrain history and pipeline selection. */
final class SunlightVolume extends ShaderInstance {
    private ShaderInstance composite;
    private int texture, framebuffer, vao, width, height;
    private final Matrix4f inverseClip = new Matrix4f();
    private final float[] matrix = new float[16];
    private final int[] viewport = new int[4], savedTextures = new int[6];
    private final int[] textures = new int[5];
    private final java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(4);
    private final int[] volumeSamplers = new int[5];
    private final int occupancyLocation;
    private final int inverseClipLocation, volumeSizeLocation, volumeStepsLocation, rainLocation,
            sunShadowDistanceLocation, sunDirectionLocation, sunRadianceLocation, cacheWidthLocation,
            cacheMinSlotLocation, cameraInCacheLocation, cameraFractionLocation, advancedMaterialsLocation,
            materialTransmissionLocation;
    private final int compositeDepthLocation, compositeLightLocation, compositeInverseClipLocation,
            compositeShadowDistanceLocation;
    private GpuTimer timer;
    private boolean closed;
    long draws;

    SunlightVolume(ResourceProvider resources) throws IOException {
        super(resources, ResourceLocation.fromNamespaceAndPath("rayvox", "sun_volume"), DefaultVertexFormat.POSITION);
        try {
            composite = new ShaderInstance(resources, ResourceLocation.fromNamespaceAndPath("rayvox", "sun_volume_composite"), DefaultVertexFormat.POSITION);
            for (int program : new int[]{getId(), composite.getId()})
                if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE)
                    throw new IllegalStateException(glGetProgramInfoLog(program));
            String[] samplers = {"PassDepth", "VoxelData", "VoxelState", "VoxelShapes", "VolumeLight"};
            for (int i = 0; i < samplers.length; i++) volumeSamplers[i] = glGetUniformLocation(getId(), samplers[i]);
            occupancyLocation = glGetUniformLocation(getId(), "VoxelOccupancy");
            inverseClipLocation = glGetUniformLocation(getId(), "InverseClipMat");
            volumeSizeLocation = glGetUniformLocation(getId(), "VolumeSize");
            volumeStepsLocation = glGetUniformLocation(getId(), "VolumeSteps");
            rainLocation = glGetUniformLocation(getId(), "Rain");
            sunShadowDistanceLocation = glGetUniformLocation(getId(), "SunShadowDistance");
            sunDirectionLocation = glGetUniformLocation(getId(), "SunDirection");
            sunRadianceLocation = glGetUniformLocation(getId(), "SunRadiance");
            cacheWidthLocation = glGetUniformLocation(getId(), "CacheWidth");
            cacheMinSlotLocation = glGetUniformLocation(getId(), "CacheMinSlot");
            cameraInCacheLocation = glGetUniformLocation(getId(), "CameraInCache");
            cameraFractionLocation = glGetUniformLocation(getId(), "CameraFraction");
            advancedMaterialsLocation = glGetUniformLocation(getId(), "AdvancedMaterials");
            materialTransmissionLocation = glGetUniformLocation(getId(), "MaterialTransmission[0]");
            compositeDepthLocation = glGetUniformLocation(composite.getId(), "PassDepth");
            compositeLightLocation = glGetUniformLocation(composite.getId(), "VolumeLight");
            compositeInverseClipLocation = glGetUniformLocation(composite.getId(), "InverseClipMat");
            compositeShadowDistanceLocation = glGetUniformLocation(composite.getId(), "SunShadowDistance");
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    void render(RenderTarget target, LightingPass.Frame f, Matrix4fc view, Matrix4fc projection, float rain, int steps) {
        RenderSystem.assertOnRenderThread();
        int oldFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (oldFramebuffer != target.frameBufferId || oldFramebuffer == 0 || glGetInteger(GL_SAMPLES) != 0
                || glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE
                || glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING) != GL_LINEAR) return;
        int depthTexture = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int oldRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldActive = glGetInteger(GL_ACTIVE_TEXTURE), oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        int equationRgb = glGetInteger(GL_BLEND_EQUATION_RGB), equationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabledi(GL_BLEND, 0), cull = glIsEnabled(GL_CULL_FACE);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST), depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetBooleanv(GL_COLOR_WRITEMASK, mask);
        for (int i = 0; i < savedTextures.length; i++) {
            glActiveTexture(GL_TEXTURE3 + i);
            savedTextures[i] = glGetInteger(i > 0 && i < 4 || i == 5 ? GL_TEXTURE_BINDING_3D : GL_TEXTURE_BINDING_2D);
        }
        boolean timing = RayvoxEngine.profiling() || Rayvox.GOVERNOR_TARGET.get() > 0;
        try {
            glActiveTexture(GL_TEXTURE7);
            resize(target.width, target.height);
            if (timing && timer == null && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) timer = new GpuTimer();
            if (!timing && timer != null) { timer.close(); timer = null; }
            if (timer != null) timer.begin();
            inverseClip.set(projection).mul(view).invert().get(matrix);
            RenderSystem.disableDepthTest(); RenderSystem.depthMask(false); RenderSystem.disableCull();
            GlStateManager._disableScissorTest();
            glDisablei(GL_BLEND, 0); glColorMaski(0, true, true, true, true);
            if (vao == 0) vao = glGenVertexArrays();
            glBindVertexArray(vao);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, width, height);
            apply();
            textures[0] = depthTexture; textures[1] = f.cache().voxels; textures[2] = f.cache().states;
            textures[3] = f.cache().shapes; textures[4] = texture;
            for (int i = 0; i < textures.length; i++) {
                glActiveTexture(GL_TEXTURE3 + i);
                glBindTexture(i > 0 && i < 4 ? GL_TEXTURE_3D : GL_TEXTURE_2D, textures[i]);
                glUniform1i(volumeSamplers[i], 3 + i);
            }
            glActiveTexture(GL_TEXTURE8);
            glBindTexture(GL_TEXTURE_3D, f.cache().occupancy);
            glUniform1i(occupancyLocation, 8);
            glUniformMatrix4fv(inverseClipLocation, false, matrix);
            glUniform2f(volumeSizeLocation, width, height);
            glUniform1i(volumeStepsLocation, steps);
            glUniform1f(rainLocation, rain);
            glUniform1f(sunShadowDistanceLocation, f.sunShadowDistance());
            var sun = f.sun();
            glUniform4f(sunDirectionLocation, sun.x(), sun.y(), sun.z(), sun.strength());
            glUniform3f(sunRadianceLocation, sun.red(), sun.green(), sun.blue());
            var grid = f.grid();
            int minX = grid.centerX - grid.radius, minY = grid.centerY - grid.radius, minZ = grid.centerZ - grid.radius;
            int bx = (int)Math.floor(f.cameraX()), by = (int)Math.floor(f.cameraY()), bz = (int)Math.floor(f.cameraZ());
            glUniform1i(cacheWidthLocation, grid.width);
            glUniform3i(cacheMinSlotLocation, Math.floorMod(minX, grid.width), Math.floorMod(minY, grid.width), Math.floorMod(minZ, grid.width));
            glUniform3i(cameraInCacheLocation, bx - minX * 16, by - minY * 16, bz - minZ * 16);
            glUniform3f(cameraFractionLocation, (float)(f.cameraX() - bx), (float)(f.cameraY() - by), (float)(f.cameraZ() - bz));
            glUniform1i(advancedMaterialsLocation, Rayvox.MATERIALS.get() ? 1 : 0);
            glUniform3fv(materialTransmissionLocation, f.materials().transmission);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            clear();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldFramebuffer);
            glViewport(0, 0, target.width, target.height);
            composite.apply();
            glUniform1i(compositeDepthLocation, 3);
            glUniform1i(compositeLightLocation, 7);
            glUniformMatrix4fv(compositeInverseClipLocation, false, matrix);
            glUniform1f(compositeShadowDistanceLocation, f.sunShadowDistance());
            glEnablei(GL_BLEND, 0);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            draws++;
        } finally {
            if (timer != null) timer.end();
            if (composite != null) composite.clear();
            clear();
            GlStateManager._glUseProgram(oldProgram);
            for (int i = 0; i < savedTextures.length; i++) {
                glActiveTexture(GL_TEXTURE3 + i);
                glBindTexture(i > 0 && i < 4 || i == 5 ? GL_TEXTURE_3D : GL_TEXTURE_2D, savedTextures[i]);
            }
            glActiveTexture(oldActive); glBindVertexArray(oldVao);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldFramebuffer); glBindFramebuffer(GL_READ_FRAMEBUFFER, oldRead);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (depth) RenderSystem.enableDepthTest();
            RenderSystem.depthMask(depthMask);
            if (cull) RenderSystem.enableCull();
            if (scissor) GlStateManager._enableScissorTest();
            if (blend) glEnablei(GL_BLEND, 0); else glDisablei(GL_BLEND, 0);
            glBlendEquationSeparate(equationRgb, equationAlpha);
            glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            glColorMaski(0, mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
        }
    }

    private void resize(int fullWidth, int fullHeight) {
        int w = (fullWidth + 3) / 4, h = (fullHeight + 3) / 4;
        if (w == width && h == height && texture != 0) return;
        releaseTargets();
        if (w <= 0 || h <= 0 || (long)w * h * 8 > 8L * 1024 * 1024)
            throw new IllegalStateException("Sunlight volume exceeds its 8 MiB budget");
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer)null);
        framebuffer = glGenFramebuffers(); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete sunlight volume framebuffer");
        width = w; height = h;
    }

    double millis() { return timer == null || timer.samples() == 0 ? 0 : timer.millis(); }

    void releaseTargets() {
        if (texture != 0) glDeleteTextures(texture);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (timer != null) { timer.close(); timer = null; }
        texture = framebuffer = width = height = 0;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        releaseTargets();
        if (vao != 0) glDeleteVertexArrays(vao);
        vao = 0;
        if (composite != null) composite.close();
        super.close();
    }
}
