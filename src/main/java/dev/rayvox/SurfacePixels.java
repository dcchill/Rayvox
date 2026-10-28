package dev.rayvox;

/** Sprite-local central differences. Input pixels use NativeImage's ABGR layout. */
final class SurfacePixels {
    private SurfacePixels() {}

    static byte[] generate(int width, int height, int[] pixels, SurfaceMaterials.Material material, int mip) {
        if (width < 1 || height < 1 || pixels.length != Math.multiplyExact(width, height))
            throw new IllegalArgumentException("Invalid surface sprite dimensions");
        byte[] result = new byte[Math.multiplyExact(pixels.length, 4)];
        float strength = 0.65f / (1 << Math.min(mip, 20));
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = y * width + x, pixel = pixels[i];
            float center = brightness(pixel);
            float dx = (height(pixels[y * width + Math.min(x + 1, width - 1)], center)
                    - height(pixels[y * width + Math.max(x - 1, 0)], center)) * strength;
            float dy = (height(pixels[Math.min(y + 1, height - 1) * width + x], center)
                    - height(pixels[Math.max(y - 1, 0) * width + x], center)) * strength;
            if ((pixel >>> 24) < 128) { dx = 0; dy = 0; }
            float length = (float)Math.sqrt(1 + dx * dx + dy * dy);
            result[i * 4] = (byte)Math.round(128 - dx / length * 127);
            result[i * 4 + 1] = (byte)Math.round(128 - dy / length * 127);
            result[i * 4 + 2] = (byte)Math.round(128 + 127 * (material.metallic() > 0 ? material.metallic() : -material.reflectivity()));
            result[i * 4 + 3] = (byte)Math.round(material.roughness() * 255);
        }
        return result;
    }

    private static float height(int pixel, float center) { return (pixel >>> 24) < 128 ? center : brightness(pixel); }
    private static float brightness(int pixel) {
        return ((pixel & 255) * 0.2126f + (pixel >> 8 & 255) * 0.7152f + (pixel >> 16 & 255) * 0.0722f) / 255;
    }
}
