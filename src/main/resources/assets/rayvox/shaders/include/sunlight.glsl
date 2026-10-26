#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
uniform vec3 SunRadiance;
#endif

vec3 sunlitBase(vec3 base, vec3 normal, vec3 transmission, float receiverDistance) {
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    if (SunDirection.w > 0.0) {
        float fade = 1.0 - smoothstep(SunShadowDistance * 0.5, SunShadowDistance, receiverDistance);
        float facing = max(dot(normal, SunDirection.xyz), 0.0);
        // The native sky light bounds exposure in caves; direct sunlight is separately traced.
        vec3 exposure = clamp(base * 3.0, vec3(0.0), vec3(1.0));
        vec3 daylight = base * 0.28 + exposure * SunRadiance * facing * transmission;
        return mix(base, daylight, fade * SunDirection.w);
    }
#endif
    return base;
}
