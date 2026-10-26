package dev.rayvox;

import java.util.Locale;

/**
 * Chooses a lighting quality level from smoothed frame times and delayed Rayvox GPU timestamps.
 * It performs no GL calls and never waits for query results. Levels only cap user settings.
 */
final class PerformanceGovernor {
    static final int LEVELS = 5;
    static final long SETTLE_NANOS = 2_000_000_000L;
    static final long BASE_BACKOFF_NANOS = 4_000_000_000L;
    static final long MAX_BACKOFF_NANOS = 60_000_000_000L;
    static final long STABLE_NANOS = 30_000_000_000L;
    static final int SETTLE_FRAMES = 30;
    static final int GPU_SAMPLES = 4;

    /** Caps applied to the user's configuration; the governor never raises a setting above its configured value. */
    record Limits(int refreshInterval, int shadowSoftness, int shadowDrawDistance, int lightsPerCell,
            int sunShadowDistance, boolean indirect, int indirectInterval) {}

    private static final Limits[] LIMITS = {
            new Limits(4, 0, 24, 2, 24, false, 8),
            new Limits(4, 0, 32, 3, 32, false, 4),
            new Limits(2, 1, 40, 4, 48, false, 2),
            new Limits(1, 2, 48, 6, 56, true, 2),
            new Limits(1, 4, 64, LightSourceManager.PER_SECTION, 64, true, 1)};

    static Limits limits(int level) { return LIMITS[Math.clamp(level, 0, LEVELS - 1)]; }

    private int target, minLevel, maxLevel = LEVELS - 1, level = LEVELS - 1;
    private double frameEma = Double.NaN, gpuEma = Double.NaN, cpuEma = Double.NaN;
    private int framesSinceChange, gpuSamplesSinceChange;
    private long lastChange = Long.MIN_VALUE, levelSince;
    private final long[] upgradeAllowed = new long[LEVELS];
    private final int[] failures = new int[LEVELS];
    private String status = "Governor OFF (fixed quality)";

    /** @param targetFps 0 disables adaptation and keeps the maximum allowed level. */
    void configure(int targetFps, int minimum, int maximum) {
        minimum = Math.clamp(minimum, 0, LEVELS - 1);
        maximum = Math.clamp(maximum, minimum, LEVELS - 1);
        if (targetFps != target || minimum != minLevel || maximum != maxLevel) {
            target = Math.max(0, targetFps); minLevel = minimum; maxLevel = maximum;
            level = Math.clamp(level, minLevel, maxLevel);
            if (target == 0) level = maxLevel;
            java.util.Arrays.fill(upgradeAllowed, Long.MIN_VALUE);
            java.util.Arrays.fill(failures, 0);
            reset();
        }
    }

    /**
     * @param frameMs wall time since the previous frame
     * @param gpuMs newest completed Rayvox GPU span, or NaN when no new sample completed this frame
     * @return the level to use for the next frame
     */
    int update(long now, double frameMs, double gpuMs) {
        return update(now, frameMs, gpuMs, Double.NaN);
    }

    /** CPU execution time excludes GPU/presentation waits; CPU and GPU durations may overlap. */
    int update(long now, double frameMs, double gpuMs, double cpuMs) {
        if (lastChange == Long.MIN_VALUE) { lastChange = now; levelSince = now; }
        if (target == 0) { level = maxLevel; status = "Governor OFF (fixed quality level " + level + ")"; return level; }
        if (frameMs > 0 && frameMs < 1000) frameEma = Double.isNaN(frameEma) ? frameMs : frameEma * 0.9 + frameMs * 0.1;
        if (Double.isFinite(cpuMs) && cpuMs >= 0 && cpuMs < 1000)
            cpuEma = Double.isNaN(cpuEma) ? cpuMs : cpuEma * 0.9 + cpuMs * 0.1;
        if (!Double.isNaN(gpuMs) && gpuMs >= 0) {
            gpuEma = Double.isNaN(gpuEma) ? gpuMs : gpuEma * 0.8 + gpuMs * 0.2;
            gpuSamplesSinceChange++;
        }
        framesSinceChange++;
        if (now - levelSince >= STABLE_NANOS) failures[level] = 0;
        double budget = 1000.0 / target;
        // Cache refreshes after a quality change cost extra; measure only once that transient has passed.
        if (framesSinceChange < SETTLE_FRAMES || now - lastChange < SETTLE_NANOS) {
            status = describe(budget, "settling");
            return level;
        }
        if (gpuSamplesSinceChange < GPU_SAMPLES) {
            status = describe(budget, "waiting for GPU timing");
            return level;
        }
        boolean over = frameEma > budget * 1.10;
        // GPU samples arrive late; CPU budget exhaustion is judged against its matching frame interval.
        boolean cpuBound = cpuEma > budget * 1.10 && cpuEma >= frameEma * 0.70;
        boolean cpuHeadroom = cpuBound && gpuEma < cpuEma * 0.75;
        boolean lightingBound = !cpuBound && gpuEma >= budget * 0.20 && gpuEma >= frameEma * 0.60;
        if (over && lightingBound && level > minLevel) {
            int left = level;
            change(now, level - 1);
            // Upgrading back into a level that just failed is delayed exponentially, preventing oscillation.
            failures[left] = Math.min(failures[left] + 1, 4);
            upgradeAllowed[left] = now + Math.min(MAX_BACKOFF_NANOS, BASE_BACKOFF_NANOS << (failures[left] - 1));
            status = describe(budget, "lowered (GPU lighting-bound)");
            resetMeasurements();
        } else if (level < maxLevel && now >= upgradeAllowed[level + 1]
                && (cpuHeadroom || frameEma < budget * 0.80 || (gpuEma < budget * 0.15 && frameEma <= budget * 1.05))) {
            // CPU-capped frames can recover quality without target-FPS headroom; failed upgrades still back off.
            change(now, level + 1);
            status = describe(budget, cpuHeadroom ? "raised (CPU-capped GPU headroom)" : "raised (headroom)");
            resetMeasurements();
        } else if (over && !lightingBound) {
            status = describe(budget, "holding: CPU or non-lighting bound");
        } else status = describe(budget, over ? "at minimum" : "stable");
        return level;
    }

    private void change(long now, int next) {
        level = next;
        lastChange = now; levelSince = now;
    }

    private void resetMeasurements() {
        frameEma = Double.NaN; gpuEma = Double.NaN; cpuEma = Double.NaN;
        framesSinceChange = 0; gpuSamplesSinceChange = 0;
    }

    /** Retain the selected quality, but require fresh evidence after a world/resource transition. */
    void reset() {
        lastChange = Long.MIN_VALUE;
        resetMeasurements();
    }

    private String describe(double budget, String state) {
        return String.format(Locale.ROOT, "Governor %d FPS: level %d [%d..%d], %s; frame %.2f / %.2f ms; Rayvox GPU %s; CPU work %s",
                target, level, minLevel, maxLevel, state, Double.isNaN(frameEma) ? 0.0 : frameEma, budget,
                Double.isNaN(gpuEma) ? "n/a" : String.format(Locale.ROOT, "%.2f ms", gpuEma),
                Double.isNaN(cpuEma) ? "n/a" : String.format(Locale.ROOT, "%.2f ms", cpuEma));
    }

    int level() { return level; }
    String status() { return status; }
}
