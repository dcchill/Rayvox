package dev.rayvox;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Box bounds or conservative 4x4x4 occupancy, represented by the same two GPU uints. */
final class ShapeMask {
    static long box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!(minX < maxX && minY < maxY && minZ < maxZ)) return 0;
        if (minX <= 0 && minY <= 0 && minZ <= 0 && maxX >= 1 && maxY >= 1 && maxZ >= 1) return -1L;
        int x0 = Math.max(0, (int)Math.floor(minX * 4)), x1 = Math.min(4, (int)Math.ceil(maxX * 4));
        int y0 = Math.max(0, (int)Math.floor(minY * 4)), y1 = Math.min(4, (int)Math.ceil(maxY * 4));
        int z0 = Math.max(0, (int)Math.floor(minZ * 4)), z1 = Math.min(4, (int)Math.ceil(maxZ * 4));
        if (x0 >= x1 || y0 >= y1 || z0 >= z1) return 0;
        long row = ((1L << (x1 - x0)) - 1) << x0;
        long mask = 0;
        for (int z = z0; z < z1; z++) for (int y = y0; y < y1; y++) mask |= row << (4 * (y + 4 * z));
        return mask;
    }

    /** A bounded extraction-local cache; dynamic block and fluid shapes bypass retained entries. */
    static final class Cache {
        private record Packed(long bits, boolean box) {}
        private final java.util.IdentityHashMap<VoxelShape, Packed> shapes = new java.util.IdentityHashMap<>();
        private final Quantizer quantizer = new Quantizer();
        private boolean boxEncoded;

        long of(VoxelShape shape, boolean stable) {
            var packed = stable ? shapes.get(shape) : null;
            if (packed != null) {
                boxEncoded = packed.box;
                return packed.bits;
            }
            long bits = quantizer.of(shape);
            boxEncoded = quantizer.boxEncoded();
            if (stable && !shape.isEmpty() && shape != Shapes.block()) {
                if (shapes.size() == 1024) shapes.clear();
                shapes.put(shape, new Packed(bits, boxEncoded));
            }
            return bits;
        }

        boolean boxEncoded() { return boxEncoded; }
        int cachedShapes() { return shapes.size(); }
    }

    /** Reused on the extraction thread; avoids allocating lists and AABBs for every voxel. */
    static final class Quantizer implements Shapes.DoubleLineConsumer {
        private long mask;
        private int boxes;
        private boolean boxEncoded;
        private double minX, minY, minZ, maxX, maxY, maxZ;

        long of(VoxelShape shape) {
            boxEncoded = false;
            if (shape.isEmpty()) return 0;
            if (shape == Shapes.block()) return -1L;
            mask = 0;
            boxes = 0;
            shape.forAllBoxes(this);
            if (boxes == 1) {
                long bounds = BoxShape.pack(minX, minY, minZ, maxX, maxY, maxZ);
                boxEncoded = bounds != 0 && bounds != BoxShape.FULL;
                return bounds == BoxShape.FULL ? -1L : bounds;
            }
            return mask;
        }

        boolean boxEncoded() { return boxEncoded; }

        @Override public void consume(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (boxes++ == 0) {
                this.minX = minX; this.minY = minY; this.minZ = minZ;
                this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
                return;
            }
            if (boxes == 2) mask = box(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
            mask |= box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
