package dev.rayvox;

/** Carry source emission in the four bits discarded by vanilla's lightmap lookup. */
public final class EmissiveLight {
    public static int[] encode(int[] lightmap, int emission) {
        int value = Math.clamp(emission, 0, 15);
        for (int i = 0; i < lightmap.length; i++) lightmap[i] = (lightmap[i] & ~15) | value;
        return lightmap;
    }

    private EmissiveLight() {}
}
