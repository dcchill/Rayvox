package dev.rayvox;

import static org.lwjgl.opengl.GL33C.*;

/** Two-frame GPU lighting history. Surface keys reject disocclusions; revisions reject stale lighting. */
final class LightingHistory implements AutoCloseable {
    private final int[] color = new int[2], key = new int[2], indirect = new int[2];
    private final int framebuffer = glGenFramebuffers();
    private final int drawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS);
    private int width, height, write, target;
    private boolean available, previous, attached, written;
    private boolean previousSun;
    private int sunRevision;
    private long revision = Long.MIN_VALUE;
    private int oldColor, oldKey, oldActive, oldFramebuffer;
    private int oldIndirect;
    private int cachedProgram, validLocation, sunValidLocation, indirectLocation, colorLocation, keyLocation;
    private int deferredLocation, staleLocation, availableLocation;
    private boolean gi;
    /** Deferred mode: history is geometry-valid across revisions; a revision change only marks stored lighting stale. */
    private boolean deferred, stale;
    private int inputs, base, emission, albedo, dynamic, composed, sceneColor, sceneDepth;
    private int passFramebuffer;
    private boolean cameraKnown, cameraMoving;
    private double cameraX, cameraY, cameraZ;
    private final org.joml.Matrix4f currentClip = new org.joml.Matrix4f();
    private final org.joml.Matrix4f previousClip = new org.joml.Matrix4f();
    private final float[] clipValues = new float[16];
    private float cameraOffsetX, cameraOffsetY, cameraOffsetZ;
    private int clipLocation, cameraOffsetLocation;
    private final boolean[] blend = new boolean[8];
    private final int[] drawMasks = new int[8];
    private final java.nio.ByteBuffer colorMask = org.lwjgl.BufferUtils.createByteBuffer(4);
    private static final float[] ZERO = {0, 0, 0, 0};
    private static final int[] DRAW = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2};
    private static final int[] DRAW_GI = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
    private static final int[] DRAW_DEFERRED = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_NONE, GL_COLOR_ATTACHMENT4, GL_COLOR_ATTACHMENT5, GL_COLOR_ATTACHMENT6, GL_COLOR_ATTACHMENT7};
    private static final int[] DRAW_DEFERRED_GI = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3, GL_COLOR_ATTACHMENT4, GL_COLOR_ATTACHMENT5, GL_COLOR_ATTACHMENT6, GL_COLOR_ATTACHMENT7};
    String status = "Lighting history warming";

    void camera(double x, double y, double z, boolean directional,
            org.joml.Matrix4fc modelView, org.joml.Matrix4fc projection) {
        previousClip.set(currentClip);
        currentClip.set(projection).mul(modelView);
        cameraOffsetX = (float)(cameraX - x);
        cameraOffsetY = (float)(cameraY - y);
        cameraOffsetZ = (float)(cameraZ - z);
        boolean moving = directional && cameraKnown && (x != cameraX || y != cameraY || z != cameraZ);
        // Sun visibility includes a camera-centered caster fade, so refresh it during translation.
        cameraMoving = moving;
        cameraKnown = true;
        cameraX = x; cameraY = y; cameraZ = z;
    }

    void begin(int width, int height, long revision) {
        begin(width, height, revision, 0);
    }

    void begin(int width, int height, long revision, int sunRevision) {
        begin(width, height, revision, sunRevision, false);
    }

    void begin(int width, int height, long revision, int sunRevision, boolean gi) {
        begin(width, height, revision, sunRevision, gi, false);
    }

    static int bytesPerPixel(boolean gi, boolean deferred) { return (gi ? 64 : 48) + (deferred ? 44 : 0); }

    static boolean fitsBudget(int width, int height, boolean gi, boolean deferred) {
        return fitsBudget(width, height, gi, deferred, Rayvox.HISTORY_MEMORY.get() * 1024L * 1024);
    }

    static boolean fitsBudget(int width, int height, boolean gi, boolean deferred, long budgetBytes) {
        return width > 0 && height > 0 && budgetBytes > 0
                && (long)width * height <= budgetBytes / bytesPerPixel(gi, deferred);
    }

    void begin(int width, int height, long revision, int sunRevision, boolean gi, boolean deferred) {
        begin(width, height, revision, sunRevision, gi, deferred, Rayvox.HISTORY_MEMORY.get() * 1024L * 1024);
    }

    void begin(int width, int height, long revision, int sunRevision, boolean gi, boolean deferred, long budgetBytes) {
        available = false;
        if (attached) detach();
        boolean requestedDeferred = deferred;
        int bound = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        // Select the feasible layout before allocation; a deferred bypass must retain usable legacy history.
        if (deferred) {
            deferred = bound != 0 && drawBuffers >= 8 && glGetInteger(GL_SAMPLES) == 0 && fitsBudget(width, height, gi, true, budgetBytes);
            for (int i = 4; deferred && i < 8; i++) deferred = glGetFramebufferAttachmentParameteri(
                    GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL_NONE;
            for (int attachment : new int[]{GL_COLOR_ATTACHMENT0, GL_DEPTH_ATTACHMENT}) {
                if (!deferred) break;
                deferred = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment,
                        GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL_TEXTURE;
            }
            if (deferred) {
                sceneColor = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                sceneDepth = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                deferred = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING) == GL_LINEAR;
            }
        }
        int bytes = bytesPerPixel(gi, deferred);
        if (!fitsBudget(width, height, gi, deferred, budgetBytes)) {
            releaseTextures();
            long required = ((long)width * height * bytes + 1024 * 1024 - 1) / (1024 * 1024);
            status = "Lighting history bypassed: " + width + "x" + height + " needs " + required
                    + " MiB; budget " + budgetBytes / (1024 * 1024) + " MiB (raise History memory in Performance options)";
            return;
        }
        if (bound == 0 || glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT1,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_NONE
                || glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT2,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_NONE
                || (gi && glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT3,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_NONE)) {
            invalidate();
            status = "Lighting history bypassed: framebuffer has other attachments";
            return;
        }
        for (int i = 0; i < drawBuffers; i++) {
            if (glGetInteger(GL_DRAW_BUFFER0 + i) != (i == 0 ? GL_COLOR_ATTACHMENT0 : GL_NONE)) {
                invalidate(); status = "Lighting history bypassed: other draw buffers active"; return;
            }
        }
        target = bound;
        if (this.width != width || this.height != height || this.gi != gi || this.deferred != deferred) {
            this.gi = gi; this.deferred = deferred; allocate(width, height);
        }
        // Deferred history survives scene revisions: surface keys still reject disocclusion, and the
        // revision change marks every reused pixel stale so the lighting pass re-traces it.
        previous = deferred ? written && color[0] != 0 : availablePrevious(revision);
        stale = deferred && previous && this.revision != revision;
        written = false;
        previousSun = previous && this.sunRevision == sunRevision && !cameraMoving;
        this.sunRevision = sunRevision;
        this.revision = revision;
        write ^= 1;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, key[write], 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        // Reprojection uses the immediately previous camera; unwritten pixels must never retain older keys.
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        glDisable(GL_SCISSOR_TEST);
        int mask = mask(0);
        glColorMaski(0, true, true, true, true);
        glClearBufferfv(GL_COLOR, 0, ZERO);
        restoreMask(0, mask);
        if (scissor) glEnable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bound);
        available = true;
        status = (requestedDeferred && !deferred ? "Deferred unavailable; " : "") + (deferred ? "Deferred lighting: " + (!previous ? "warming (all pixels traced)" : stale
                ? "scene changed, refreshing stale pixels" : "reusing; tracing new/stale pixels") : "Lighting history: "
                + (previous ? "reusing unchanged surfaces" : "invalidated / warming"))
                + "; " + ((long)width * height * bytes / 1024 / 1024) + " MiB";
    }

    private int lastAttachment() { return deferred ? 7 : gi ? 3 : 2; }

    private boolean availablePrevious(long revision) { return written && this.revision == revision && color[0] != 0; }

    boolean attach(int program) {
        if (cachedProgram != program) {
            cachedProgram = program;
            validLocation = glGetUniformLocation(program, "HistoryValid");
            sunValidLocation = glGetUniformLocation(program, "SunHistoryValid");
            indirectLocation = glGetUniformLocation(program, "HistoryIndirect");
            colorLocation = glGetUniformLocation(program, "HistoryColor");
            keyLocation = glGetUniformLocation(program, "HistoryKey");
            clipLocation = glGetUniformLocation(program, "HistoryClipMat");
            cameraOffsetLocation = glGetUniformLocation(program, "HistoryCameraOffset");
            deferredLocation = glGetUniformLocation(program, "DeferredLighting");
            staleLocation = glGetUniformLocation(program, "HistoryStale");
            availableLocation = glGetUniformLocation(program, "HistoryAvailable");
        }
        glUniform1i(validLocation, 0);
        glUniform1i(sunValidLocation, 0);
        glUniform1i(deferredLocation, 0);
        glUniform1i(staleLocation, 0);
        glUniform1i(availableLocation, 0);
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
            glUniform1i(indirectLocation, 10);
        }
        glActiveTexture(oldActive);
        glUniform1i(colorLocation, 7);
        glUniform1i(keyLocation, 8);
        glUniform1i(validLocation, previous ? 1 : 0);
        glUniform1i(sunValidLocation, previousSun ? 1 : 0);
        glUniformMatrix4fv(clipLocation, false, previousClip.get(clipValues));
        glUniform3f(cameraOffsetLocation, cameraOffsetX, cameraOffsetY, cameraOffsetZ);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, color[write], 0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, key[write], 0);
        if (gi) glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, indirect[write], 0);
        if (deferred) {
            int[] surfaces = {inputs, base, emission, albedo};
            for (int i = 0; i < surfaces.length; i++) glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT4 + i, GL_TEXTURE_2D, surfaces[i], 0);
        }
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            detach();
            previous = available = false;
            status = "Lighting history bypassed: incompatible framebuffer";
            return false;
        }
        glDrawBuffers(deferred ? (gi ? DRAW_DEFERRED_GI : DRAW_DEFERRED) : gi ? DRAW_GI : DRAW);
        for (int i = 1; i <= lastAttachment(); i++) {
            if (i == 3 && !gi) continue;
            blend[i] = glIsEnabledi(GL_BLEND, i); glDisablei(GL_BLEND, i);
            drawMasks[i] = mask(i); glColorMaski(i, true, true, true, true);
        }
        glUniform1i(deferredLocation, deferred ? 1 : 0);
        glUniform1i(staleLocation, stale ? 1 : 0);
        glUniform1i(availableLocation, 1);
        attached = true;
        written = true;
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
        if (deferred) for (int i = 4; i < 8; i++) glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, 0, 0);
        if (attached) for (int i = 1; i <= lastAttachment(); i++) {
            if (i == 3 && !gi) continue;
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

    void invalidate() {
        revision = Long.MIN_VALUE;
        previous = available = written = false;
    }

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
            // Single-frame pass inputs: rgb = ray-free far/section terms, a = state/match code (exact in half floats).
            if (deferred) {
                inputs = texture(width, height, GL_RGBA16F);
                base = texture(width, height, GL_RGBA8);
                emission = texture(width, height, GL_RGBA8);
                albedo = texture(width, height, GL_RGBA32F);
                dynamic = texture(width, height, GL_RGBA16F);
                composed = texture(width, height, GL_RGBA8);
            }
        } finally { saved.restore(); }
    }

    /** True after terrain wrote this frame's deferred surfaces and before the next begin. */
    boolean passReady() { return deferred && available && written && !attached && color[write] != 0; }
    boolean deferredAvailable() { return deferred && available; }
    int passKey() { return key[write]; }
    int passInputs() { return inputs; }
    int passIndirect() { return indirect[write]; }
    int passLighting() { return color[write]; }
    int passBase() { return base; }
    int passEmission() { return emission; }
    int passAlbedo() { return albedo; }
    int passDynamic() { return dynamic; }
    int sceneColor() { return sceneColor; }
    int sceneDepth() { return sceneDepth; }
    void uploadInverseClip(int location) {
        if (location >= 0) glUniformMatrix4fv(location, false, new org.joml.Matrix4f(currentClip).invert().get(clipValues));
    }
    int width() { return width; }
    int height() { return height; }

    /** Binds this frame's lighting target for the pass; the caller restores the previous draw framebuffer. */
    boolean bindPassTarget() {
        return bindPassTarget(color[write]);
    }

    boolean bindIndirectPassTarget() {
        return gi && passReady() && bindPassTarget(indirect[write ^ 1]);
    }

    boolean bindDynamicPassTarget() { return passReady() && bindPassTarget(dynamic); }
    boolean bindCompositionTarget() { return passReady() && bindPassTarget(composed); }

    void finishComposition() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, passFramebuffer);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    void finishIndirectPass() {
        // The pass reads the terrain-reprojected GI and overwrites the no-longer-used previous texture.
        int previous = indirect[write];
        indirect[write] = indirect[write ^ 1];
        indirect[write ^ 1] = previous;
    }

    private boolean bindPassTarget(int texture) {
        if (passFramebuffer == 0) passFramebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, passFramebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        return glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
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
        // Dimensions are set before allocation and reset after release; repeated budget bypasses own no textures.
        if (width == 0 && height == 0) {
            invalidate();
            return;
        }
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
        if (passFramebuffer != 0) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, passFramebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bound);
        }
        for (int texture : new int[]{inputs, base, emission, albedo, dynamic, composed}) glDeleteTextures(texture);
        inputs = base = emission = albedo = dynamic = composed = 0;
        width = height = 0;
        invalidate();
    }

    @Override public void close() {
        releaseTextures();
        glDeleteFramebuffers(framebuffer);
        if (passFramebuffer != 0) glDeleteFramebuffers(passFramebuffer);
        passFramebuffer = 0;
    }
}
