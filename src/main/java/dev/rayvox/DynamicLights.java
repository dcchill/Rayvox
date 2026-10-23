package dev.rayvox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** At most four nearby players with an emissive block item in either hand; no world light edits. */
final class DynamicLights {
    static List<LightSourceManager.Light> collect(ClientLevel level, Vec3 camera, float partialTick,
            LightColors colors) {
        var result = new ArrayList<LightSourceManager.Light>();
        for (var player : level.players()) {
            if (player.distanceToSqr(camera) > 32 * 32 || player.isSpectator()) continue;
            ItemStack item = player.getMainHandItem();
            if (!(item.getItem() instanceof BlockItem main)
                    || main.getBlock().defaultBlockState().getLightEmission(level, player.blockPosition()) <= 0)
                item = player.getOffhandItem();
            if (!(item.getItem() instanceof BlockItem block)) continue;
            int emission = block.getBlock().defaultBlockState().getLightEmission(level, player.blockPosition());
            if (emission <= 0) continue;
            String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            var def = colors.resolve(block.getBlock().defaultBlockState(), emission);
            var position = player.getPosition(partialTick).add(0, player.getEyeHeight() - 0.25, 0);
            result.add(new LightSourceManager.Light("held:" + id, position.x, position.y, position.z, def));
        }
        result.sort(Comparator.comparingDouble(light -> light.distanceSquared(camera.x, camera.y, camera.z)));
        return List.copyOf(result.subList(0, Math.min(4, result.size())));
    }
}
