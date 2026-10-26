package dev.rayvox;

/** Linear RGB aggregates for emission chroma and diffuse reflectance; NativeImage pixels are ABGR. */
final class TextureColor {
    private static final float[] LINEAR = new float[256];
    static {
        for (int value = 0; value < LINEAR.length; value++) LINEAR[value] = LightDefinition.linear(value / 255f);
    }
    private double red, green, blue;
    private double diffuseRed, diffuseGreen, diffuseBlue, coverage;

    void add(int abgr) {
        float alpha = (abgr >>> 24) / 255f;
        if (alpha == 0) return;
        float r = LINEAR[abgr & 255];
        float g = LINEAR[abgr >>> 8 & 255];
        float b = LINEAR[abgr >>> 16 & 255];
        diffuseRed += r * alpha; diffuseGreen += g * alpha; diffuseBlue += b * alpha;
        coverage += alpha;
        double bright = Math.max(r, Math.max(g, b));
        double weight = alpha * bright * bright * bright;
        red += r * weight; green += g * weight; blue += b * weight;
    }

    int rgb() {
        double brightest = Math.max(red, Math.max(green, blue));
        if (brightest <= 0) return 0xffffff;
        // Emission supplies brightness independently; normalize texture chroma rather than dimming dark lamps twice.
        return channel(red / brightest) << 16 | channel(green / brightest) << 8 | channel(blue / brightest);
    }

    boolean hasColor() { return red + green + blue > 0; }

    // Unlike emission chroma, albedo must retain dark textures and black pixels.
    int albedo() {
        return coverage == 0 ? -1 : channel(diffuseRed / coverage) << 16
                | channel(diffuseGreen / coverage) << 8 | channel(diffuseBlue / coverage);
    }

    void merge(TextureColor color) {
        red += color.red; green += color.green; blue += color.blue;
        diffuseRed += color.diffuseRed; diffuseGreen += color.diffuseGreen; diffuseBlue += color.diffuseBlue;
        coverage += color.coverage;
    }

    static int channel(double linear) {
        double srgb = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return Math.clamp((int)Math.round(srgb * 255), 0, 255);
    }
}
