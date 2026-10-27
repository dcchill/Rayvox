package dev.rayvox;

import java.io.IOException;
import net.minecraft.server.packs.resources.ResourceProvider;

/** Owns the independent lighting stages; GameRenderer reloads/closes this resource as one shader. */
final class LightingRenderPipeline extends LightingPass {
    LightingPass direct, indirect, dynamic;
    LightingPass probes;
    private boolean closed;

    LightingRenderPipeline(ResourceProvider resources) throws IOException {
        super(resources, Kind.COMPOSITION);
        try {
            direct = new LightingPass(resources, Kind.STATIC);
            indirect = new LightingPass(resources, Kind.INDIRECT);
            dynamic = new LightingPass(resources, Kind.DYNAMIC);
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
        try { probes = new LightingPass(resources, Kind.PROBE); }
        catch (IOException | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Rayvox world probes unavailable; using surface GI gathers", failure);
        }
    }

    void renderFrame(LightingHistory history, Frame frame, GpuTimer directTimer, GpuTimer indirectTimer) {
        direct.render(history, frame, directTimer);
        if (frame.indirect()) indirect.render(history, frame, indirectTimer);
        if (!frame.dynamic().isEmpty()) dynamic.render(history, frame, null);
        render(history, frame, null);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (direct != null) direct.close();
        if (indirect != null) indirect.close();
        if (dynamic != null) dynamic.close();
        if (probes != null) probes.close();
        super.close();
    }
}
