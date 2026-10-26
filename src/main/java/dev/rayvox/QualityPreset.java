package dev.rayvox;

/** Hardware presets select pipeline architecture and workload budgets, not only shader constants. */
public enum QualityPreset {
    CUSTOM(null),
    PERFORMANCE(new Settings(LightingPipeline.DEFERRED, 4, 0, 32, 3, 16, 32, false, 4, false, 30, 0, 2)),
    BALANCED(new Settings(LightingPipeline.DEFERRED, 2, 1, 48, 4, 32, 48, false, 2, false, 60, 1, 3)),
    QUALITY(new Settings(LightingPipeline.DEFERRED, 1, 2, 64, LightSourceManager.PER_SECTION,
            LightSourceManager.MAX_LIGHTS, 64, true, 1, true, 0, 2, 4));

    /**
     * @param refreshInterval frames over which stale cached pixels are re-traced (1 = every frame)
     * @param governorTarget 0 keeps fixed quality
     */
    public record Settings(LightingPipeline pipeline, int refreshInterval, int shadowSoftness, int shadowDrawDistance,
            int lightsPerCell, int activeLights, int sunShadowDistance, boolean indirect, int indirectInterval, boolean materials,
            int governorTarget, int governorMin, int governorMax) {}

    public final Settings settings;

    QualityPreset(Settings settings) { this.settings = settings; }

    /** Writes the preset into the client config; CUSTOM leaves the user's values untouched. */
    public void apply() {
        if (settings == null) return;
        Rayvox.PIPELINE.set(settings.pipeline());
        Rayvox.REFRESH_INTERVAL.set(settings.refreshInterval());
        Rayvox.HISTORY.set(true);
        Rayvox.SHADOW_SOFTNESS.set(settings.shadowSoftness());
        Rayvox.SHADOW_DRAW_DISTANCE.set(settings.shadowDrawDistance());
        Rayvox.LIGHTS_PER_SECTION.set(settings.lightsPerCell());
        Rayvox.ACTIVE_LIGHTS.set(settings.activeLights());
        Rayvox.SUN_SHADOW_DISTANCE.set(settings.sunShadowDistance());
        Rayvox.SUNLIGHT.set(true);
        Rayvox.GODRAYS.set(this != PERFORMANCE);
        Rayvox.INDIRECT.set(settings.indirect());
        Rayvox.INDIRECT_INTERVAL.set(settings.indirectInterval());
        Rayvox.MATERIALS.set(settings.materials());
        Rayvox.GOVERNOR_TARGET.set(settings.governorTarget());
        Rayvox.GOVERNOR_MIN.set(settings.governorMin());
        Rayvox.GOVERNOR_MAX.set(settings.governorMax());
        Rayvox.PRESET.set(this);
    }
}
