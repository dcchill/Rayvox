package dev.rayvox;

/** Six conservative 1/256-block bounds in 54 bits; fits the existing two-uint shape texture. */
final class BoxShape {
    static final int FLAG = 64;
    static final int MATERIAL_MASK = 63;
    static final long FULL = 256L << 27 | 256L << 36 | 256L << 45;

    static long pack(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!(minX < maxX && minY < maxY && minZ < maxZ)) return 0;
        int x0 = lower(minX), y0 = lower(minY), z0 = lower(minZ);
        int x1 = upper(maxX), y1 = upper(maxY), z1 = upper(maxZ);
        if (x0 >= x1 || y0 >= y1 || z0 >= z1) return 0;
        return (long)x0 | (long)y0 << 9 | (long)z0 << 18 | (long)x1 << 27 | (long)y1 << 36 | (long)z1 << 45;
    }

    static double coordinate(long shape, int index) { return (shape >>> (index * 9) & 511) / 256.0; }

    private static int lower(double value) { return Math.clamp((int)Math.floor(value * 256), 0, 256); }
    private static int upper(double value) { return Math.clamp((int)Math.ceil(value * 256), 0, 256); }
}
