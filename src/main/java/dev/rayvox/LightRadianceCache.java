package dev.rayvox;

import java.util.HashMap;
import java.util.Map;

/** Bounded linear-radiance conversion cache, owned by one resource-loaded terrain shader. */
final class LightRadianceCache {
    private final Map<LightDefinition, float[]> colors = new HashMap<>();

    float[] get(LightDefinition definition) {
        var cached = colors.get(definition);
        if (cached != null) return cached;
        if (colors.size() >= 1024) colors.clear();
        cached = new float[]{LightDefinition.linear(definition.red()) * definition.intensity(),
                LightDefinition.linear(definition.green()) * definition.intensity(),
                LightDefinition.linear(definition.blue()) * definition.intensity()};
        colors.put(definition, cached);
        return cached;
    }
}
