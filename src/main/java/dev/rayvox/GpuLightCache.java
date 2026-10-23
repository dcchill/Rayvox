package dev.rayvox;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

public final class GpuLightCache implements AutoCloseable {
    public final int data, lists;
    private final int rows;
    private final FloatBuffer lightBuffer = BufferUtils.createFloatBuffer(LightSourceManager.MAX_LIGHTS * 8);
    private final IntBuffer indexBuffer;

    public GpuLightCache(int rows) {
        RenderSystem.assertOnRenderThread();
        this.rows = rows;
        indexBuffer = BufferUtils.createIntBuffer(rows * LightSourceManager.LIST_WIDTH);
        var saved = new GpuVoxelCache.UploadState();
        int lights = 0, indices = 0;
        try {
            lights = allocate(2, LightSourceManager.MAX_LIGHTS, GL_RGBA32F, GL_RGBA, GL_FLOAT);
            indices = allocate(LightSourceManager.LIST_WIDTH, rows, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT);
        } catch (RuntimeException failure) {
            glDeleteTextures(lights); glDeleteTextures(indices);
            throw failure;
        } finally { saved.restore(); }
        data = lights;
        lists = indices;
    }

    private static int allocate(int width, int height, int format, int channels, int type) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, channels, type, 0L);
        if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != width) {
            glDeleteTextures(texture);
            throw new IllegalStateException("Unable to allocate light texture");
        }
        return texture;
    }

    public void upload(LightSourceManager manager) {
        RenderSystem.assertOnRenderThread();
        if (manager.lists.length != rows * LightSourceManager.LIST_WIDTH) throw new IllegalArgumentException("Light list size mismatch");
        var saved = new GpuVoxelCache.UploadState();
        try {
            lightBuffer.clear().put(manager.data).flip();
            glBindTexture(GL_TEXTURE_2D, data);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, LightSourceManager.MAX_LIGHTS, GL_RGBA, GL_FLOAT, lightBuffer);
            indexBuffer.clear().put(manager.lists).flip();
            glBindTexture(GL_TEXTURE_2D, lists);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, LightSourceManager.LIST_WIDTH, rows, GL_RED_INTEGER, GL_UNSIGNED_INT, indexBuffer);
        } finally { saved.restore(); }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        glDeleteTextures(data); glDeleteTextures(lists);
    }
}
