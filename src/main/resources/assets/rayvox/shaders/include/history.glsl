uniform mat4 HistoryClipMat;
// Previous camera relative to the current camera; world coordinates stay in double precision on the CPU.
uniform vec3 HistoryCameraOffset;

bool historyPixel(vec3 position, out ivec2 pixel) {
    pixel = ivec2(0);
    vec4 clip = HistoryClipMat * vec4(position - HistoryCameraOffset, 1.0);
    if (clip.w <= 0.0 || any(greaterThanEqual(abs(clip.xyz), vec3(clip.w)))) return false;
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    pixel = ivec2(uv * vec2(textureSize(HistoryKey, 0)));
    return true;
}

bool historySurface(vec4 prior, vec3 position, vec3 normal, float face, float footprint) {
    float priorFace = prior.w;
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    if (AdvancedMaterials != 0 && priorFace >= 8388608.0) priorFace = float(uint(priorFace) & 7u);
#endif
    vec3 delta = prior.xyz - position;
    // A strict plane/depth test plus the receiver block prevents reuse across edges and thin surfaces.
    return face > 0.0 && priorFace == face && abs(dot(delta, normal)) < 0.001
            && length(delta) <= min(0.1, max(0.00001, footprint * 1.5))
            && all(equal(ivec3(floor(prior.xyz - normal * 0.02)), ivec3(floor(position - normal * 0.02))));
}

bool historyStaticLight(vec3 origin, vec3 target, bool shadows) {
    if (all(equal(HistoryCameraOffset, vec3(0.0)))) return true;
    // Source-budget and cache-edge fades depend on camera distance; refresh instead of retaining stale weights.
    if (ShadowBudget.w > 0.0) return false;
    vec3 camera = CameraFraction + vec3(CameraInCache);
    vec3 previousCamera = camera + HistoryCameraOffset;
    float sourceRange = min(length(target - camera), length(target - previousCamera));
    float farSourceRange = max(length(target - camera), length(target - previousCamera));
    float range = float(CacheWidth / 2) * 16.0;
    if (FarReady != 0 && farSourceRange > max(0.0, range - 16.0) && sourceRange < range) return false;
    if (!shadows) return true;
    float current = max(length(origin - camera), length(target - camera));
    float previous = max(length(origin - previousCamera), length(target - previousCamera));
    return max(current, previous) <= ShadowDrawDistance * 0.5
            || min(current, previous) >= ShadowDrawDistance;
}
