package dev.rayvox;

/** Conservative 4x4x4 occupancy, represented by two GPU uints. Slab half heights are exact. */
final class ShapeMask {
    static long box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        long mask = 0;
        for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
            if (maxX > x / 4.0 && minX < (x + 1) / 4.0 && maxY > y / 4.0 && minY < (y + 1) / 4.0
                    && maxZ > z / 4.0 && minZ < (z + 1) / 4.0) mask |= 1L << (x + 4 * (y + 4 * z));
        return mask;
    }
}
