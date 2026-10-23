package dev.rayvox;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonParser;

/** Colors are sRGB; radius is blocks and intensity scales linear radiance. */
public record LightDefinition(float red, float green, float blue, float intensity, float radius, boolean shadows) {
    public LightDefinition {
        if (!Float.isFinite(red + green + blue + intensity + radius) || red < 0 || red > 1
                || green < 0 || green > 1 || blue < 0 || blue > 1 || intensity < 0 || intensity > 8
                || radius < 1 || radius > 16) throw new IllegalArgumentException("Invalid RGB light definition");
    }

    public static float linear(float value) {
        return value <= 0.04045f ? value / 12.92f : (float)Math.pow((value + 0.055f) / 1.055f, 2.4);
    }

    public static Map<String, LightDefinition> read(Reader reader) {
        var result = new HashMap<String, LightDefinition>();
        var json = JsonParser.parseReader(reader).getAsJsonObject();
        if (json.size() > 4096) throw new IllegalArgumentException("Too many light definitions");
        for (var entry : json.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid block ID");
            var value = entry.getValue().getAsJsonObject();
            var rgb = value.getAsJsonArray("color");
            if (rgb.size() != 3) throw new IllegalArgumentException("Light color needs three channels");
            result.put(entry.getKey(), new LightDefinition(rgb.get(0).getAsFloat(), rgb.get(1).getAsFloat(),
                    rgb.get(2).getAsFloat(), value.get("intensity").getAsFloat(), value.get("radius").getAsFloat(),
                    value.get("shadows").getAsBoolean()));
        }
        return Map.copyOf(result);
    }
}
