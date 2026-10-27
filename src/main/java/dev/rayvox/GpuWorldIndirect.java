package dev.rayvox;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import static dev.rayvox.WorldLightingCache.Channel.INDIRECT;
import static org.lwjgl.opengl.GL33C.*;

/** Six directional irradiances per four-block probe. All publication and sampling stays on the GPU. */
final class GpuWorldIndirect implements AutoCloseable {
    static final int PROBES_PER_BRICK = 64, DIRECTIONS = 6, ATLAS_WIDTH = PROBES_PER_BRICK * DIRECTIONS;
    private static final long UPDATE_BUDGET_NANOS = 500_000;
    final int irradiance, validity;
    private final int width, rows, framebuffer;
    private final WorldLightingState state;
    private final ByteBuffer metadata;
    private final GpuTimer timer = new GpuTimer();
    private int measuredSamples;
    private Settings settings;
    private long draws;
    private boolean changed;
    private record Settings(boolean shadows, float distance, float sunDistance, boolean advanced, MaterialTable materials) {}

    GpuWorldIndirect(WorldLightingState state, int width) {
        RenderSystem.assertOnRenderThread();
        this.state = state;
        this.width = width;
        rows = width * width * width;
        metadata = BufferUtils.createByteBuffer(rows);
        var saved = new GpuVoxelCache.UploadState();
        int data = 0, flags = 0, target = 0;
        int previous = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            data = allocate(ATLAS_WIDTH, rows, GL_RGBA16F, GL_RGBA, GL_FLOAT);
            flags = allocate(1, rows, GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, rows, GL_RED_INTEGER, GL_UNSIGNED_BYTE, metadata);
            target = glGenFramebuffers();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, data, 0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete world-probe framebuffer");
        } catch (RuntimeException failure) {
            glDeleteTextures(data); glDeleteTextures(flags); glDeleteFramebuffers(target); timer.close();
            throw failure;
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previous);
            saved.restore();
        }
        irradiance = data;
        validity = flags;
        framebuffer = target;
        // A new atlas cannot inherit publication from a previous GL resource.
        state.cache.invalidateAll(INDIRECT);
    }

    private static int allocate(int width, int height, int internal, int format, int type) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, (ByteBuffer)null);
        if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != width) {
            glDeleteTextures(texture);
            throw new IllegalStateException("Unable to allocate world-probe texture");
        }
        return texture;
    }

    void update(LightingPass producer, LightingPass.Frame frame) {
        RenderSystem.assertOnRenderThread();
        changed = false;
        if (frame.grid().width != width) throw new IllegalArgumentException("World-probe dimensions differ");
        var next = new Settings(frame.shadows(), frame.shadowDistance(), frame.sunShadowDistance(),
                Rayvox.MATERIALS.get(), frame.materials());
        if (!next.equals(settings)) { state.cache.invalidateAll(INDIRECT); settings = next; }
        timer.poll();
        if (timer.samples() != measuredSamples) {
            state.scheduler.recordGpuTime(1, Math.max(1, (long)(timer.millis() * 1_000_000)));
            measuredSamples = timer.samples();
        }
        var work = state.scheduler.schedule(frame.cameraX(), frame.cameraY(), frame.cameraZ(), 4,
                UPDATE_BUDGET_NANOS, region -> resident(frame.grid(), region), region -> 0,
                ticket -> ticket.channel() == INDIRECT && resident(frame.grid(), ticket.region()));
        for (var item : work) {
            if (!resident(frame.grid(), item.region())) { state.scheduler.retry(item); continue; }
            try {
                producer.renderProbes(this, item.region(), frame, timer);
                // GL command ordering guarantees the complete row is written before the following sampling pass.
                // CPU timing and queue removal are never treated as completion of unsubmitted work.
                for (var ticket : item.tickets()) state.scheduler.completeGpu(ticket);
                draws++;
                changed = true;
            } catch (RuntimeException failure) {
                state.scheduler.retry(item);
                throw failure;
            }
        }
        uploadValidity(frame.grid());
    }

    static boolean resident(VoxelGrid grid, WorldLightingCache.Region region) {
        var slot = grid.get(region.x(), region.y(), region.z());
        return slot != null && slot.uploaded;
    }

    private void uploadValidity(VoxelGrid grid) {
        boolean changed = false;
        for (int i = 0; i < rows; i++) {
            var slot = grid.slots[i];
            byte value = (byte)(slot.uploaded && state.cache.valid(
                    new WorldLightingCache.Region(slot.x, slot.y, slot.z), INDIRECT) ? 1 : 0);
            if (metadata.get(i) != value) { metadata.put(i, value); changed = true; }
        }
        if (!changed) return;
        this.changed = true;
        var saved = new GpuVoxelCache.UploadState();
        try {
            glBindTexture(GL_TEXTURE_2D, validity);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, rows, GL_RED_INTEGER, GL_UNSIGNED_BYTE, metadata);
        } finally { saved.restore(); }
    }

    void bind(WorldLightingCache.Region region) {
        int row = Math.floorMod(region.x(), width)
                + width * (Math.floorMod(region.y(), width) + width * Math.floorMod(region.z(), width));
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glViewport(0, row, ATLAS_WIDTH, 1);
    }

    String status() {
        return String.format(java.util.Locale.ROOT, "GPU indirect probes: %d draws, %.3f ms/brick; %.2f MiB",
                draws, timer.millis(), (double)rows * (ATLAS_WIDTH * 8 + 1) / (1024 * 1024));
    }

    long draws() { return draws; }
    boolean changed() { return changed; }
    void idleFrame() { changed = false; }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        state.cache.invalidateAll(INDIRECT);
        timer.close();
        glDeleteFramebuffers(framebuffer);
        glDeleteTextures(irradiance);
        glDeleteTextures(validity);
    }
}
