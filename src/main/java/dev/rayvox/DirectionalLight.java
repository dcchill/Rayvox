package dev.rayvox;

/** Matches LevelRenderer's Y(-90 degrees), X(time-of-day * 360 degrees) sky transform. */
record DirectionalLight(float x, float y, float z, float strength, int epoch, float red, float green, float blue) {
    static final DirectionalLight OFF = new DirectionalLight(0, 1, 0, 0, 0, 0, 0, 0);

    static DirectionalLight at(double radians, float moonBrightness, float rain, boolean sky) {
        if (!Double.isFinite(radians) || !Float.isFinite(moonBrightness) || !Float.isFinite(rain))
            throw new IllegalArgumentException("Non-finite sky inputs");
        if (!sky) return OFF;
        // shortcut: 0.1-degree shadow updates trade continuous motion for stable temporal reuse; measure before finer steps.
        int angle = Math.floorMod((int)Math.round(radians * 1800 / Math.PI), 3600);
        double quantized = angle * Math.PI / 1800;
        float x = (float)-Math.sin(quantized), y = (float)Math.cos(quantized);
        boolean moon = y < 0;
        if (moon) { x = -x; y = -y; }
        float strength = Math.clamp(y * 8, 0, 1) * (1 - Math.clamp(rain, 0, 1) * 0.75f)
                * (moon ? Math.clamp(moonBrightness, 0, 1) * 0.35f : 1);
        // Atmospheric air mass follows the reference LightManager: low sunlight loses blue first.
        double airMass = 1 / (y + 0.15 * Math.pow(93.885 - Math.toDegrees(Math.acos(y)), -1.253));
        float red = moon ? 0.55f : (float)(1.45 * Math.exp(-0.225 * airMass));
        float green = moon ? 0.68f : (float)(1.45 * Math.exp(-0.420 * airMass));
        float blue = moon ? 1.0f : (float)(1.45 * Math.exp(-0.660 * airMass));
        return strength > 0 ? new DirectionalLight(x, y, 0, strength, angle + 3600, red, green, blue) : OFF;
    }
}
