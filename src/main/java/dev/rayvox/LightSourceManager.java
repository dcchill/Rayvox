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
    private int lastMinSection, lastMaxSection;
    private int eligibleSources;
    private long definitionRevision;

    public boolean rebuild(VoxelGrid grid, int x, int y, int z, int limit, int perSection, int minSection, int maxSection) {
        if (limit < 1 || limit > MAX_LIGHTS || perSection < 1 || perSection > PER_SECTION)
            throw new IllegalArgumentException("Invalid light budget");
        if (lastRevision == grid.snapshotRevision && (eligibleSources <= limit || x == lastX && y == lastY && z == lastZ)
                && limit == lastLimit && perSection == lastPerSection
                && minSection == lastMinSection && maxSection == lastMaxSection) return false;
        long start = System.nanoTime();
        boolean sameInputs = lastRevision == grid.snapshotRevision && limit == lastLimit && perSection == lastPerSection
                && minSection == lastMinSection && maxSection == lastMaxSection;
        lastRevision = grid.snapshotRevision;
        lastX = x; lastY = y; lastZ = z; lastLimit = limit; lastPerSection = perSection;
        lastMinSection = minSection; lastMaxSection = maxSection;
        var all = new ArrayList<Light>();
        var retained = new ArrayList<Light>();
        for (var slot : grid.slots) if (slot.source != null) {
            retained.addAll(slot.lights);
            retained.addAll(slot.uploadedLights);
            if (slot.uploaded) all.addAll(slot.uploadedLights);
        }
        all.addAll(testLights);
        retained.addAll(testLights);
        if (overrides.keySet().retainAll(retained)) definitionRevision++;
        if (selected != null && !retained.contains(selected)) selected = null;
        discovered = all.size();
        all.removeIf(light -> !definition(light).contributes());
        eligibleSources = all.size();
        boolean[] included = new boolean[all.size()];
        var selection = selectNearest(all, x, y, z, limit, included);
        // Camera motion can reorder the same sources. Keep their GPU indices and history stable.
        if (sameInputs && sameSources(active, selection)) return false;
        active = List.copyOf(selection);
        boolean[] omittedInfluence = new boolean[grid.slots.length];
        int omittedSections = 0;
        for (int i = 0; i < all.size() && omittedSections < grid.slots.length; i++) {
            if (included[i]) continue;
            var light = all.get(i);
            float radius = definition(light).radius();
            for (int sz = (int)Math.floor((light.z - radius) / 16); sz <= (int)Math.floor((light.z + radius) / 16); sz++) {
                for (int sy = (int)Math.floor((light.y - radius) / 16); sy <= (int)Math.floor((light.y + radius) / 16); sy++) {
                    for (int sx = (int)Math.floor((light.x - radius) / 16); sx <= (int)Math.floor((light.x + radius) / 16); sx++) {
                        if (grid.get(sx, sy, sz) == null) continue;
                        int index = grid.index(sx, sy, sz);
                        if (!omittedInfluence[index] && aabbDistanceSquared(light, sx * 16, sy * 16, sz * 16, 16) < radius * radius) {
                            omittedInfluence[index] = true;
                            omittedSections++;
                        }
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
        double[] radiusSquared = new double[active.size()];
        for (int i = 0; i < active.size(); i++) {
            float radius = definition(active.get(i)).radius();
            radiusSquared[i] = radius * radius;
        }
        int[] candidates = new int[active.size()];
        for (int row = 0; row < grid.slots.length; row++) {
            var slot = grid.slots[row];
            // A tile is contained in its section: reject distant sources once, rather than 64 times.
            int candidateCount = 0;
            for (int i = 0; i < active.size(); i++)
                if (aabbDistanceSquared(active.get(i), slot.x * 16, slot.y * 16, slot.z * 16, 16) < radiusSquared[i])
                    candidates[candidateCount++] = i;
            boolean complete = !omittedInfluence[row] && completeNeighbors(grid, slot, minSection, maxSection);
            if (candidateCount == 0) {
                if (complete) {
                    completeSections++;
                    int offset = row * LIST_WIDTH;
                    for (int tile = 0; tile < TILES_PER_SECTION; tile++) lists[offset + tile * 9] = 0x100;
                }
                continue;
            }
            boolean truncated = false;
            for (int tile = 0; tile < TILES_PER_SECTION; tile++) {
                int tx = slot.x * 16 + (tile % 4) * TILE_SIZE;
                int ty = slot.y * 16 + (tile / 4 % 4) * TILE_SIZE;
                int tz = slot.z * 16 + (tile / 16) * TILE_SIZE;
                int relevant = 0, count = 0;
                for (int candidate = 0; candidate < candidateCount; candidate++) {
                    int i = candidates[candidate];
                    var light = active.get(i);
                    double distance = aabbDistanceSquared(light, tx, ty, tz, TILE_SIZE);
                    if (distance >= radiusSquared[i]) continue;
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

    private static boolean completeNeighbors(VoxelGrid grid, VoxelGrid.Slot slot, int minSection, int maxSection) {
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            if (slot.y + dy < minSection || slot.y + dy >= maxSection) continue;
            var neighbor = grid.get(slot.x + dx, slot.y + dy, slot.z + dz);
            if (neighbor == null || !neighbor.uploaded || neighbor.uploadedLightOverflow) return false;
        }
        return true;
    }

    private record RankedLight(Light light, double distance, int order) {}
    private static final Comparator<RankedLight> NEAREST = Comparator.comparingDouble(RankedLight::distance)
            .thenComparingInt(RankedLight::order);

    private static List<Light> selectNearest(ArrayList<Light> all, int x, int y, int z, int limit, boolean[] included) {
        if (all.size() <= limit * 2) {
            all.sort(Comparator.comparingDouble(light -> light.distanceSquared(x, y, z)));
            int count = Math.min(limit, all.size());
            Arrays.fill(included, 0, count, true);
            return all.subList(0, count);
        }
        // Dense emissive sections can supply thousands of sources. Rank only the bounded active set.
        var nearest = new java.util.PriorityQueue<>(limit, NEAREST.reversed());
        for (int i = 0; i < all.size(); i++) {
            var light = all.get(i);
            double distance = light.distanceSquared(x, y, z);
            if (nearest.size() == limit) {
                if (Double.compare(distance, nearest.peek().distance()) >= 0) continue;
                nearest.remove();
            }
            nearest.add(new RankedLight(light, distance, i));
        }
        var ordered = nearest.toArray(RankedLight[]::new);
        Arrays.sort(ordered, NEAREST);
        var result = new ArrayList<Light>(ordered.length);
        for (var ranked : ordered) {
            included[ranked.order()] = true;
            result.add(ranked.light());
        }
        return result;
    }

    private static boolean sameSources(List<Light> first, List<Light> second) {
        if (first.size() != second.size()) return false;
        if (first.equals(second)) return true;
        var remaining = new ArrayList<>(second);
        for (var light : first) if (!remaining.remove(light)) return false;
        return true;
    }

    public LightDefinition definition(Light light) { return overrides.getOrDefault(light, light.definition); }
    long definitionRevision() { return definitionRevision; }
    public void invalidate() { lastRevision = -1; }
    public void adjust(LightDefinition definition) {
        if (selected == null) return;
        if (overrides.size() >= MAX_LIGHTS && !overrides.containsKey(selected)) overrides.remove(overrides.keySet().iterator().next());
        overrides.put(selected, definition);
        definitionRevision++;
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
