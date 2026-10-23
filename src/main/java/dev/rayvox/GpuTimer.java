package dev.rayvox;

import static org.lwjgl.opengl.GL33C.*;

/** Timestamp pairs, read only after availability; never waits for the GPU. */
final class GpuTimer implements AutoCloseable {
    private final int[] queries = new int[16];
    private final boolean[] pending = new boolean[8];
    private int next, active = -1;
    private double millis;
    private int samples;

    GpuTimer() { glGenQueries(queries); }

    void begin() {
        if (active != -1) return;
        for (int i = 0; i < pending.length; i++) {
            if (pending[i] && glGetQueryObjecti(queries[i * 2 + 1], GL_QUERY_RESULT_AVAILABLE) != 0) {
                long start = glGetQueryObjectui64(queries[i * 2], GL_QUERY_RESULT);
                long end = glGetQueryObjectui64(queries[i * 2 + 1], GL_QUERY_RESULT);
                millis = (end - start) / 1_000_000.0;
                samples++;
                pending[i] = false;
            }
        }
        if (pending[next]) return;
        active = next;
        next = (next + 1) % pending.length;
        glQueryCounter(queries[active * 2], GL_TIMESTAMP);
    }

    void end() {
        if (active == -1) return;
        glQueryCounter(queries[active * 2 + 1], GL_TIMESTAMP);
        pending[active] = true;
        active = -1;
    }

    String status() { return samples == 0 ? "GPU timing: warming (asynchronous)"
            : String.format(java.util.Locale.ROOT, "GPU: last terrain layer %.3f ms (%d samples; includes draw + lighting)", millis, samples); }

    @Override public void close() { end(); glDeleteQueries(queries); }
}
