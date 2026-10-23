package dev.rayvox;

import static org.lwjgl.opengl.GL33C.*;

/** Two-frame GPU lighting history. Surface keys reject disocclusions; revisions reject stale lighting. */
final class LightingHistory implements AutoCloseable {
    private final int[] color = new int[2], key = new int[2], indirect = new int[2];
    private final int framebuffer = glGenFramebuffers();
    private final int drawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS);
    private int width, height, write, target;
    private boolean available, previous, attached;
    private boolean previousSun;
    private int sunRevision;
    private long revision = Long.MIN_VALUE;
    private int oldColor, oldKey, oldActive, oldFramebuffer;
    private int oldIndirect;
    private boolean gi;
    private final boolean[] blend = new boolean[4];
    private final int[] drawMasks = new int[4];
    private final java.nio.ByteBuffer colorMask = org.lwjgl.BufferUtils.createByteBuffer(4);
    private static final float[] ZERO = {0, 0, 0, 0};
    private static final int[] CLEAR = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2};
    private static final int[] CLEAR_DIRECT = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
    private static final int[] DRAW = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2};
    private static final int[] DRAW_GI = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
    String status = "Lighting history warming";

    void begin(int width, int height, long revision) {
        begin(width, height, revision, 0);
    }

    void begin(int width, int height, long revision, int sunRevision) {
        begin(width, height, revision, sunRevision, false);
    }

    void begin(int width, int height, long revision, int sunRevision, boolean gi) {
        available = false;
        int bytes = gi ? 64 : 48;
        if (width <= 0 || height <= 0 || (long)width * height * bytes > 128L * 1024 * 1024) {
            releaseTextures();
            status = "Lighting history bypassed: exceeds 128 MiB budget";
            return;
        }
        int bound = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (bound == 0 || glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT1,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_NONE
                || glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT2,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_NONE
                || (gi && glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT3,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_NONE)) {
            previous = false;
            status = "Lighting history bypassed: framebuffer has other attachments";
            return;
        }
        for (int i = 0; i < drawBuffers; i++) {
            if (glGetInteger(GL_DRAW_BUFFER0 + i) != (i == 0 ? GL_COLOR_ATTACHMENT0 : GL_NONE)) {
                invalidate(); status = "Lighting history bypassed: other draw buffers active"; return;
            }
        }
        target = bound;
        if (this.width != width || this.height != height || this.gi != gi) { this.gi = gi; allocate(width, height); }
        previous = availablePrevious(revision);
        previousSun = previous && this.sunRevision == sunRevision;
        this.sunRevision = sunRevision;
        this.revision = revision;
        write ^= 1;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color[write], 0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, key[write], 0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, gi ? indirect[write] : 0, 0);
        glDrawBuffers(gi ? CLEAR : CLEAR_DIRECT);
        // Clear only history, preserving Minecraft's color, depth, clear color and framebuffer.
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        glDisable(GL_SCISSOR_TEST);
        for (int i = 0; i < (gi ? 3 : 2); i++) {
            int mask = mask(i);
            glColorMaski(i, true, true, true, true);
            glClearBufferfv(GL_COLOR, i, ZERO);
            restoreMask(i, mask);
        }
        if (scissor) glEnable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bound);
        available = true;
        status = "Lighting history: " + (previous ? "reusing unchanged surfaces" : "invalidated / warming")
                + "; " + ((long)width * height * bytes / 1024 / 1024) + " MiB";
    }

    private boolean availablePrevious(long revision) { return this.revision == revision && color[0] != 0; }

    boolean attach(int program) {
        glUniform1i(glGetUniformLocation(program, "HistoryValid"), 0);
        glUniform1i(glGetUniformLocation(program, "SunHistoryValid"), 0);
        if (!available || attached || glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != target) return false;
        oldFramebuffer = target;
        oldActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE7);
        oldColor = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, color[write ^ 1]);
        glActiveTexture(GL_TEXTURE8);
        oldKey = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, key[write ^ 1]);
        if (gi) {
            glActiveTexture(GL_TEXTURE10);
            oldIndirect = glGetInteger(GL_TEXTURE_BINDING_2D);
            glBindTexture(GL_TEXTURE_2D, indirect[write ^ 1]);
            glUniform1i(glGetUniformLocation(program, "HistoryIndirect"), 10);
        }
        glActiveTexture(oldActive);
        glUniform1i(glGetUniformLocation(program, "HistoryColor"), 7);
        glUniform1i(glGetUniformLocation(program, "HistoryKey"), 8);
        glUniform1i(glGetUniformLocation(program, "HistoryValid"), previous ? 1 : 0);
        glUniform1i(glGetUniformLocation(program, "SunHistoryValid"), previousSun ? 1 : 0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, color[write], 0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, key[write], 0);
        if (gi) glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, indirect[write], 0);
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            detach();
            previous = available = false;
            status = "Lighting history bypassed: incompatible framebuffer";
            return false;
        }
        glDrawBuffers(gi ? DRAW_GI : DRAW);
        for (int i = 1; i <= (gi ? 3 : 2); i++) {
            blend[i] = glIsEnabledi(GL_BLEND, i); glDisablei(GL_BLEND, i);
            drawMasks[i] = mask(i); glColorMaski(i, true, true, true, true);
        }
        attached = true;
        return true;
    }

    void detach() {
        // Also used to unwind a failed attachment before attached becomes true.
        if (oldFramebuffer == 0) return;
        int bound = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldFramebuffer);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, 0, 0);
        if (gi) glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, 0, 0);
        if (attached) for (int i = 1; i <= (gi ? 3 : 2); i++) {
            if (blend[i]) glEnablei(GL_BLEND, i); else glDisablei(GL_BLEND, i);
            restoreMask(i, drawMasks[i]);
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bound);
        glActiveTexture(GL_TEXTURE7);
        glBindTexture(GL_TEXTURE_2D, oldColor);
        glActiveTexture(GL_TEXTURE8);
        glBindTexture(GL_TEXTURE_2D, oldKey);
        if (gi) { glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, oldIndirect); }
        glActiveTexture(oldActive);
        attached = false;
        oldFramebuffer = 0;
    }

    void invalidate() { revision = Long.MIN_VALUE; previous = available = false; }

    private int mask(int index) {
        glGetBooleani_v(GL_COLOR_WRITEMASK, index, colorMask);
        int bits = 0;
        for (int i = 0; i < 4; i++) if (colorMask.get(i) != 0) bits |= 1 << i;
        return bits;
    }

    private static void restoreMask(int index, int bits) {
        glColorMaski(index, (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
    }

    private void allocate(int width, int height) {
        releaseTextures();
        this.width = width; this.height = height;
        var saved = new GpuVoxelCache.UploadState();
        try {
            for (int i = 0; i < 2; i++) {
                color[i] = texture(width, height, GL_RGBA16F);
                key[i] = texture(width, height, GL_RGBA32F);
                if (gi) indirect[i] = texture(width, height, GL_RGBA16F);
            }
        } finally { saved.restore(); }
    }

    private static int texture(int width, int height, int format) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, GL_RGBA, GL_FLOAT, 0L);
        if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != width) {
            glDeleteTextures(id);
            throw new IllegalStateException("Unable to allocate lighting history texture");
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return id;
    }

    private void releaseTextures() {
        detach();
        int bound = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        for (int i = 0; i < 3; i++) glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bound);
        for (int i = 0; i < 2; i++) {
            glDeleteTextures(color[i]); glDeleteTextures(key[i]);
            glDeleteTextures(indirect[i]);
            color[i] = key[i] = indirect[i] = 0;
        }
        width = height = 0;
        invalidate();
    }

    @Override public void close() { releaseTextures(); glDeleteFramebuffers(framebuffer); }
}
