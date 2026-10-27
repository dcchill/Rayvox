package dev.rayvox.render;

/** Delayed GPU measurements only; a CPU stall must not reduce image quality. */
public final class ResolutionController {
    private UpscalingMode mode=UpscalingMode.ADAPTIVE;
    private double scale=1, average=Double.NaN;
    private int samples, cooldown;

    /** A mode change discards old timing history and restarts adaptive at native. */
    public void configure(UpscalingMode mode) {
        java.util.Objects.requireNonNull(mode, "mode");
        if (this.mode == mode) return;
        this.mode=mode;
        scale=mode.scale();
        average=Double.NaN;
        samples=cooldown=0;
    }

    public double scale() { return scale; }
    public void sample(double millis) {
        if (mode != UpscalingMode.ADAPTIVE) return;
        if (!Double.isFinite(millis) || millis <= 0) return;
        average=Double.isNaN(average) ? millis : average*.9+millis*.1;
        if (cooldown > 0) { cooldown--; return; }
        if (++samples < 16) return;
        samples=0;
        double next=scale;
        if (average > 17.5) next=Math.max(.5, scale-.0625);
        else if (average < 13.5) next=Math.min(1, scale+.0625);
        if (next != scale) { scale=next; cooldown=60; }
    }
    public int dimension(int output) { return Math.max(1, (int)Math.ceil(output*scale)); }
}
