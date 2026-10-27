package dev.rayvox;

/** Conservative separator test against committed full opaque cubes, independent of material settings. */
final class VoxelOcclusion {
    static void commit(VoxelGrid.Slot slot) {
        java.util.Arrays.fill(slot.uploadedOpaque, 0);
        for (int i = 0; i < slot.data.length; i++)
            if ((slot.data[i] & 63) == 1) slot.uploadedOpaque[i >>> 6] |= 1L << (i & 63);
    }

    static boolean blocked(VoxelGrid grid, LightSourceManager.Light light, int x, int y, int z) {
        int axis = 0;
        for (int a = 1; a < 3; a++)
            if (gap(source(light, a), tile(x, y, z, a) - 0.05, tile(x, y, z, a) + 4.05)
                    > gap(source(light, axis), tile(x, y, z, axis) - 0.05, tile(x, y, z, axis) + 4.05)) axis = a;
        double startAxis = source(light, axis), lowAxis = tile(x, y, z, axis) - 0.05, highAxis = lowAxis + 4.1;
        if (gap(startAxis, lowAxis, highAxis) < 2) return false;
        // Test one plane halfway across the gap. Failure means unknown, never hidden.
        double edge = startAxis < lowAxis ? lowAxis : highAxis;
        int plane = (int)Math.floor((startAxis + edge) * 0.5);
        double coordinate = plane + 0.5;
        // Bound the complete source-to-tile cone, including all soft-shadow samples (radius <= .14).
        double tMin = 1, tMax = 0;
        for (int side = -1; side <= 1; side += 2) {
            double start = startAxis + side * 0.2;
            double t0 = (coordinate - start) / (lowAxis - start);
            double t1 = (coordinate - start) / (highAxis - start);
            tMin = Math.min(tMin, Math.min(t0, t1));
            tMax = Math.max(tMax, Math.max(t0, t1));
        }
        if (tMin <= 0 || tMax >= 1) return false;
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        int u0 = minimum(source(light, u), tile(x, y, z, u) - 0.05, tMin, tMax);
        int u1 = maximum(source(light, u), tile(x, y, z, u) + 4.05, tMin, tMax);
        int v0 = minimum(source(light, v), tile(x, y, z, v) - 0.05, tMin, tMax);
        int v1 = maximum(source(light, v), tile(x, y, z, v) + 4.05, tMin, tMax);
        for (int a = u0; a <= u1; a++) for (int b = v0; b <= v1; b++) {
            int cx = axis == 0 ? plane : u == 0 ? a : b;
            int cy = axis == 1 ? plane : u == 1 ? a : b;
            int cz = axis == 2 ? plane : u == 2 ? a : b;
            var slot = grid.get(Math.floorDiv(cx, 16), Math.floorDiv(cy, 16), Math.floorDiv(cz, 16));
            if (slot == null || !slot.uploaded) return false;
            int index = VoxelGrid.voxelIndex(cx, cy, cz);
            if ((slot.uploadedOpaque[index >>> 6] & (1L << (index & 63))) == 0) return false;
        }
        return true;
    }

    private static double source(LightSourceManager.Light light, int axis) {
        return axis == 0 ? light.x() : axis == 1 ? light.y() : light.z();
    }

    private static int tile(int x, int y, int z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static double gap(double source, double low, double high) {
        return Math.max(low - source, source - high);
    }

    private static int minimum(double source, double low, double t0, double t1) {
        return (int)Math.floor(Math.min(source + (low - source) * t0, source + (low - source) * t1) - 0.2);
    }

    private static int maximum(double source, double high, double t0, double t1) {
        return (int)Math.floor(Math.max(source + (high - source) * t0, source + (high - source) * t1) + 0.2);
    }
}
