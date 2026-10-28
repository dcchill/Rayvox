uniform int MetalReflections;

vec3 metalFresnel(vec3 albedo, float metallic, float cosine) {
    // Glass/ice reflect neutral light weakly, with a restrained grazing-angle increase.
    if (metallic < 0.0) return vec3(-metallic * (1.0 + 2.0 * pow(1.0 - clamp(cosine, 0.0, 1.0), 5.0)));
    return metallic * (albedo + (vec3(1.0) - albedo) * pow(1.0 - clamp(cosine, 0.0, 1.0), 5.0));
}

vec3 metalHighlight(vec3 normal, vec3 view, vec3 light, vec3 albedo, vec2 material) {
    float nl = max(dot(normal, light), 0.0), nv = max(dot(normal, view), 0.0);
    vec3 halfway = view + light;
    if (nl <= 0.0 || nv <= 0.0 || dot(halfway, halfway) < 1e-8) return vec3(0.0);
    halfway = normalize(halfway);
    float a = max(material.y * material.y, 0.0064), a2 = a * a;
    float nh = max(dot(normal, halfway), 0.0);
    float denominator = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / max(3.14159265 * denominator * denominator, 1e-7);
    float k = (material.y + 1.0) * (material.y + 1.0) / 8.0;
    float geometry = nv / (nv * (1.0 - k) + k) * nl / (nl * (1.0 - k) + k);
    return metalFresnel(albedo, material.x, dot(view, halfway)) * distribution * geometry / max(4.0 * nv, 0.001);
}

vec3 surfaceReflection(vec3 position, vec3 geometric, vec3 normal, vec3 albedo, vec2 material) {
    if (MetalReflections == 0 || material.x == 0.0) return vec3(0.0);
    vec3 camera = CameraFraction + vec3(CameraInCache);
    vec3 relative = position - camera;
    vec3 view = -relative / max(length(relative), 0.0001);
    vec3 origin = position + geometric * 0.03;
    vec3 result = vec3(0.0);
    ivec3 cell = ivec3(floor(position - geometric * 0.02));
    if (voxelAt(cell) >= 0) {
        ivec3 slot = cacheSlot(cell);
        int row = slot.x + CacheWidth * (slot.y + CacheWidth * slot.z);
        ivec3 tile = (cell % 16) / 4;
        int column = (tile.x + 4 * (tile.y + 4 * tile.z)) * 9;
        uint header = texelFetch(LightLists, ivec2(column, row), 0).r;
        for (int i = 0; i < 8; i++) {
            if (i >= int(header & 255u)) break;
            int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
            if (lightCulled(index)) continue;
            vec4 light = texelFetch(LightData, ivec2(0, index), 0);
            vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
            vec3 toLight = light.xyz - position;
            float distance = length(toLight);
            if (distance >= light.w || dot(geometric, toLight) <= 0.0) continue;
            vec3 highlight = metalHighlight(normal, view, toLight / max(distance, 0.0001), albedo, material);
            if (all(equal(highlight, vec3(0.0)))) continue;
            vec3 energy=highlight*radiance.rgb*pow(1.0-distance/max(light.w,0.001),2.0);
            // Below a fifth of a dark sRGB display step: avoid tracing an invisible glint.
            if(max(energy.r,max(energy.g,energy.b))<1.0/16384.0)continue;
            vec3 visibility = ShadowsEnabled != 0 && radiance.w > 0.5
                    ? blockVisibilityCurrent(origin, light.xyz, tileOccluded(header, i)) : vec3(1.0);
            result += energy * visibility;
        }
    }
    for (int i = 0; i < 4; i++) {
        if (i >= DynamicCount) break;
        vec4 light = DynamicRecords[i * 2], radiance = DynamicRecords[i * 2 + 1];
        vec3 toLight = light.xyz - relative;
        float distance = length(toLight);
        if (distance >= light.w || dot(geometric, toLight) <= 0.0) continue;
        vec3 highlight = metalHighlight(normal, view, toLight / max(distance, 0.0001), albedo, material);
        if (all(equal(highlight, vec3(0.0)))) continue;
        vec3 energy=highlight*radiance.rgb*pow(1.0-distance/max(light.w,0.001),2.0);
        if(max(energy.r,max(energy.g,energy.b))<1.0/16384.0)continue;
        vec3 visibility = ShadowsEnabled != 0 && radiance.w > 0.5
                ? blockVisibilityCurrent(origin, light.xyz + camera) : vec3(1.0);
        result += energy * visibility;
    }
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    if (SunDirection.w > 0.0 && dot(geometric, SunDirection.xyz) > 0.0) {
        vec3 highlight = metalHighlight(normal, view, SunDirection.xyz, albedo, material);
        if (any(greaterThan(highlight, vec3(0.0)))) {
            vec3 visibility = softDirectionalTransmission(origin, origin + SunDirection.xyz * SunShadowDistance,
                    camera, SunShadowDistance);
            result += highlight * SunRadiance * SunDirection.w * visibility;
        }
    }
#endif
    vec3 reflected = reflect(-view, normal);
    vec3 tangent = normalize(cross(abs(reflected.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0), reflected));
    vec3 bitangent = cross(reflected, tangent);
    vec3 gathered = vec3(0.0);
    // Stratify area-light samples across reflection rays instead of tracing four at every hit.
    for (int i = 0; i < 4; i++) {
        float angle = (float(i) + 0.5) * 1.57079633;
        vec3 direction = normalize(reflected + material.y * material.y * 0.65
                * (tangent * cos(angle) + bitangent * sin(angle)));
        if (dot(direction, geometric) <= 0.0) continue;
        gathered += surfaceRay(origin, direction * 8.0, geometric, true, i);
    }
    return result + gathered * 0.25 * metalFresnel(albedo, material.x, dot(normal, view));
}

vec4 translucentSurfaceColor(vec4 base, vec3 reflection) {
    // Source-alpha blending needs coverage for reflections on otherwise clear texels.
    vec3 shine = min(toSrgb(max(reflection, vec3(0.0))), vec3(0.08));
    float coverage = max(shine.r, max(shine.g, shine.b));
    float alpha = base.a + (1.0 - base.a) * coverage;
    return vec4((base.rgb * base.a + shine) / max(alpha, 0.0001), alpha);
}
