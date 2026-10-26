package dev.rayvox;

import java.util.List;
import net.minecraft.world.phys.AABB;
import static dev.rayvox.WorldLightingCache.Channel.*;

/** Phase 2 shadow bookkeeping only: no GPU dispatch and no terrain sampling until acceptance measurements. */
final class WorldLightingState implements AutoCloseable {
    static final int MAX_BRICKS = 729;
    static final long PAYLOAD_BUDGET = 8L * 1024 * 1024;
    final WorldLightingCache cache = new WorldLightingCache(MAX_BRICKS, PAYLOAD_BUDGET, 4);
    final LightingUpdateScheduler scheduler = new LightingUpdateScheduler(cache);
    private long geometryRevision = -1, emitterRevision = -1, definitionRevision = -1;
    private int sunEpoch = -1;
    private List<LightSourceManager.Light> dynamic = List.of(), testLights = List.of();
    private List<AABB> casters = List.of();

    void update(VoxelGrid grid, long emitters, LightSourceManager lights,
            List<LightSourceManager.Light> moving, DirectionalLight sun, List<AABB> entityCasters) {
        if (geometryRevision != grid.snapshotRevision) {
            // Only committed uploaded sections can acquire residency. Never retain mutable extraction buffers.
            for (var slot : grid.slots) if (slot.uploaded)
                cache.touch(new WorldLightingCache.Region(slot.x, slot.y, slot.z));
            // Without recorded ray dependencies, a caster can affect any resident receiver, including the sun.
            // Recenter/unknown transitions also revoke old results; unknown is not an empty section.
            geometryUnavailable();
            geometryRevision = grid.snapshotRevision;
        }
        if (emitterRevision != emitters || definitionRevision != lights.definitionRevision() || !testLights.equals(lights.testLights)) {
            // This deliberately does not use the camera-dependent active light list as a world source revision.
            cache.invalidateAll(STATIC_DIRECT, INDIRECT);
            emitterRevision = emitters;
            definitionRevision = lights.definitionRevision();
            testLights = List.copyOf(lights.testLights);
        }
        if (!dynamic.equals(moving)) {
            invalidateDynamic(dynamic);
            invalidateDynamic(moving);
            // Indirect dependencies are not bounded by a direct-light radius yet.
            cache.invalidateAll(INDIRECT);
            dynamic = List.copyOf(moving);
        }
        if (sunEpoch != sun.epoch()) {
            cache.invalidateAll(DIRECTIONAL_VISIBILITY, INDIRECT);
            sunEpoch = sun.epoch();
        }
        if (!casters.equals(entityCasters)) {
            geometryUnavailable();
            casters = List.copyOf(entityCasters);
        }
        scheduler.advanceFrame();
    }

    private void invalidateDynamic(List<LightSourceManager.Light> sources) {
        for (var region : cache.regions()) {
            for (var source : sources) {
                double radius = source.definition().radius();
                double dx = Math.max(Math.max(region.x() * 16.0 - source.x(), 0), source.x() - (region.x() * 16.0 + 16));
                double dy = Math.max(Math.max(region.y() * 16.0 - source.y(), 0), source.y() - (region.y() * 16.0 + 16));
                double dz = Math.max(Math.max(region.z() * 16.0 - source.z(), 0), source.z() - (region.z() * 16.0 + 16));
                if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                    cache.invalidate(region, DYNAMIC);
                    break;
                }
            }
        }
    }

    /** Conservative fallback for geometry outside the committed near snapshot or missing dependencies. */
    void geometryUnavailable() { cache.invalidateAll(WorldLightingCache.Channel.values()); }

    String status() {
        return "World lighting (bookkeeping): " + cache.size() + "/" + cache.capacity() + " bricks; "
                + cache.residentPayloadBytes() / 1024 + " KiB stored / " + cache.reservedPayloadBytes() / 1024
                + " KiB reserved; " + scheduler.pendingCount() + " channel updates; producer OFF";
    }

    @Override public void close() { scheduler.close(); cache.close(); }
}