package dev.rayvox.render;

/** Internal render scale; ADAPTIVE starts native and requires GPU timing support. */
public enum UpscalingMode {
    NATIVE(1),
    QUALITY(2.0 / 3),
    BALANCED(1.0 / 1.7),
    PERFORMANCE(.5),
    ADAPTIVE(1);

    private final double scale;

    UpscalingMode(double scale) { this.scale = scale; }

    public double scale() { return scale; }

    /** Round up to cover the output, including tiny or not-yet-sized windows. */
    public int dimension(int output) { return Math.max(1, (int)Math.ceil(output * scale)); }
}