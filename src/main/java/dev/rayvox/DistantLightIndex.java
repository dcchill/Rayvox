package dev.rayvox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Committed emitter columns and their receiver lists; no world access or GL calls. */
final class DistantLightIndex {
    private final Map<Long, List<LightSourceManager.Light>> sources = new HashMap<>();
    private final Map<Long, Map<Integer, List<LightSourceManager.Light>>> receivers = new HashMap<>();
    private LightSourceManager definitionOwner;
    private long definitionRevision = Long.MIN_VALUE;

    boolean commit(int x, int z, List<LightSourceManager.Light> lights) {
        long key = key(x, z);
        var snapshot = List.copyOf(lights);
        if (snapshot.equals(sources.get(key))) return false;
        sources.put(key, snapshot);
        invalidate(x, z);
        return true;
    }

    void remove(int x, int z) {
        sources.remove(key(x, z));
        invalidate(x, z);
    }

    private void invalidate(int x, int z) {
        // Radius is at most 16 blocks, so only these nine receiver columns can be affected.
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) receivers.remove(key(x + dx, z + dz));
    }

    List<LightSourceManager.Light> section(int x, int y, int z, LightSourceManager manager) {
        if (definitionOwner != manager || definitionRevision != manager.definitionRevision()) {
            receivers.clear();
            definitionOwner = manager;
            definitionRevision = manager.definitionRevision();
        }
        long column = key(x, z);
        // Uncommitted/unloaded centers can be queried, but cannot grow a persistent cache while travelling.
        if (!sources.containsKey(column)) return selectNearby(x, y, z, manager);
        var levels = receivers.get(column);
        if (levels == null) {
            levels = new HashMap<>();
            receivers.put(column, levels);
        }
        var cached = levels.get(y);
        if (cached != null) return cached;
        var selected = selectNearby(x, y, z, manager);
        levels.put(y, selected);
        return selected;
    }

    private List<LightSourceManager.Light> selectNearby(int x, int y, int z, LightSourceManager manager) {
        var candidates = new ArrayList<LightSourceManager.Light>();
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            var snapshot = sources.get(key(x + dx, z + dz));
            if (snapshot != null) candidates.addAll(snapshot);
        }
        return select(candidates, x * 16, y * 16, z * 16, manager::definition);
    }

    private static long key(int x, int z) { return (x & 0xffffffffL) | (long)z << 32; }

    static List<LightSourceManager.Light> select(List<LightSourceManager.Light> lights, int x, int y, int z,
            Function<LightSourceManager.Light, LightDefinition> definitions) {
        var nearest = new LightSourceManager.Light[LightSourceManager.PER_SECTION];
        var scores = new double[LightSourceManager.PER_SECTION];
        int count = 0;
        for (var light : lights) {
            var definition = definitions.apply(light);
            if (!definition.contributes()) continue;
            double dx = Math.max(Math.max(x - light.x(), 0), light.x() - (x + 16));
            double dy = Math.max(Math.max(y - light.y(), 0), light.y() - (y + 16));
            double dz = Math.max(Math.max(z - light.z(), 0), light.z() - (z + 16));
            if (!(dx * dx + dy * dy + dz * dz < definition.radius() * definition.radius())) continue;
            double score = light.distanceSquared(x + 8, y + 8, z + 8);
            int position = count;
            while (position > 0 && score < scores[position - 1]) position--;
            if (position == nearest.length) continue;
            for (int i = Math.min(count, nearest.length - 1); i > position; i--) {
                nearest[i] = nearest[i - 1];
                scores[i] = scores[i - 1];
            }
            nearest[position] = light;
            scores[position] = score;
            count = Math.min(count + 1, nearest.length);
        }
        return count == 0 ? List.of() : List.of(java.util.Arrays.copyOf(nearest, count));
    }
}
