package dev.rayvox;

/** Matches LevelRenderer's Y(-90 degrees), X(time-of-day * 360 degrees) sky transform. */
record DirectionalLight(float x, float y, float z, float strength, int epoch) {
    static DirectionalLight at(double radians, float moonBrightness, float rain, boolean sky) {
        if (!Double.isFinite(radians) || !Float.isFinite(moonBrightness) || !Float.isFinite(rain))
            throw new IllegalArgumentException("Non-finite sky inputs");
        // shortcut: 0.1-degree shadow updates trade continuous motion for stable temporal reuse; measure before finer steps.
        int angle = Math.floorMod((int)Math.round(radians * 1800 / Math.PI), 3600);
        double quantized = angle * Math.PI / 1800;
        float x = (float)-Math.sin(quantized), y = (float)Math.cos(quantized);
        boolean moon = y < 0;
        if (moon) { x = -x; y = -y; }
        float strength = sky ? Math.clamp(y * 8, 0, 1) * (1 - Math.clamp(rain, 0, 1) * 0.75f)
                * (moon ? Math.clamp(moonBrightness, 0, 1) * 0.35f : 1) : 0;
        return new DirectionalLight(x, y, 0, strength, angle + (strength > 0 ? 3600 : 0));
    }
}
