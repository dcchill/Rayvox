package dev.rayvox;

import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Texture IDs, independent of voxel transmission material IDs. */
final class SurfaceMaterials {
    record Material(float metallic, float roughness, float reflectivity) {}
    static final Material MATTE = new Material(0, 1, 0);
    final Map<String, Material> textures;

    private SurfaceMaterials(Map<String, Material> textures) { this.textures = Map.copyOf(textures); }

    static SurfaceMaterials read(Reader reader) {
        var json = JsonParser.parseReader(reader).getAsJsonObject();
        if (json.size() > 4096) throw new IllegalArgumentException("Too many surface texture IDs");
        var textures = new HashMap<String, Material>();
        for (var entry : json.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid surface texture ID");
            var value = entry.getValue().getAsJsonObject();
            float metallic = value.get("metallic").getAsFloat(), roughness = value.get("roughness").getAsFloat();
            float reflectivity = value.has("reflectivity") ? value.get("reflectivity").getAsFloat() : 0;
            if (!Float.isFinite(reflectivity) || reflectivity < 0 || reflectivity > 1)
                throw new IllegalArgumentException("Surface reflectivity must be in [0, 1]");
            if (!Float.isFinite(metallic) || !Float.isFinite(roughness)
                    || metallic < 0 || metallic > 1 || roughness < 0 || roughness > 1)
                throw new IllegalArgumentException("Surface metallic and roughness must be in [0, 1]");
            textures.put(entry.getKey(), new Material(metallic, Math.max(0.08f, roughness), reflectivity));
        }
        return new SurfaceMaterials(textures);
    }

    Material get(String texture) { return textures.getOrDefault(texture, MATTE); }
}
