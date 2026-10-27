package dev.rayvox;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/** At most four nearby players with an emissive block item in either hand; no world light edits. */
final class DynamicLights {
    record HeldLight(String type, LightDefinition definition) {}

    static HeldLight choose(ItemStack main, ItemStack off, BlockGetter world, BlockPos position, LightColors colors) {
        var selected = held(main, world, position, colors);
        return selected != null ? selected : held(off, world, position, colors);
    }

    private static HeldLight held(ItemStack item, BlockGetter world, BlockPos position, LightColors colors) {
        if (!(item.getItem() instanceof BlockItem block)) return null;
        var state = block.getBlock().defaultBlockState();
        int emission = state.getLightEmission(world, position);
        if (emission <= 0) return null;
        var definition = colors.resolve(state, emission);
        if (!definition.contributes()) return null;
        return new HeldLight("held:" + BuiltInRegistries.BLOCK.getKey(block.getBlock()), definition);
    }

    static List<LightSourceManager.Light> collect(ClientLevel level, Vec3 camera, float partialTick,
            LightColors colors) {
        return collect(level, camera, partialTick, colors, null);
    }

    static List<LightSourceManager.Light> collect(ClientLevel level, Vec3 camera, float partialTick,
            LightColors colors, LightingCulling culling) {
        var result = new ArrayList<LightSourceManager.Light>(4);
        for (var player : level.players()) {
            if (player.distanceToSqr(camera) > 32 * 32 || player.isSpectator()) continue;
            var source = choose(player.getMainHandItem(), player.getOffhandItem(), level, player.blockPosition(), colors);
            if (source == null) continue;
            var position = player.getPosition(partialTick).add(0, player.getEyeHeight() - 0.25, 0);
            if (result.size() == 4 && position.distanceToSqr(camera) >= result.getLast().distanceSquared(camera.x, camera.y, camera.z)) continue;
            var light = new LightSourceManager.Light(source.type(), position.x, position.y, position.z, source.definition());
            if (culling != null && !culling.light(light, source.definition())) continue;
            offer(result, light, camera.x, camera.y, camera.z);
        }
        return List.copyOf(result);
    }

    static void offer(List<LightSourceManager.Light> result, LightSourceManager.Light light, double x, double y, double z) {
        double distance = light.distanceSquared(x, y, z);
        int position = result.size();
        while (position > 0 && distance < result.get(position - 1).distanceSquared(x, y, z)) position--;
        if (position >= 4) return;
        if (result.size() == 4) result.removeLast();
        result.add(position, light);
    }
}
