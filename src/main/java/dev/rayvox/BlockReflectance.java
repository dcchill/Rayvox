package dev.rayvox;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Resource-lifetime, texture-derived voxel albedo; biome tints are resolved at each block's position. */
final class BlockReflectance {
    record Surface(int rgb, int tintIndex) {}
    record ModelColor(int rgb, List<Surface> surfaces) {}
    private static final ModelColor FALLBACK = new ModelColor(-1, List.of());
    private static final Direction[] FACES = Direction.values();
    private final Map<BlockState, ModelColor> models = new IdentityHashMap<>();
    private final TextureColorCache sprites = new TextureColorCache();

    int resolve(BlockState state, BlockGetter level, BlockPos position) {
        // Non-client block views cannot supply resource-loaded models or biome tints.
        if (!(level instanceof BlockAndTintGetter world)) return state.getMapColor(level, position).col;
        try {
            var color = models.get(state);
            if (color == null) {
                color = sample(Minecraft.getInstance().getBlockRenderer().getBlockModel(state), state);
                models.put(state, color);
            }
            if (color.rgb >= 0) return color.rgb;
            if (!color.surfaces.isEmpty())
                return average(color.surfaces, Minecraft.getInstance().getBlockColors(), state, world, position);
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("Rayvox texture reflectance unavailable for {}; using map color", state, failure);
            models.put(state, FALLBACK);
        }
        return state.getMapColor(level, position).col;
    }

    ModelColor sample(BakedModel model, BlockState state) {
        var surfaces = new ArrayList<Surface>();
        var random = RandomSource.create(42);
        boolean tinted = false;
        // shortcut: one average per voxel omits face direction and position-specific model data; use per-face albedo for finer GI.
        for (int face = 0; face < 7 && surfaces.size() < 64; face++) {
            random.setSeed(42);
            for (var quad : model.getQuads(state, face == 6 ? null : FACES[face], random, ModelData.EMPTY, null)) {
                var sprite = quad.getSprite();
                if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) continue;
                int rgb = sprites.get(sprite).albedo();
                if (rgb < 0) continue;
                surfaces.add(new Surface(rgb, quad.getTintIndex()));
                tinted |= quad.isTinted();
                if (surfaces.size() == 64) break;
            }
        }
        if (surfaces.isEmpty()) {
            var sprite = model.getParticleIcon(ModelData.EMPTY);
            if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) return FALLBACK;
            return new ModelColor(sprites.get(sprite).albedo(), List.of());
        }
        return tinted ? new ModelColor(-1, List.copyOf(surfaces))
                : new ModelColor(average(surfaces, null, state, null, null), List.of());
    }

    static int average(List<Surface> surfaces, BlockColors colors, BlockState state,
            BlockAndTintGetter world, BlockPos position) {
        double red = 0, green = 0, blue = 0;
        for (var surface : surfaces) {
            int tint = surface.tintIndex < 0 ? -1 : colors.getColor(state, world, position, surface.tintIndex);
            // The terrain shader multiplies texture and vertex tint in sRGB before converting to linear light.
            red += LightDefinition.linear((surface.rgb >> 16 & 255) / 255f * ((tint >> 16 & 255) / 255f));
            green += LightDefinition.linear((surface.rgb >> 8 & 255) / 255f * ((tint >> 8 & 255) / 255f));
            blue += LightDefinition.linear((surface.rgb & 255) / 255f * ((tint & 255) / 255f));
        }
        return TextureColor.channel(red / surfaces.size()) << 16 | TextureColor.channel(green / surfaces.size()) << 8
                | TextureColor.channel(blue / surfaces.size());
    }
}
