// Shared by the inline terrain tracer and the deferred lighting pass; keep both paths numerically identical.

#moj_import <rayvox:color.glsl>
#moj_import <rayvox:surface_normal.glsl>

// Default-zero rejection mask keeps standalone shader users and world-space GI independent of the view.
uniform ivec2 CulledLights;
bool lightCulled(int index) {
    return CullingDisabled == 0 && (uint(CulledLights[index / 32]) & (1u << uint(index % 32))) != 0u;
}

bool tileOccluded(uint header, int entry) {
    return CullingDisabled == 0 && (header & (1u << uint(16 + entry))) != 0u;
}

vec3 blockVisibility(vec3 origin, vec3 target, bool occluded) {
#ifdef RAYVOX_WORLD_PROBES
    // World irradiance has no camera fades, area-light jitter, or moving entity dependencies.
    return occluded || distance(origin, target) > ShadowDistance ? vec3(0.0) : traceTransmission(origin, target, false);
#else
    vec3 camera = CameraFraction + vec3(CameraInCache);
    // Both ends must fade before the section-aligned cache can drop a source or its ray.
    float cameraDistance = max(length(origin - camera), length(target - camera));
    float fade = 1.0 - smoothstep(ShadowDrawDistance * 0.5, ShadowDrawDistance, cameraDistance);
    if (fade <= 0.0) return vec3(1.0);
    vec3 visibility = occluded || distance(origin, target) > ShadowDistance ? vec3(0.0) : softTransmission(origin, target);
    return mix(vec3(1.0), visibility, fade);
#endif
}

vec3 blockVisibility(vec3 origin, vec3 target) {
    return blockVisibility(origin, target, false);
}

// Source-budget and cache-range fades for a near static light. Matched records only correct a stable section light.
#ifndef RAYVOX_WORLD_PROBES
float staticWeight(vec3 target, bool farReady, bool matched) {
    float sourceDistance = length(target - CameraFraction - vec3(CameraInCache));
    float weight = 1.0;
    if (ShadowBudget.w > 0.0) {
        float boundary = length(ShadowBudget.xyz);
        weight = 1.0 - smoothstep(max(0.0, boundary - 8.0), boundary, sourceDistance);
    }
    if (farReady && !matched) {
        // Extra near sources in dense sections fade before the ring can drop them in a 16-block step.
        float range = float(CacheWidth / 2) * 16.0;
        weight *= 1.0 - smoothstep(max(0.0, range - 16.0), range, sourceDistance);
    }
    return weight;
}
#endif

// Unshadowed diffuse contribution of a cache-local light record, or zero when out of range / back-facing.
vec3 nearContribution(vec4 light, vec4 radiance, vec3 position, vec3 normal) {
    vec3 toLight = light.xyz - position;
    if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) return vec3(0.0);
    float distanceToLight = length(toLight);
    float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
    float diffuse = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
    if (falloff <= 0.0 || diffuse <= 0.0) return vec3(0.0);
    return radiance.rgb * (falloff * falloff * diffuse);
}

