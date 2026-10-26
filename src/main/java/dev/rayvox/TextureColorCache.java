package dev.rayvox;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Resource-lifetime sprite aggregates, shared by generated colors for different block states. */
final class TextureColorCache {
    private final Map<TextureAtlasSprite, TextureColor> colors = new IdentityHashMap<>();

    TextureColor get(TextureAtlasSprite sprite) {
        var sampled = colors.get(sprite);
        if (sampled == null) {
            sampled = sample(sprite);
            colors.put(sprite, sampled);
        }
        return sampled;
    }

    static TextureColor sample(TextureAtlasSprite sprite) {
        var color = new TextureColor();
        var contents = sprite.contents();
        int[] frames = contents.getUniqueFrames().limit(4).toArray();
        int width = Math.min(contents.width(), 16), height = Math.min(contents.height(), 16);
        for (int frame : frames) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            color.add(sprite.getPixelRGBA(frame, x * contents.width() / width, y * contents.height() / height));
        return color;
    }
}
