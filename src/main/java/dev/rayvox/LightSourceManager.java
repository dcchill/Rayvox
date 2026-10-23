package dev.rayvox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded source selection and 4-block tile lists. Contains no GL or world access. */
public final class LightSourceManager {
    public static final int MAX_LIGHTS = 64;
    public static final int PER_SECTION = 8;
    public static final int PER_SNAPSHOT = 64;
    public static final int TILE_SIZE = 4;
    public static final int TILES_PER_SECTION = 64;
    public static final int LIST_WIDTH = 9 * TILES_PER_SECTION;
    public record Light(String type, double x, double y, double z, LightDefinition definition) {
        public double distanceSquared(double px, double py, double pz) {
            return (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz);
        }
    }

    public final float[] data = new float[MAX_LIGHTS * 8];
    public int[] lists = new int[0];
    public List<Light> active = List.of();
    public List<Light> testLights = List.of();
    public Light selected;
    public int discovered, completeSections, truncatedSections;
    public long buildNanos;
    private final Map<Light, LightDefinition> overrides = new LinkedHashMap<>();
    private long lastRevision = -1;
    private int lastX, lastY, lastZ, lastLimit, lastPerSection;

    public boolean rebuild(VoxelGrid grid, int x, int y, int z, int limit, int perSection, int minSection, int maxSection) {
        if (limit < 1 || limit > MAX_LIGHTS || perSection < 1 || perSection > PER_SECTION)
            throw new IllegalArgumentException("Invalid light budget");
        if (lastRevision == grid.revision && x == lastX && y == lastY && z == lastZ
                && limit == lastLimit && perSection == lastPerSection) return false;
        long start = System.nanoTime();
        boolean sameInputs = lastRevision == grid.revision && limit == lastLimit && perSection == lastPerSection;
        lastRevision = grid.revision;
        lastX = x; lastY = y; lastZ = z; lastLimit = limit; lastPerSection = perSection;
        var all = new ArrayList<Light>();
        var retained = new ArrayList<Light>();
        for (var slot : grid.slots) if (slot.source != null) {
            retained.addAll(slot.lights);
            retained.addAll(slot.uploadedLights);
            if (slot.uploaded) all.addAll(slot.uploadedLights);
        }
        all.addAll(testLights);
        retained.addAll(testLights);
        overrides.keySet().retainAll(retained);
        if (selected != null && !retained.contains(selected)) selected = null;
        discovered = all.size();
        all.sort(Comparator.comparingDouble(light -> light.distanceSquared(x, y, z)));
        var selection = all.subList(0, Math.min(limit, all.size()));
        if (sameInputs && active.equals(selection)) return false;
        active = List.copyOf(selection);
        boolean[] omittedInfluence = new boolean[grid.slots.length];
        for (int i = active.size(); i < all.size(); i++) {
            var light = all.get(i);
            float radius = definition(light).radius();
            for (int sz = (int)Math.floor((light.z - radius) / 16); sz <= (int)Math.floor((light.z + radius) / 16); sz++) {
                for (int sy = (int)Math.floor((light.y - radius) / 16); sy <= (int)Math.floor((light.y + radius) / 16); sy++) {
                    for (int sx = (int)Math.floor((light.x - radius) / 16); sx <= (int)Math.floor((light.x + radius) / 16); sx++) {
                        if (grid.get(sx, sy, sz) != null && aabbDistanceSquared(light, sx * 16, sy * 16, sz * 16, 16) < radius * radius)
                            omittedInfluence[grid.index(sx, sy, sz)] = true;
                    }
                }
            }
        }
        Arrays.fill(data, 0);
        int minX = (grid.centerX - grid.radius) * 16;
        int minY = (grid.centerY - grid.radius) * 16;
        int minZ = (grid.centerZ - grid.radius) * 16;
        for (int i = 0; i < active.size(); i++) {
            Light light = active.get(i);
            var def = definition(light);
            int offset = i * 8;
            data[offset] = (float)(light.x - minX);
            data[offset + 1] = (float)(light.y - minY);
            data[offset + 2] = (float)(light.z - minZ);
            data[offset + 3] = def.radius();
            data[offset + 4] = LightDefinition.linear(def.red()) * def.intensity();
            data[offset + 5] = LightDefinition.linear(def.green()) * def.intensity();
            data[offset + 6] = LightDefinition.linear(def.blue()) * def.intensity();
            data[offset + 7] = def.shadows() ? 1 : 0;
        }
        if (lists.length != grid.slots.length * LIST_WIDTH) lists = new int[grid.slots.length * LIST_WIDTH];
        Arrays.fill(lists, 0);
        completeSections = truncatedSections = 0;
        int[] nearest = new int[PER_SECTION];
        double[] distances = new double[PER_SECTION];
        for (int row = 0; row < grid.slots.length; row++) {
            var slot = grid.slots[row];
            boolean complete = !omittedInfluence[row];
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (slot.y + dy < minSection || slot.y + dy >= maxSection) continue;
                var neighbor = grid.get(slot.x + dx, slot.y + dy, slot.z + dz);
                if (neighbor == null || !neighbor.uploaded || neighbor.uploadedLightOverflow) complete = false;
            }
            boolean truncated = false;
            for (int tile = 0; tile < TILES_PER_SECTION; tile++) {
                int tx = slot.x * 16 + (tile % 4) * TILE_SIZE;
                int ty = slot.y * 16 + (tile / 4 % 4) * TILE_SIZE;
                int tz = slot.z * 16 + (tile / 16) * TILE_SIZE;
                int relevant = 0, count = 0;
                for (int i = 0; i < active.size(); i++) {
                    var light = active.get(i);
                    double distance = aabbDistanceSquared(light, tx, ty, tz, TILE_SIZE);
                    float radius = definition(light).radius();
                    if (distance >= radius * radius) continue;
                    relevant++;
                    double score = light.distanceSquared(tx + 2, ty + 2, tz + 2);
                    int position = count;
                    while (position > 0 && score < distances[position - 1]) position--;
                    if (position >= perSection) continue;
                    for (int j = Math.min(count, perSection - 1); j > position; j--) {
                        distances[j] = distances[j - 1];
                        nearest[j] = nearest[j - 1];
                    }
                    distances[position] = score;
                    nearest[position] = i;
                    count = Math.min(count + 1, perSection);
                }
                boolean tileComplete = complete && relevant <= perSection;
                if (relevant > perSection) truncated = true;
                int offset = row * LIST_WIDTH + tile * 9;
                lists[offset] = count | (tileComplete ? 0x100 : 0);
                for (int i = 0; i < count; i++) lists[offset + 1 + i] = nearest[i];
            }
            if (truncated) truncatedSections++;
            if (complete && !truncated) completeSections++;
        }
        buildNanos = System.nanoTime() - start;
        return true;
    }

    private static double aabbDistanceSquared(Light light, double x, double y, double z, int size) {
        double dx = Math.max(Math.max(x - light.x, 0), light.x - (x + size));
        double dy = Math.max(Math.max(y - light.y, 0), light.y - (y + size));
        double dz = Math.max(Math.max(z - light.z, 0), light.z - (z + size));
        return dx * dx + dy * dy + dz * dz;
    }

    public LightDefinition definition(Light light) { return overrides.getOrDefault(light, light.definition); }
    public void invalidate() { lastRevision = -1; }
    public void adjust(LightDefinition definition) {
        if (selected == null) return;
        if (overrides.size() >= MAX_LIGHTS && !overrides.containsKey(selected)) overrides.remove(overrides.keySet().iterator().next());
        overrides.put(selected, definition);
        invalidate();
    }

    public void select(double x, double y, double z, double dx, double dy, double dz) {
        selected = null;
        double nearest = 12;
        for (var light : active) {
            double along = (light.x - x) * dx + (light.y - y) * dy + (light.z - z) * dz;
            double perpendicular = light.distanceSquared(x, y, z) - along * along;
            if (along > 0 && along < nearest && perpendicular < 0.65 * 0.65) { selected = light; nearest = along; }
        }
    }
}
