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
    private final float[] previousData = new float[LightSourceManager.MAX_LIGHTS * 8];
    private final int[] previousLists;
    private final int[] runStarts = new int[8], runEnds = new int[8];
    private boolean initialized;
    public int uploadedListRows, uploadedBytes;

    public GpuLightCache(int rows) {
        RenderSystem.assertOnRenderThread();
        this.rows = rows;
        indexBuffer = BufferUtils.createIntBuffer(rows * LightSourceManager.LIST_WIDTH);
        previousLists = new int[rows * LightSourceManager.LIST_WIDTH];
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
        uploadedListRows = uploadedBytes = 0;
        boolean dataChanged = !initialized || !java.util.Arrays.equals(previousData, manager.data);
        int runs = 0;
        int row = 0;
        while (row < rows) {
            if (!rowChanged(manager.lists, row)) { row++; continue; }
            int start = row++;
            while (row < rows && rowChanged(manager.lists, row)) row++;
            if (runs == runStarts.length) { runs = 1; runStarts[0] = 0; runEnds[0] = rows; break; }
            runStarts[runs] = start;
            runEnds[runs++] = row;
        }
        if (!dataChanged && runs == 0) return;
        var saved = new GpuVoxelCache.UploadState();
        try {
            if (dataChanged) {
                lightBuffer.clear().put(manager.data).flip();
                glBindTexture(GL_TEXTURE_2D, data);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, LightSourceManager.MAX_LIGHTS, GL_RGBA, GL_FLOAT, lightBuffer);
                System.arraycopy(manager.data, 0, previousData, 0, previousData.length);
                uploadedBytes += previousData.length * Float.BYTES;
            }
            if (runs > 0) glBindTexture(GL_TEXTURE_2D, lists);
            for (int run = 0; run < runs; run++) {
                int start = runStarts[run] * LightSourceManager.LIST_WIDTH;
                int length = (runEnds[run] - runStarts[run]) * LightSourceManager.LIST_WIDTH;
                indexBuffer.clear().put(manager.lists, start, length).flip();
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, runStarts[run], LightSourceManager.LIST_WIDTH,
                        runEnds[run] - runStarts[run], GL_RED_INTEGER, GL_UNSIGNED_INT, indexBuffer);
                System.arraycopy(manager.lists, start, previousLists, start, length);
                uploadedListRows += runEnds[run] - runStarts[run];
                uploadedBytes += length * Integer.BYTES;
            }
            initialized = true;
        } finally { saved.restore(); }
    }

    private boolean rowChanged(int[] current, int row) {
        int start = row * LightSourceManager.LIST_WIDTH, end = start + LightSourceManager.LIST_WIDTH;
        return !initialized || !java.util.Arrays.equals(previousLists, start, end, current, start, end);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        glDeleteTextures(data); glDeleteTextures(lists);
    }
}
