uniform sampler2D WorldIndirect;
uniform usampler2D WorldProbeState;
uniform int WorldIndirectEnabled;

// Trilinear irradiance interpolation, with per-probe validity and receiver-to-probe visibility.
bool worldIndirect(vec3 origin, vec3 normal, out vec3 result) {
    result = vec3(0.0);
    if (WorldIndirectEnabled == 0) return false;
    vec3 coordinate = (origin - vec3(2.0)) / 4.0;
    ivec3 base = ivec3(floor(coordinate));
    vec3 fraction = fract(coordinate);
    float total = 0.0;
    vec3 normalWeights = normal * normal;
    for (int i = 0; i < 8; i++) {
        ivec3 corner = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        ivec3 probe = base + corner;
        ivec3 cell = probe * 4 + 2;
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(CacheWidth * 16)))) continue;
        vec3 target = vec3(cell);
        // Only probes on the receiver's outward side can contribute.
        if (dot(target - origin, normal) <= 0.0) continue;
        ivec3 slot = cacheSlot(cell);
        int row = slot.x + CacheWidth * (slot.y + CacheWidth * slot.z);
        if (texelFetch(WorldProbeState, ivec2(0, row), 0).r == 0u) continue;
        ivec3 local = (cell % 16) / 4;
        int column = (local.x + 4 * (local.y + 4 * local.z)) * 6;
        vec4 sampleValue = vec4(0.0);
        bool known = true;
        if (normalWeights.x > 0.0) {
            vec4 value = texelFetch(WorldIndirect, ivec2(column + (normal.x >= 0.0 ? 0 : 1), row), 0);
            sampleValue.rgb += value.rgb * normalWeights.x;
            known = known && value.a > 0.5;
        }
        if (normalWeights.y > 0.0) {
            vec4 value = texelFetch(WorldIndirect, ivec2(column + (normal.y >= 0.0 ? 2 : 3), row), 0);
            sampleValue.rgb += value.rgb * normalWeights.y;
            known = known && value.a > 0.5;
        }
        if (normalWeights.z > 0.0) {
            vec4 value = texelFetch(WorldIndirect, ivec2(column + (normal.z >= 0.0 ? 4 : 5), row), 0);
            sampleValue.rgb += value.rgb * normalWeights.z;
            known = known && value.a > 0.5;
        }
        if (!known) continue;
        // A blocked path rejects the probe. Transmission attenuation avoids illuminating through glass for free.
        vec3 visibility = traceTransmission(origin, target, false);
        if (max(visibility.r, max(visibility.g, visibility.b)) <= 0.0) continue;
        vec3 weights = mix(vec3(1.0) - fraction, fraction, vec3(corner));
        float weight = weights.x * weights.y * weights.z;
        result += sampleValue.rgb * visibility * weight;
        total += weight;
    }
    if (total <= 0.0001) return false;
    result /= total;
    return true;
}
