float historyNormalKey(vec3 normal) {
    // Preserve the common axis-aligned keys and their inexpensive path.
    float axis = abs(normal.x) == 1.0 ? (normal.x > 0.0 ? 1.0 : 2.0)
            : abs(normal.y) == 1.0 ? (normal.y > 0.0 ? 3.0 : 4.0)
            : abs(normal.z) == 1.0 ? (normal.z > 0.0 ? 5.0 : 6.0) : 0.0;
    if (axis > 0.0 || dot(normal, normal) < 0.999) return axis;
    vec2 oct = normal.xy / (abs(normal.x) + abs(normal.y) + abs(normal.z));
    if (normal.z < 0.0)
        oct = (1.0 - abs(oct.yx)) * mix(vec2(-1.0), vec2(1.0), greaterThanEqual(oct, vec2(0.0)));
    vec2 encoded = round((oct * 0.5 + 0.5) * 2047.0);
    // A 22-bit octahedral direction plus the reserved axis/invalid values fits exactly in RGBA32F.
    return 7.0 + encoded.x + encoded.y * 2048.0;
}

// Inverse of historyNormalKey: axis keys 1..6, 22-bit octahedral keys from 7, optional sun-colour tag.
vec3 historyNormal(float key) {
    if (key >= 8388608.0) key = float(uint(key) & 7u);
    if (key < 0.5) return vec3(0.0);
    if (key < 6.5) {
        int axis = int(key + 0.5);
        return axis == 1 ? vec3(1, 0, 0) : axis == 2 ? vec3(-1, 0, 0) : axis == 3 ? vec3(0, 1, 0)
                : axis == 4 ? vec3(0, -1, 0) : axis == 5 ? vec3(0, 0, 1) : vec3(0, 0, -1);
    }
    float encoded = key - 7.0;
    vec2 oct = vec2(mod(encoded, 2048.0), floor(encoded / 2048.0)) / 2047.0 * 2.0 - 1.0;
    vec3 normal = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (normal.z < 0.0)
        normal.xy = (1.0 - abs(normal.yx)) * mix(vec2(-1.0), vec2(1.0), greaterThanEqual(normal.xy, vec2(0.0)));
    return normalize(normal);
}
