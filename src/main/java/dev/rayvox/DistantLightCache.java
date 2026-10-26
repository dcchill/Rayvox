package dev.rayvox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Sparse emitter-only cache for loaded render-distance chunks; no distant voxel allocation. */
final class DistantLightCache {
    private static final int PER_CHUNK = 128;
    private final Map<Long, Column> columns = new HashMap<>();
    private final DistantLightIndex index = new DistantLightIndex();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    long revision;

    void tick(ClientLevel level, int cameraX, int cameraZ, int distance, LightColors colors) {
        int cx = Math.floorDiv(cameraX, 16), cz = Math.floorDiv(cameraZ, 16);
        int radius = Math.clamp(distance, 2, 32);
        boolean removed = columns.entrySet().removeIf(entry -> {
            var column = entry.getValue();
            boolean stale = Math.abs(column.x - cx) > radius || Math.abs(column.z - cz) > radius;
            if (stale) index.remove(column.x, column.z);
            return stale;
        });
        if (removed) revision++;
        Column next = null;
        int best = Integer.MAX_VALUE;
        for (int z = cz - radius; z <= cz + radius; z++) for (int x = cx - radius; x <= cx + radius; x++) {
            var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            long key = ChunkPos.asLong(x, z);
            var column = columns.get(key);
            // Poll each in-range column once for both discovery and missed unload/replacement events.
            if (column != null && column.chunk != chunk) {
                columns.remove(key);
                index.remove(x, z);
                revision++;
                column = null;
            }
            if (chunk == null) continue;
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
                extract(level, section, next.x, sy, next.z, colors, next.pending);
                if (System.nanoTime() >= deadline) return;
            }
            boolean changed = index.commit(next.x, next.z, next.pending);
            next.pending.clear();
            next.dirty = false;
            if (!next.ready || changed) revision++;
            next.ready = true;
            if (System.nanoTime() >= deadline) return;
            next = null;
            best = Integer.MAX_VALUE;
            for (var column : columns.values()) {
                int priority = (column.x - cx) * (column.x - cx) + (column.z - cz) * (column.z - cz);
                if (column.dirty && priority < best) { next = column; best = priority; }
            }
        }
    }

    void extract(net.minecraft.world.level.BlockGetter level, net.minecraft.world.level.chunk.LevelChunkSection section,
            int sx, int sy, int sz, LightColors colors, List<LightSourceManager.Light> pending) {
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            if (pending.size() >= PER_CHUNK) return;
            var state = section.getBlockState(x, y, z);
            cursor.set(sx * 16 + x, sy * 16 + y, sz * 16 + z);
            int emission = state.getLightEmission(level, cursor);
            if (emission <= 0) continue;
            var definition = colors.resolve(state, emission);
            if (!definition.contributes()) continue;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            pending.add(new LightSourceManager.Light(id, cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5, definition));
        }
    }

    String status() {
        long pending = columns.values().stream().filter(column -> column.dirty).count();
        return "Distant RGB: " + (columns.size() - pending) + " chunks ready; " + pending + " pending";
    }

    void changed(int x, int z, int minSection, boolean unload) {
        var column = columns.get(ChunkPos.asLong(x, z));
        if (column == null) return;
        if (unload) { columns.remove(ChunkPos.asLong(x, z)); index.remove(x, z); revision++; return; }
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

    List<LightSourceManager.Light> section(int x, int y, int z, LightSourceManager manager) {
        return index.section(x, y, z, manager);
    }

    static List<LightSourceManager.Light> select(List<LightSourceManager.Light> lights, int x, int y, int z) {
        return select(lights, x, y, z, LightSourceManager.Light::definition);
    }

    static List<LightSourceManager.Light> select(List<LightSourceManager.Light> lights, int x, int y, int z,
            java.util.function.Function<LightSourceManager.Light, LightDefinition> definitions) {
        return DistantLightIndex.select(lights, x, y, z, definitions);
    }

    private static final class Column {
        final int x, z;
        final LevelChunk chunk;
        int section;
        boolean dirty = true, ready;
        final List<LightSourceManager.Light> pending = new ArrayList<>();
        Column(int x, int z, LevelChunk chunk, int section) { this.x = x; this.z = z; this.chunk = chunk; this.section = section; }
    }
}
