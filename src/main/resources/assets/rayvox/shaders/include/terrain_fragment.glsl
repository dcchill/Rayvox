#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float AlphaCutoff;
uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform int DebugMode;
uniform int CacheWidth;
uniform ivec3 CacheMinSlot;
uniform ivec3 CameraInCache;
uniform vec3 CameraFraction;
uniform sampler2D LightData;
uniform usampler2D LightLists;
uniform int ShadowsEnabled;
uniform float ShadowDistance;
uniform float ShadowDrawDistance;
uniform vec4 ShadowBudget;
uniform float SunShadowDistance;
uniform int FarLightCount;
uniform int FarReady;
uniform vec4 FarLightRecords[16];
uniform int HistoryValid;
uniform sampler2D HistoryColor;
uniform sampler2D HistoryKey;
uniform int DynamicCount;
uniform vec4 DynamicRecords[8];
uniform vec4 SunDirection;
uniform int SunHistoryValid;
uniform usampler3D VoxelShapes;
#ifdef RAYVOX_BASIC_MATERIALS
const int AdvancedMaterials = 0;
#else
uniform int AdvancedMaterials;
#endif
uniform vec3 MaterialTransmission[64];
#ifdef RAYVOX_NO_INDIRECT
const int IndirectEnabled = 0;
#else
uniform int IndirectEnabled;
#endif
uniform sampler2D HistoryIndirect;

#moj_import <rayvox:shadow.glsl>

in float vertexDistance;
in vec4 vertexColor;
in vec4 vanillaLight;
in vec4 skyLight;
in vec3 sourceLight;
in vec2 texCoord0;
in vec3 surfacePosition;
in vec3 surfaceNormal;

out vec4 fragColor;
out vec4 cachedLight;
out vec4 cachedKey;
out vec4 cachedIndirect;

vec3 toLinear(vec3 value) {
    return mix(value / 12.92, pow((value + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), value));
}

vec3 toSrgb(vec3 value) {
    return mix(value * 12.92, 1.055 * pow(value, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), value));
}

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

#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
float sunColorKey(float face, vec3 visibility, float mean) {
    vec2 chroma = mean > 0.0 ? visibility.rg / (3.0 * mean) : vec2(1.0 / 3.0);
    uvec2 encoded = uvec2(round(clamp(chroma, vec2(0.0), vec2(1.0)) * 1023.0));
    // Axis normal + two 10-bit chromaticity coordinates + tag: exactly 24 bits in RGBA32F.
    return float(8388608u | uint(face) | (encoded.x << 3u) | (encoded.y << 13u));
}

vec3 sunColorVisibility(float mean, float key) {
    uint encoded = uint(key);
    vec2 chroma = vec2((encoded >> 3u) & 1023u, (encoded >> 13u) & 1023u) / 1023.0;
    return clamp(vec3(chroma, max(1.0 - chroma.x - chroma.y, 0.0)) * (3.0 * mean), vec3(0.0), vec3(1.0));
}
#endif

#moj_import <rayvox:soft_shadow.glsl>

vec3 blockVisibility(vec3 origin, vec3 target) {
    vec3 camera = CameraFraction + vec3(CameraInCache);
    // Both ends must fade before the section-aligned cache can drop a source or its ray.
    float cameraDistance = max(length(origin - camera), length(target - camera));
    float fade = 1.0 - smoothstep(ShadowDrawDistance * 0.5, ShadowDrawDistance, cameraDistance);
    if (fade <= 0.0) return vec3(1.0);
    vec3 visibility = distance(origin, target) > ShadowDistance ? vec3(0.0) : softTransmission(origin, target);
    return mix(vec3(1.0), visibility, fade);
}

vec3 staticVisibility(vec3 origin, vec3 target, int index, bool shadows) {
    bool matched = false;
    if (FarReady != 0) {
        for (int i = 0; i < 8; i++) {
            if (i >= FarLightCount) break;
            if (int(FarLightRecords[i * 2 + 1].w) == index + 1) matched = true;
        }
    }
    float sourceDistance = length(target - CameraFraction - vec3(CameraInCache));
    float weight = 1.0;
    if (ShadowBudget.w > 0.0) {
        float boundary = length(ShadowBudget.xyz);
        weight = 1.0 - smoothstep(max(0.0, boundary - 8.0), boundary, sourceDistance);
    }
    if (FarReady != 0 && !matched) {
        // Extra near sources in dense sections fade before the ring can drop them in a 16-block step.
        float range = float(CacheWidth / 2) * 16.0;
        weight *= 1.0 - smoothstep(max(0.0, range - 16.0), range, sourceDistance);
    }
    if (weight <= 0.0) return vec3(0.0);
    vec3 visibility = shadows ? blockVisibility(origin, target) : vec3(1.0);
    // Ready section lights provide stable RGB; matching near records contribute only the shadow correction.
    return (FarReady != 0 && matched ? visibility - vec3(1.0) : visibility) * weight;
}

#moj_import <rayvox:indirect.glsl>

void main() {
    cachedLight = vec4(0.0);
    cachedKey = vec4(0.0);
    cachedIndirect = vec4(0.0);
    vec4 albedo = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    float alpha = albedo.a * vanillaLight.a;
    if (alpha < AlphaCutoff) discard;
    // Fully opaque fog replaces RGB completely; retain the alpha test and skip invisible lighting work.
    if (DebugMode == 0 && vertexDistance > FogStart && vertexDistance >= FogEnd && FogColor.a == 1.0) {
        fragColor = vec4(FogColor.rgb, alpha);
        return;
    }

    if (DebugMode == 1 || DebugMode == 2) {
        vec3 normal = surfaceNormal / max(length(surfaceNormal), 0.0001);
        ivec3 local = ivec3(floor(surfacePosition + CameraFraction - normal * 0.02)) + CameraInCache;
        vec3 diagnostic = vec3(0.5, 0.05, 0.5);
        if (all(greaterThanEqual(local, ivec3(0))) && all(lessThan(local, ivec3(CacheWidth * 16)))) {
            ivec3 sectionSlot = cacheSlot(local);
            uint state = texelFetch(VoxelState, sectionSlot, 0).r;
            bool valid = (state & 1u) != 0u;
            bool dirty = (state & 2u) != 0u;
            if (DebugMode == 2) {
                diagnostic = dirty ? vec3(1.0, 0.6, 0.0) : valid ? vec3(0.1, 0.65, 0.2) : diagnostic;
            } else if (valid) {
                uint material = texelFetch(VoxelData, sectionSlot * 16 + local % 16, 0).r & 63u;
                diagnostic = material == 1u ? vec3(0.05, 0.8, 0.7) : material == 2u ? vec3(1.0, 0.45, 0.1)
                        : material >= 3u ? vec3(0.15, 0.4, 1.0) : vec3(0.08);
                if (dirty) diagnostic = mix(diagnostic, vec3(1.0, 0.8, 0.0), 0.6);
            } else if (dirty) diagnostic = vec3(1.0, 0.6, 0.0);
        }
        vec3 cell = fract(surfacePosition + CameraFraction);
        vec3 edge = min(cell, 1.0 - cell) + abs(normal);
        float grid = min(edge.x, min(edge.y, edge.z)) < 0.035 ? 0.45 : 1.0;
        fragColor = vec4(diagnostic * grid, alpha);
        return;
    }

    vec3 normal = surfaceNormal / max(length(surfaceNormal), 0.0001);
    vec3 localPosition = surfacePosition + CameraFraction + vec3(CameraInCache);
    float face = historyNormalKey(normal);
    vec4 receiver = vec4(localPosition, face);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 prior = HistoryValid != 0 ? texelFetch(HistoryKey, pixel, 0) : vec4(0.0);
    float priorFace = prior.w;
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    if (AdvancedMaterials != 0 && priorFace >= 8388608.0) priorFace = float(uint(priorFace) & 7u);
#endif
    bool reused = HistoryValid != 0 && face > 0.0 && priorFace == face
            && all(lessThan(abs(prior.xyz - localPosition), vec3(0.00001)));
    ivec3 cell = ivec3(floor(localPosition - normal * 0.02));
    vec3 direct = reused ? texelFetch(HistoryColor, pixel, 0).rgb : vec3(0.0);
    float replacement = 0.0;
    if (all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(CacheWidth * 16)))) {
        ivec3 sectionSlot = cacheSlot(cell);
        int row = sectionSlot.x + CacheWidth * (sectionSlot.y + CacheWidth * sectionSlot.z);
        ivec3 tile = (cell % 16) / 4;
        int column = (tile.x + 4 * (tile.y + 4 * tile.z)) * 9;
        uint header = texelFetch(LightLists, ivec2(column, row), 0).r;
        if ((texelFetch(VoxelState, sectionSlot, 0).r & 1u) != 0u) {
            vec3 edge = min(localPosition, vec3(CacheWidth * 16) - localPosition);
            replacement = smoothstep(0.0, 16.0, min(edge.x, min(edge.y, edge.z)));
        }
        int count = int(header & 255u);
        for (int i = 0; i < 8 && !reused; i++) {
            if (i >= count) break;
            int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
            vec4 light = texelFetch(LightData, ivec2(0, index), 0);
            vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
            vec3 toLight = light.xyz - localPosition;
            if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
            float distanceToLight = length(toLight);
            float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
            float diffuse = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
            if (falloff <= 0.0 || diffuse <= 0.0) continue;
            vec3 visibility = staticVisibility(localPosition + normal * 0.03, light.xyz, index,
                    ShadowsEnabled != 0 && radiance.w > 0.5);
            direct += radiance.rgb * (falloff * falloff * diffuse * visibility);
        }
    }
    vec3 farDirect = vec3(0.0);
    if (!reused && FarReady != 0) {
        for (int i = 0; i < 8; i++) {
            if (i >= FarLightCount) break;
            vec4 light = FarLightRecords[i * 2];
            vec3 toLight = light.xyz - surfacePosition;
            if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
            float distanceToLight = length(toLight);
            float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
            float diffuse = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
            farDirect += FarLightRecords[i * 2 + 1].rgb * (falloff * falloff * diffuse);
        }
        direct = max(farDirect + direct, vec3(0.0));
    }
    float sunlight = 1.0;
    float sunFade = 0.0;
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    vec3 sunTransmission = vec3(1.0);
    float sunFacing = max(dot(normal, SunDirection.xyz), 0.0);
    sunFade = 1.0 - smoothstep(SunShadowDistance * 0.5, SunShadowDistance, length(surfacePosition));
    if (SunDirection.w > 0.0 && sunFade > 0.0 && sunFacing > 0.0) {
        bool axisFace = face > 0.0 && face <= 6.0;
        if (reused && SunHistoryValid != 0 && (AdvancedMaterials == 0 || axisFace)) {
            sunlight = texelFetch(HistoryColor, pixel, 0).a;
            sunTransmission = vec3(sunlight);
            if (AdvancedMaterials != 0 && prior.w >= 8388608.0) {
                sunTransmission = sunColorVisibility(sunlight, prior.w);
                receiver.w = prior.w;
            }
        } else {
            vec3 origin = localPosition + normal * 0.03;
            sunTransmission = softDirectionalTransmission(origin, origin + SunDirection.xyz * SunShadowDistance,
                    CameraFraction + vec3(CameraInCache), SunShadowDistance);
            sunlight = dot(sunTransmission, vec3(1.0 / 3.0));
            if (AdvancedMaterials != 0 && any(notEqual(sunTransmission, sunTransmission.rrr))) {
                if (axisFace) receiver.w = sunColorKey(face, sunTransmission, sunlight);
            } else sunTransmission = vec3(sunlight);
        }
    }
#endif
    cachedLight = vec4(direct, sunlight);
    cachedKey = receiver;
    vec3 indirect = vec3(0.0);
    if (IndirectEnabled != 0 && replacement > 0.0) {
        vec4 accumulated = reused ? texelFetch(HistoryIndirect, pixel, 0) : vec4(0.0);
        float count = clamp(accumulated.a, 0.0, 8.0);
        indirect = count >= 8.0 ? accumulated.rgb : (accumulated.rgb * count
                + bounceSample(localPosition + normal * 0.03, normal, int(count))) / (count + 1.0);
        cachedIndirect = vec4(indirect, min(count + 1.0, 8.0));
        indirect *= replacement;
    }
    // Moving lights stay outside static history; their four rays update every rendered frame.
    for (int i = 0; i < 4; i++) {
        if (i >= DynamicCount) break;
        vec4 light = DynamicRecords[i * 2];
        vec4 radiance = DynamicRecords[i * 2 + 1];
        vec3 toLight = light.xyz - surfacePosition;
        if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
        float distanceToLight = length(toLight);
        float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
        float diffuse = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
        vec3 visibility = vec3(1.0);
        if (ShadowsEnabled != 0 && radiance.w > 0.5 && falloff * diffuse > 0.0)
            visibility = blockVisibility(localPosition + normal * 0.03, light.xyz + CameraFraction + vec3(CameraInCache));
        direct += radiance.rgb * (falloff * falloff * diffuse * visibility);
    }
    if (DebugMode == 6) {
        fragColor = vec4(reused ? vec3(0.1, 0.8, 0.2) : vec3(0.8, 0.15, 0.05), alpha);
        return;
    }
    if (DebugMode == 7) {
        fragColor = vec4(toSrgb(indirect / (vec3(1.0) + indirect)), alpha);
        return;
    }
    if (DebugMode == 5) {
        fragColor = vec4(toSrgb(direct / (vec3(1.0) + direct)), alpha);
        return;
    }
    // Source budgets and dirty flags must never switch vanilla block lighting back on under RGB shadows.
    vec3 base = mix(toLinear(vanillaLight.rgb), toLinear(skyLight.rgb), FarReady != 0 ? 1.0 : replacement);
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    if (SunDirection.w > 0.0) {
        vec3 occlusion = (vec3(1.0) - sunTransmission) * sunFacing;
        base *= vec3(1.0) - 0.65 * occlusion * sunFade * SunDirection.w;
    }
#endif
    // A source's own glow is independent of diffuse normals, occlusion and source-list budgets.
    vec3 illumination = max(max(base, direct) + indirect, toLinear(sourceLight));
    vec3 lit = toSrgb(clamp(toLinear(albedo.rgb) * illumination, 0.0, 1.0));
    fragColor = linear_fog(vec4(lit, alpha), vertexDistance, FogStart, FogEnd, FogColor);
}
