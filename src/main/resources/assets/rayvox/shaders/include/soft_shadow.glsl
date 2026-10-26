uniform int EntityCount;
uniform vec4 EntityRecords[32];
uniform int ShadowSoftness;

float entityVisibility(vec3 origin, vec3 target) {
    float visibility = 1.0;
    int count = clamp(EntityCount, 0, 16);
    for (int i = 0; i < count; i++) {
        vec4 caster = EntityRecords[i * 2];
        if (caster.w <= 0.0) continue;
        vec3 radius = EntityRecords[i * 2 + 1].xyz;
        vec3 start = (origin - caster.xyz) / radius;
        vec3 end = (target - caster.xyz) / radius;
        // Held lights inside their owner's volume must still illuminate outwards.
        if (dot(end, end) <= 1.001) continue;
        vec3 ray = end - start;
        float a = dot(ray, ray);
        if (a < 0.000001) continue;
        float b = dot(start, ray);
        float discriminant = b * b - a * (dot(start, start) - 1.0);
        if (discriminant <= 0.0) continue;
        float root = sqrt(discriminant);
        if ((-b + root) / a > 0.001 && (-b - root) / a < 0.999)
            visibility = min(visibility, 1.0 - caster.w);
        if (visibility <= 0.0) break;
    }
    return visibility;
}

vec3 areaOffset(vec3 ray, int sampleIndex, float radius) {
    float distance = length(ray);
    if (distance < 0.0001) return vec3(0.0);
    vec3 direction = ray / distance;
    vec3 tangent = normalize(cross(abs(direction.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0), direction));
    vec3 bitangent = cross(direction, tangent);
    vec2 corner = vec2((sampleIndex & 1) == 0 ? -0.7071 : 0.7071,
            (sampleIndex & 2) == 0 ? -0.7071 : 0.7071);
    return (tangent * corner.x + bitangent * corner.y) * radius;
}

vec3 softTransmission(vec3 origin, vec3 target) {
    int samples = ShadowSoftness > 0 ? 4 : 1;
    vec3 camera = CameraFraction + vec3(CameraInCache);
    vec3 result = vec3(0.0);
    // Uniform loop bounds avoid duplicating the full voxel tracer four times on eager-unrolling drivers.
    for (int i = 0; i < samples; i++) {
        vec3 light = target + (samples > 1 ? areaOffset(target - origin, i, float(ShadowSoftness) * 0.035) : vec3(0.0));
        result += traceTransmission(origin, light, false) * entityVisibility(origin - camera, light - camera);
    }
    return result / float(samples);
}

#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
vec3 softDirectionalTransmission(vec3 origin, vec3 target, vec3 camera, float range) {
    int samples = ShadowSoftness > 0 ? 4 : 1;
    vec3 result = vec3(0.0);
    for (int i = 0; i < samples; i++) {
        vec3 light = target + (samples > 1 ? areaOffset(target - origin, i,
                length(target - origin) * float(ShadowSoftness) * 0.001) : vec3(0.0));
        result += traceDirectionalTransmission(origin, light, camera, range)
                * entityVisibility(origin - camera, light - camera);
    }
    return result / float(samples);
}
#endif
