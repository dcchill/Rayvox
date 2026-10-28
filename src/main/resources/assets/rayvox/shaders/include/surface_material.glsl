#ifndef RAYVOX_SURFACE_MATERIAL
#define RAYVOX_SURFACE_MATERIAL

// Four exact 16-bit integers in RGBA32F; geometric normals remain in the history key.
float surfacePair(vec2 value) {
    uvec2 bytes = uvec2(round(clamp(value, 0.0, 1.0) * 255.0));
    return float(bytes.x | (bytes.y << 8u));
}
vec2 surfaceUnpair(float value) {
    uint bits = uint(value + 0.5);
    return vec2(bits & 255u, (bits >> 8u) & 255u) / 255.0;
}
vec2 surfaceOct(vec3 normal) {
    normal /= abs(normal.x) + abs(normal.y) + abs(normal.z);
    vec2 oct = normal.xy;
    if (normal.z < 0.0) oct = (1.0 - abs(oct.yx)) * mix(vec2(-1), vec2(1), greaterThanEqual(oct, vec2(0)));
    return oct * 0.5 + 0.5;
}
vec3 surfaceUnoct(vec2 oct) {
    oct = oct * 2.0 - 1.0;
    vec3 normal = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (normal.z < 0.0) normal.xy = (1.0 - abs(normal.yx)) * mix(vec2(-1), vec2(1), greaterThanEqual(normal.xy, vec2(0)));
    normal = normalize(normal);
    // Preserve flat block faces exactly despite octahedral byte quantization.
    if (max(abs(normal.x), max(abs(normal.y), abs(normal.z))) > 0.99998) normal = round(normal);
    return normal;
}
// Signed response: positive metals, negative dielectric reflectivity, zero matte.
float surfaceResponse(float encoded) { return (encoded * 255.0 - 128.0) / 127.0; }
float surfaceDiffuseWeight(float response) {
    // Finite voxel rays omit the distant environment; keep metals readable when reflections miss.
    return 1.0 - 0.25 * max(response, 0.0);
}
vec4 packSurface(vec4 albedo, vec3 normal, vec2 material) {
    material.x = (128.0 + 127.0 * material.x) / 255.0;
    return vec4(surfacePair(albedo.rg), surfacePair(albedo.ba), surfacePair(surfaceOct(normal)), surfacePair(material));
}
vec4 surfaceAlbedo(vec4 data) { return vec4(surfaceUnpair(data.r), surfaceUnpair(data.g)); }
vec3 surfaceDirection(vec4 data) { return surfaceUnoct(surfaceUnpair(data.b)); }
vec2 surfaceProperties(vec4 data) { vec2 material = surfaceUnpair(data.a); material.x = surfaceResponse(material.x); return material; }

vec3 textureSurfaceNormal(vec3 geometric, vec3 position, vec2 uv, vec2 encoded) {
    vec3 px = dFdx(position), py = dFdy(position);
    vec2 ux = dFdx(uv), uy = dFdy(uv);
    float determinant = ux.x * uy.y - ux.y * uy.x;
    if (abs(determinant) < 1e-12) return geometric;
    vec3 tangent = (px * uy.y - py * ux.y) / determinant;
    vec3 bitangent = (py * ux.x - px * uy.x) / determinant;
    tangent -= geometric * dot(geometric, tangent);
    bitangent -= geometric * dot(geometric, bitangent);
    if (dot(tangent, tangent) < 1e-12 || dot(bitangent, bitangent) < 1e-12) return geometric;
    vec2 xy = clamp((encoded * 255.0 - 128.0) / 127.0, -1.0, 1.0);
    return normalize(normalize(tangent) * xy.x + normalize(bitangent) * xy.y
            + geometric * sqrt(max(1.0 - dot(xy, xy), 0.001)));
}
#endif
