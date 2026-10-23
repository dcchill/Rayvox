package dev.rayvox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Sparse emitter-only cache for loaded render-distance chunks; no distant voxel allocation. */
final class DistantLightCache {
    private static final int PER_CHUNK = 128;
    private final Map<Long, Column> columns = new HashMap<>();
    private final Map<Long, List<LightSourceManager.Light>> sections = new HashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    long revision;

    void tick(ClientLevel level, int cameraX, int cameraZ, int distance, LightColors colors) {
        int cx = Math.floorDiv(cameraX, 16), cz = Math.floorDiv(cameraZ, 16);
        int radius = Math.clamp(distance, 2, 32);
        boolean removed = columns.entrySet().removeIf(entry -> {
            var column = entry.getValue();
            return Math.abs(column.x - cx) > radius || Math.abs(column.z - cz) > radius
                    || level.getChunkSource().getChunk(column.x, column.z, ChunkStatus.FULL, false) != column.chunk;
        });
        if (removed) { sections.clear(); revision++; }
        Column next = null;
        int best = Integer.MAX_VALUE;
        for (int z = cz - radius; z <= cz + radius; z++) for (int x = cx - radius; x <= cx + radius; x++) {
            var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            long key = ChunkPos.asLong(x, z);
            var column = columns.get(key);
            if (column == null) { column = new Column(x, z, chunk, level.getMinSection()); columns.put(key, column); revision++; }
            int priority = (x - cx) * (x - cx) + (z - cz) * (z - cz);
            if (column.dirty && priority < best) { next = column; best = priority; }
        }
        if (next == null) return;
        long deadline = System.nanoTime() + 2_000_000;
        for (int work = 0; work < 64 && next != null; work++) {
            while (next.section < level.getMaxSection() && next.pending.size() < PER_CHUNK) {
                int sy = next.section++;
                var section = next.chunk.getSection(level.getSectionIndexFromSectionY(sy));
                if (section.hasOnlyAir() || !section.maybeHas(state -> state.hasDynamicLightEmission()
                        || state.getLightEmission(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO) > 0)) continue;
                for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    if (next.pending.size() >= PER_CHUNK) break;
                    var state = section.getBlockState(x, y, z);
                    cursor.set(next.x * 16 + x, sy * 16 + y, next.z * 16 + z);
                    int emission = state.getLightEmission(level, cursor);
                    if (emission <= 0) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    var definition = colors.resolve(state, emission);
                    next.pending.add(new LightSourceManager.Light(id, cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5, definition));
                }
                if (System.nanoTime() >= deadline) return;
            }
            next.lights = List.copyOf(next.pending);
            next.pending.clear();
            next.dirty = false;
            next.ready = true;
            sections.clear();
            revision++;
            if (System.nanoTime() >= deadline) return;
            next = null;
            best = Integer.MAX_VALUE;
            for (var column : columns.values()) {
                int priority = (column.x - cx) * (column.x - cx) + (column.z - cz) * (column.z - cz);
                if (column.dirty && priority < best) { next = column; best = priority; }
            }
        }
    }

    String status() {
        long pending = columns.values().stream().filter(column -> column.dirty).count();
        return "Distant RGB: " + (columns.size() - pending) + " chunks ready; " + pending + " pending";
    }

    void changed(int x, int z, int minSection, boolean unload) {
        var column = columns.get(ChunkPos.asLong(x, z));
        if (column == null) return;
        if (unload) { columns.remove(ChunkPos.asLong(x, z)); sections.clear(); revision++; return; }
        column.dirty = true;
        column.section = minSection;
        column.pending.clear();
    }

    boolean ready(int x, int z) {
        var center = columns.get(ChunkPos.asLong(x, z));
        if (center == null || !center.ready) return false;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            var neighbor = columns.get(ChunkPos.asLong(x + dx, z + dz));
            if (neighbor != null && !neighbor.ready) return false;
        }
        return true;
    }

    List<LightSourceManager.Light> section(int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        return sections.computeIfAbsent(key, ignored -> {
            var candidates = new ArrayList<LightSourceManager.Light>();
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                var column = columns.get(ChunkPos.asLong(x + dx, z + dz));
                if (column != null) candidates.addAll(column.lights);
            }
            return select(candidates, x * 16, y * 16, z * 16);
        });
    }

    static List<LightSourceManager.Light> select(List<LightSourceManager.Light> lights, int x, int y, int z) {
        return lights.stream().filter(light -> {
            double dx = Math.max(Math.max(x - light.x(), 0), light.x() - (x + 16));
            double dy = Math.max(Math.max(y - light.y(), 0), light.y() - (y + 16));
            double dz = Math.max(Math.max(z - light.z(), 0), light.z() - (z + 16));
            return dx * dx + dy * dy + dz * dz < light.definition().radius() * light.definition().radius();
        }).sorted(java.util.Comparator.comparingDouble(light -> light.distanceSquared(x + 8, y + 8, z + 8)))
                .limit(LightSourceManager.PER_SECTION).toList();
    }

    private static final class Column {
        final int x, z;
        final LevelChunk chunk;
        int section;
        boolean dirty = true, ready;
        final List<LightSourceManager.Light> pending = new ArrayList<>();
        List<LightSourceManager.Light> lights = List.of();
        Column(int x, int z, LevelChunk chunk, int section) { this.x = x; this.z = z; this.chunk = chunk; this.section = section; }
    }
}
