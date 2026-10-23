package dev.rayvox;

/** Nearby work first, with aging to keep continuous near edits from starving distant sections. */
final class SectionScheduler {
    private final int[] age;
    private double sectionNanos = 500_000;

    SectionScheduler(int slots) { age = new int[slots]; }

    void tick(VoxelGrid grid) {
        for (int i = 0; i < age.length; i++)
            age[i] = grid.slots[i].dirty && grid.slots[i].source != null ? Math.min(age[i] + 1, 10_000) : 0;
    }

    int budget(int maximum) { return Math.clamp((int)(2_000_000 / sectionNanos), 1, maximum); }

    int next(VoxelGrid grid) {
        int selected = -1, best = Integer.MAX_VALUE;
        for (int i = 0; i < age.length; i++) {
            var slot = grid.slots[i];
            if (!slot.dirty || slot.source == null) continue;
            int x = slot.x - grid.centerX, y = slot.y - grid.centerY, z = slot.z - grid.centerZ;
            int score = x * x + y * y + z * z - age[i] * 2;
            if (score < best) { selected = i; best = score; }
        }
        return selected;
    }

    void completed(int index, long nanos) {
        age[index] = 0;
        sectionNanos = sectionNanos * 0.8 + Math.max(1, nanos) * 0.2;
    }
}
