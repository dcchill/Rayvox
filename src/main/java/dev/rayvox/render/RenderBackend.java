package dev.rayvox.render;

/** Implementations own their GPU objects and are called only on the render thread. */
public interface RenderBackend extends AutoCloseable {
    void initialize();
    void update(Scene.Update update);
    void resize(int width, int height);
    void render(Scene.Camera camera);
    Diagnostics diagnostics();
    @Override void close();

    record Diagnostics(String name, String device, String reason, int width, int height,
                       double gpuMillis, long allocatedBytes, int meshes, int pending) {
        public String summary() {
            return name + " / " + device + "; " + width + "x" + height + "; "
                    + String.format(java.util.Locale.ROOT, "%.2f ms; %.1f MiB; ", gpuMillis, allocatedBytes / 1048576.0)
                    + meshes + " meshes; " + pending + " pending" + (reason.isEmpty() ? "" : "; " + reason);
        }
    }
}
