package dev.rayvox;

/** Renderer selection. LEGACY is the original inline terrain-shader tracer, retained as the reference path. */
public enum LightingPipeline {
    /** Static shadow rays are traced in the terrain fragment shader, reusing exact-revision history. */
    LEGACY,
    /**
     * Terrain fragments sample reprojected lighting; one full-screen pass after opaque terrain traces
     * new and stale visible pixels once each (no overdraw) for use by the next frame. Indirect gathers
     * run in their own pass and history; the terrain shader only composes those cached samples.
     */
    DEFERRED
}
