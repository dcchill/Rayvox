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
    record Caster(int entityId, AABB bounds, List<PlayerShadowCapture.Part> parts) {}
    static final int MAX_CASTERS = 16;
    static final double RANGE = 32;

    static List<Caster> collect(ClientLevel level, Vec3 camera, float partialTick) {
        return collect(level, camera, partialTick, null, DirectionalLight.OFF, 0, 0);
    }

    static List<Caster> collect(ClientLevel level, Vec3 camera, float partialTick, LightingCulling culling,
            DirectionalLight sun, double sunReach, double pointReach) {
        var candidates = new ArrayList<Caster>();
        for (var entity : level.entitiesForRendering()) {
            if (!entity.isAlive() || entity.isInvisible() || entity.isSpectator()
                    || !(entity instanceof LivingEntity || entity.isPickable())) continue;
            var bounds = entity.getBoundingBox().move(entity.getPosition(partialTick).subtract(entity.position()));
            if (bounds.getCenter().distanceToSqr(camera) >= RANGE * RANGE) continue;
            if (culling != null && !culling.shadowCaster(entity instanceof net.minecraft.client.player.AbstractClientPlayer ? bounds.inflate(1) : bounds, sun, sunReach, pointReach)) continue;
            offer(candidates,new Caster(entity.getId(),bounds,List.of()),camera);
        }
        var result=new ArrayList<Caster>();
        for(int i=0;i<candidates.size();i++) {
            var candidate=candidates.get(i);var bounds=candidate.bounds();
            var entity=level.getEntity(candidate.entityId());
            if(entity==null)continue;
            var parts=i<MAX_CASTERS && entity instanceof net.minecraft.client.player.AbstractClientPlayer player
                    ? PlayerShadowCapture.collect(player,camera,partialTick) : List.<PlayerShadowCapture.Part>of();
            if(i<MAX_CASTERS && entity instanceof net.minecraft.client.player.AbstractClientPlayer && parts.isEmpty())continue;
            for(var part:parts)bounds=bounds.minmax(part.bounds().move(camera));
            result.add(new Caster(candidate.entityId(),bounds,parts));
        }
        return List.copyOf(result);
    }

    static void offer(List<Caster> result, Caster caster, Vec3 camera) {
        result.add(caster);
        result.sort(Comparator.comparingDouble(value -> value.bounds().getCenter().distanceToSqr(camera)));
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
