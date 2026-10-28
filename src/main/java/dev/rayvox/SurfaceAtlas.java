package dev.rayvox;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Companion atlases follow the actual sprite uploads, including interpolated animation frames. */
public final class SurfaceAtlas {
    private static final Map<Integer, SurfaceAtlas> atlases = new HashMap<>();
    private static long revision;
    private static int blockAtlas;
    private int texture;
    private boolean failed;
    private final Map<String, Long> hashes = new HashMap<>();
    private SurfaceMaterials materials;

    private SurfaceAtlas() {}
    public static void blocks(int albedo) { blockAtlas = albedo; deleted(albedo); }
    public static long revision() { return revision; }
    public static int texture(int albedo) {
        var atlas = atlases.get(albedo);
        return atlas == null || atlas.failed ? 0 : atlas.texture;
    }

    public static void deleted(int albedo) {
        var atlas = atlases.remove(albedo);
        if (atlas != null) { glDeleteTextures(atlas.texture); revision++; }
    }

    public static void close() {
        for (var atlas : atlases.values()) glDeleteTextures(atlas.texture);
        atlases.clear(); revision++;
    }

    public static void upload(ResourceLocation name, int x, int y, int sx, int sy,
            int width, int height, NativeImage[] images) {
        int albedo = glGetInteger(GL_TEXTURE_BINDING_2D);
        if (albedo == 0 || albedo != blockAtlas) return;
        var atlas = atlases.computeIfAbsent(albedo, ignored -> new SurfaceAtlas());
        if (atlas.failed) return;
        int unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int alignment = glGetInteger(GL_UNPACK_ALIGNMENT), row = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int skipX = glGetInteger(GL_UNPACK_SKIP_PIXELS), skipY = glGetInteger(GL_UNPACK_SKIP_ROWS);
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1); glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0); glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            if (atlas.texture == 0) {
                int aw = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
                int ah = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
                if (aw < 1 || ah < 1 || (long)aw * ah * 4 * 4 / 3 > 128L * 1024 * 1024)
                    throw new IllegalStateException("Surface atlas exceeds 128 MiB");
                try (var reader = Minecraft.getInstance().getResourceManager().openAsReader(
                        ResourceLocation.fromNamespaceAndPath("rayvox", "surface_materials.json"))) {
                    atlas.materials = SurfaceMaterials.read(reader);
                }
                atlas.texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, atlas.texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, images.length > 1 ? GL_NEAREST_MIPMAP_LINEAR : GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, images.length - 1);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                for (int mip = 0; mip < images.length; mip++)
                    glTexImage2D(GL_TEXTURE_2D, mip, GL_RGBA8, Math.max(1, aw >> mip), Math.max(1, ah >> mip),
                            0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
            } else glBindTexture(GL_TEXTURE_2D, atlas.texture);
            var material = atlas.materials.get(name.toString());
            for (int mip = 0; mip < images.length && (width >> mip) > 0 && (height >> mip) > 0; mip++) {
                int w = width >> mip, h = height >> mip;
                int[] pixels = new int[w * h];
                for (int py = 0; py < h; py++) for (int px = 0; px < w; px++)
                    pixels[py * w + px] = images[mip].getPixelRGBA((sx >> mip) + px, (sy >> mip) + py);
                byte[] generated = SurfacePixels.generate(w, h, pixels, material, mip);
                var crc = new CRC32(); crc.update(generated);
                Long old = atlas.hashes.put(name + ":" + mip, crc.getValue());
                if (old != null && old == crc.getValue()) continue;
                ByteBuffer buffer = MemoryUtil.memAlloc(generated.length);
                try {
                    buffer.put(generated).flip();
                    glTexSubImage2D(GL_TEXTURE_2D, mip, x >> mip, y >> mip, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                } finally { MemoryUtil.memFree(buffer); }
                if (mip == 0) revision++;
            }
        } catch (Exception failure) {
            atlas.failed = true;
            glDeleteTextures(atlas.texture); atlas.texture = 0;
            LogUtils.getLogger().warn("Rayvox surface atlas unavailable; using flat matte surfaces", failure);
        } finally {
            glBindTexture(GL_TEXTURE_2D, albedo); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment); glPixelStorei(GL_UNPACK_ROW_LENGTH, row);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipX); glPixelStorei(GL_UNPACK_SKIP_ROWS, skipY);
        }
    }
}
