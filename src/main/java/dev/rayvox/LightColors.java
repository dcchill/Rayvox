package dev.rayvox;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Client-thread color resolution shared by near, distant and held lights; replaced on resource reload. */
final class LightColors {
    private final Map<String, LightDefinition> explicit;
    private final Map<BlockState, Integer> generated = new IdentityHashMap<>();
    private int fallbacks;

    LightColors(Map<String, LightDefinition> explicit) { this.explicit = explicit; }

    LightDefinition resolve(BlockState state, int emission) {
        if (emission <= 0) throw new IllegalArgumentException("Only emissive states are light sources");
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var defined = explicit.get(id);
        if (defined != null) return defined;
        int rgb = generated.computeIfAbsent(state, this::sample);
        return new LightDefinition((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f,
                Math.clamp(emission / 15f, 0, 8), Math.clamp(emission, 1, 16), true);
    }

    private int sample(BlockState state) {
        try {
            var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            var sprites = new LinkedHashSet<TextureAtlasSprite>();
            var random = RandomSource.create(42);
            // shortcut: state-based model textures omit position-specific model data/tints; use lights.json for those emitters.
            for (int face = 0; face < 7 && sprites.size() < 16; face++) {
                random.setSeed(42);
                var quads = model.getQuads(state, face == 6 ? null : Direction.values()[face], random, ModelData.EMPTY, null);
                for (var quad : quads) {
                    sprites.add(quad.getSprite());
                    if (sprites.size() == 16) break;
                }
            }
            if (sprites.isEmpty()) sprites.add(model.getParticleIcon(ModelData.EMPTY));
            var color = new TextureColor();
            for (var sprite : sprites) {
                var contents = sprite.contents();
                if (contents.name().equals(MissingTextureAtlasSprite.getLocation())) continue;
                int[] frames = contents.getUniqueFrames().limit(4).toArray();
                int width = Math.min(contents.width(), 16), height = Math.min(contents.height(), 16);
                for (int frame : frames) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    color.add(sprite.getPixelRGBA(frame, x * contents.width() / width, y * contents.height() / height));
                }
            }
            if (!color.hasColor()) fallbacks++;
            return color.rgb();
        } catch (RuntimeException failure) {
            fallbacks++;
            LogUtils.getLogger().warn("Rayvox texture color unavailable for {}; using neutral light", state, failure);
            return 0xffffff;
        }
    }

    String status() { return "Texture light colors: " + generated.size() + " states cached; " + fallbacks + " neutral fallbacks"; }
}
