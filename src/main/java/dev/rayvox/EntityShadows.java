package dev.rayvox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Interpolated shadow volumes, independent of voxel extraction and entity render order. */
final class EntityShadows {
    static final int MAX_CASTERS = 16;
    static final double RANGE = 32;

    static List<AABB> collect(ClientLevel level, Vec3 camera, float partialTick) {
        var result = new ArrayList<AABB>();
        for (var entity : level.entitiesForRendering()) {
            if (!entity.isAlive() || entity.isInvisible() || entity.isSpectator()
                    || !(entity instanceof LivingEntity || entity.isPickable())) continue;
            var bounds = entity.getBoundingBox().move(entity.getPosition(partialTick).subtract(entity.position()));
            if (bounds.getCenter().distanceToSqr(camera) >= RANGE * RANGE) continue;
            offer(result, bounds, camera);
        }
        return List.copyOf(result);
    }

    static void offer(List<AABB> result, AABB bounds, Vec3 camera) {
        result.add(bounds);
        result.sort(Comparator.comparingDouble(box -> box.getCenter().distanceToSqr(camera)));
        // One lookahead volume lets the shader fade before a caster leaves the bounded set.
        if (result.size() > MAX_CASTERS + 1) result.removeLast();
    }

    static float weight(AABB bounds, List<AABB> casters, Vec3 camera) {
        double range = casters.size() > MAX_CASTERS
                ? Math.min(RANGE, casters.get(MAX_CASTERS).getCenter().distanceTo(camera)) : RANGE;
        // The transition is eight blocks wide, or the full range when very crowded.
        double t = Math.clamp((bounds.getCenter().distanceTo(camera) - Math.max(0, range - 8)) / Math.min(Math.max(range, 0.001), 8), 0, 1);
        return (float)(1 - t * t * (3 - 2 * t));
    }
}
