package dev.rayvox;

/** Bounded section-aligned ring; CPU and GPU use the same physical slot coordinates. */
public final class VoxelGrid {
    public static final int SECTION_SIZE = 16;
    public static final int SECTION_BYTES = 4096;
    public final int radius;
    public final int width;
    public final Slot[] slots;
    public int centerX, centerY, centerZ;
    public long revision;
    private boolean centered;

    public VoxelGrid(int radius) {
        if (radius < 1 || radius > 4) throw new IllegalArgumentException("Cache radius must be 1..4 sections");
        this.radius = radius;
        width = radius * 2 + 1;
        slots = new Slot[width * width * width];
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    public int index(int x, int y, int z) {
        return Math.floorMod(x, width) + width * (Math.floorMod(y, width) + width * Math.floorMod(z, width));
    }

    public static int voxelIndex(int x, int y, int z) {
        return Math.floorMod(x, 16) + 16 * (Math.floorMod(y, 16) + 16 * Math.floorMod(z, 16));
    }

    public int recenter(int x, int y, int z) {
        if (centered && x == centerX && y == centerY && z == centerZ) return 0;
        centerX = x;
        centerY = y;
        centerZ = z;
        centered = true;
        revision++;
        int replaced = 0;
        for (int sz = z - radius; sz <= z + radius; sz++) {
            for (int sy = y - radius; sy <= y + radius; sy++) {
                for (int sx = x - radius; sx <= x + radius; sx++) {
                    Slot slot = slots[index(sx, sy, sz)];
                    if (!slot.assigned || slot.x != sx || slot.y != sy || slot.z != sz) {
                        slot.x = sx;
                        slot.y = sy;
                        slot.z = sz;
                        slot.assigned = true;
                        slot.setSource(null);
                        slot.dirty = slot.ready = slot.uploaded = false;
                        slot.lights = java.util.List.of();
                        slot.uploadedLights = java.util.List.of();
                        slot.lightOverflow = false;
                        slot.uploadedLightOverflow = false;
                        replaced++;
                    }
                }
            }
        }
        return replaced;
    }

    public Slot get(int x, int y, int z) {
        Slot slot = slots[index(x, y, z)];
        return slot.assigned && slot.x == x && slot.y == y && slot.z == z ? slot : null;
    }

    public void markDirty(int x, int y, int z) {
        Slot slot = get(x, y, z);
        if (slot != null && slot.source != null) {
            revision++;
            slot.dirty = true;
            slot.ready = false;
        }
    }

    public void blockChanged(int x, int y, int z) {
        // Neighbor-dependent shapes can cross a section boundary.
        for (int sz = Math.floorDiv(z - 1, 16); sz <= Math.floorDiv(z + 1, 16); sz++) {
            for (int sy = Math.floorDiv(y - 1, 16); sy <= Math.floorDiv(y + 1, 16); sy++) {
                for (int sx = Math.floorDiv(x - 1, 16); sx <= Math.floorDiv(x + 1, 16); sx++) markDirty(sx, sy, sz);
            }
        }
    }

    public void invalidateUploads() {
        revision++;
        for (Slot slot : slots) {
            slot.uploaded = false;
            if (slot.source != null && !slot.dirty) slot.ready = true;
        }
    }

    public int pending() {
        int count = 0;
        for (Slot slot : slots) if (slot.dirty || slot.ready) count++;
        return count;
    }

    public int resident() {
        int count = 0;
        for (Slot slot : slots) if (slot.uploaded) count++;
        return count;
    }

    public static final class Slot {
        public int x, y, z;
        public boolean assigned, dirty, ready, uploaded;
        public Object source;
        public java.util.List<LightSourceManager.Light> lights = java.util.List.of();
        public java.util.List<LightSourceManager.Light> uploadedLights = java.util.List.of();
        public boolean lightOverflow;
        public boolean uploadedLightOverflow;
        public final byte[] data = new byte[SECTION_BYTES];
        public final byte[] albedo = new byte[SECTION_BYTES * 3];
        public final long[] shapes = new long[SECTION_BYTES];

        Slot() { java.util.Arrays.fill(shapes, -1L); java.util.Arrays.fill(albedo, (byte)153); }

        public void setSource(Object current) {
            if (source != current) {
                source = current;
                dirty = current != null;
                ready = uploaded = false;
                lights = java.util.List.of();
                uploadedLights = java.util.List.of();
                lightOverflow = false;
                uploadedLightOverflow = false;
            }
        }

        public int state() {
            return (uploaded ? 1 : 0) | (dirty || ready ? 2 : 0);
        }
    }
}
