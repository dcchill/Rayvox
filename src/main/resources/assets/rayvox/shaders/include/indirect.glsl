// Experimental one-bounce voxel gather; no scene-independent ambient color or screen tint.
vec3 bounceLighting(vec3 hit, vec3 normal, ivec3 cell) {
    ivec3 slot = cacheSlot(cell);
    int row = slot.x + CacheWidth * (slot.y + CacheWidth * slot.z);
    ivec3 tile = (cell % 16) / 4;
    int column = (tile.x + 4 * (tile.y + 4 * tile.z)) * 9;
    uint header = texelFetch(LightLists, ivec2(column, row), 0).r;
    int count = int(header & 255u);
    vec3 result = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        if (i >= count) break;
        int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
        vec4 light = texelFetch(LightData, ivec2(0, index), 0);
        vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
        vec3 ray = light.xyz - hit;
        if (dot(ray, ray) >= light.w * light.w || dot(normal, ray) <= 0.0) continue;
        float distance = length(ray);
        float falloff = max(1.0 - distance / max(light.w, 0.001), 0.0);
        float diffuse = max(dot(normal, ray / max(distance, 0.0001)), 0.0);
        if (falloff * diffuse <= 0.0) continue;
        vec3 visibility = vec3(1.0);
        if (ShadowsEnabled != 0 && radiance.w > 0.5)
            visibility = blockVisibility(hit + normal * 0.03, light.xyz, tileOccluded(header, i));
        result += radiance.rgb * falloff * falloff * diffuse * visibility;
    }
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    float sunFacing = max(dot(normal, SunDirection.xyz), 0.0);
    if (SunDirection.w > 0.0 && sunFacing > 0.0) {
        vec3 origin = hit + normal * 0.03;
#ifdef RAYVOX_WORLD_PROBES
        vec3 sunlight = traceTransmission(origin, origin + SunDirection.xyz * SunShadowDistance, true);
#else
        vec3 sunlight = traceDirectionalTransmission(origin, origin + SunDirection.xyz * SunShadowDistance,
                CameraFraction + vec3(CameraInCache), SunShadowDistance);
#endif
        result += SunRadiance * (SunDirection.w * sunFacing) * sunlight;
    }
#endif
    vec3 reflectance = toLinear(vec3(texelFetch(VoxelData, slot * 16 + cell % 16, 0).gba) / 255.0);
    return result * reflectance * 0.5;
}

vec3 bounceDirection(vec3 normal, int sampleIndex) {
    float u = (7.5 - float(sampleIndex)) / 8.0;
    float phi = float(sampleIndex) * 2.39996323;
    vec3 tangent = normalize(cross(abs(normal.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0), normal));
    return tangent * (sqrt(u) * cos(phi)) + cross(normal, tangent) * (sqrt(u) * sin(phi))
            + normal * sqrt(1.0 - u);
}

vec3 bounceSample(vec3 origin, vec3 normal, int sampleIndex) {
    vec3 ray = bounceDirection(normal, sampleIndex) * 6.0;
    ivec3 cell = ivec3(floor(origin));
    ivec3 stepDir = ivec3(sign(ray));
    vec3 delta = 1.0 / max(abs(ray), vec3(0.0000001));
    vec3 boundary = mix(origin - vec3(cell), vec3(cell + 1) - origin, greaterThan(ray, vec3(0.0)));
    vec3 next = boundary * delta;
    if (stepDir.x == 0) next.x = 1e20;
    if (stepDir.y == 0) next.y = 1e20;
    if (stepDir.z == 0) next.z = 1e20;
    float entry = 0.0;
    vec3 hitNormal = -normal;
    vec3 throughput = vec3(1.0);
    for (int step = 0; step < 32; step++) {
        int voxel = voxelAt(cell);
        if (voxel < 0) return vec3(0.0);
        int material = voxel & 63;
        float nearest = min(next.x, min(next.y, next.z));
        float hitDistance = entry;
        vec3 subNormal = vec3(0.0);
        bool blocked = material == 1 || (material == 2 && (AdvancedMaterials == 0
                || shapeHit(cell, origin, ray, entry, min(nearest, 1.0), (voxel & 64) != 0, hitDistance, subNormal)));
        if (blocked) {
            if (dot(subNormal, subNormal) > 0.0) hitNormal = subNormal;
            return throughput * bounceLighting(origin + ray * hitDistance, hitNormal, cell);
        }
        if (AdvancedMaterials != 0 && (material == 3 || material >= 16)) {
            float distance = shapeThickness(cell, origin, ray, entry, min(nearest, 1.0), (voxel & 64) != 0) * length(ray);
            if (distance > 0.0) throughput *= pow(MaterialTransmission[min(material, 63)], vec3(distance));
            if (all(equal(throughput, vec3(0.0)))) return vec3(0.0);
        }
        if (nearest >= 1.0) return vec3(0.0);
        bvec3 crossed = lessThanEqual(next, vec3(nearest + 0.0000001));
        if (crossed.x) { cell.x += stepDir.x; next.x += delta.x; hitNormal = vec3(-stepDir.x, 0, 0); }
        if (crossed.y) { cell.y += stepDir.y; next.y += delta.y; hitNormal = vec3(0, -stepDir.y, 0); }
        if (crossed.z) { cell.z += stepDir.z; next.z += delta.z; hitNormal = vec3(0, 0, -stepDir.z); }
        entry = nearest;
    }
    return vec3(0.0);
}
