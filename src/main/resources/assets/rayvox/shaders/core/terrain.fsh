#version 150

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
uniform int AdvancedMaterials;
uniform vec3 MaterialTransmission[64];
uniform int IndirectEnabled;
uniform sampler2D HistoryIndirect;

#moj_import <rayvox:shadow.glsl>

in float vertexDistance;
in vec4 vertexColor;
in vec4 vanillaLight;
in vec4 skyLight;
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

#moj_import <rayvox:indirect.glsl>

void main() {
    cachedLight = vec4(0.0);
    cachedKey = vec4(0.0);
    cachedIndirect = vec4(0.0);
    vec4 albedo = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    float alpha = albedo.a * vanillaLight.a;
    if (alpha < AlphaCutoff) discard;

    if (DebugMode == 1 || DebugMode == 2) {
        vec3 normal = surfaceNormal / max(length(surfaceNormal), 0.0001);
        ivec3 local = ivec3(floor(surfacePosition + CameraFraction - normal * 0.02)) + CameraInCache;
        vec3 diagnostic = vec3(0.5, 0.05, 0.5);
        if (all(greaterThanEqual(local, ivec3(0))) && all(lessThan(local, ivec3(CacheWidth * 16)))) {
            ivec3 sectionSlot = (local / 16 + CacheMinSlot) % CacheWidth;
            uint state = texelFetch(VoxelState, sectionSlot, 0).r;
            bool valid = (state & 1u) != 0u;
            bool dirty = (state & 2u) != 0u;
            if (DebugMode == 2) {
                diagnostic = dirty ? vec3(1.0, 0.6, 0.0) : valid ? vec3(0.1, 0.65, 0.2) : diagnostic;
            } else if (valid) {
                uint material = texelFetch(VoxelData, sectionSlot * 16 + local % 16, 0).r;
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
    float face = abs(normal.x) == 1.0 ? (normal.x > 0.0 ? 1.0 : 2.0)
            : abs(normal.y) == 1.0 ? (normal.y > 0.0 ? 3.0 : 4.0)
            : abs(normal.z) == 1.0 ? (normal.z > 0.0 ? 5.0 : 6.0) : 0.0;
    vec4 receiver = vec4(localPosition, face);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 prior = HistoryValid != 0 ? texelFetch(HistoryKey, pixel, 0) : vec4(0.0);
    bool reused = HistoryValid != 0 && face > 0.0 && prior.w == face
            && all(lessThan(abs(prior.xyz - localPosition), vec3(0.00001)));
    ivec3 cell = ivec3(floor(localPosition - normal * 0.02));
    vec3 direct = reused ? texelFetch(HistoryColor, pixel, 0).rgb : vec3(0.0);
    float replacement = 0.0;
    if (all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(CacheWidth * 16)))) {
        ivec3 sectionSlot = (cell / 16 + CacheMinSlot) % CacheWidth;
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
            float distanceToLight = length(toLight);
            float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
            float diffuse = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
            if (falloff <= 0.0 || diffuse <= 0.0) continue;
            vec3 visibility = vec3(1.0);
            if (ShadowsEnabled != 0 && radiance.w > 0.5) {
                visibility = distanceToLight > ShadowDistance ? vec3(0.0) : traceTransmission(localPosition + normal * 0.03, light.xyz, false);
            }
            direct += radiance.rgb * (falloff * falloff * diffuse * visibility);
        }
    }
    vec3 farDirect = vec3(0.0);
    if (!reused && FarReady != 0 && replacement < 1.0) {
        for (int i = 0; i < 8; i++) {
            if (i >= FarLightCount) break;
            vec4 light = FarLightRecords[i * 2];
            vec3 toLight = light.xyz - surfacePosition;
            float distanceToLight = length(toLight);
            float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
            float diffuse = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
            farDirect += FarLightRecords[i * 2 + 1].rgb * (falloff * falloff * diffuse);
        }
        direct = mix(farDirect, direct, replacement);
    }
    float sunlight = 1.0;
    if (SunDirection.w > 0.0 && replacement > 0.0) {
        sunlight = reused && SunHistoryValid != 0 ? texelFetch(HistoryColor, pixel, 0).a
                : traceVisibility(localPosition + normal * 0.03,
                        localPosition + normal * 0.03 + SunDirection.xyz * ShadowDistance, true);
    }
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
        float distanceToLight = length(toLight);
        float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
        float diffuse = max(dot(normal, toLight / max(distanceToLight, 0.0001)), 0.0);
        vec3 visibility = vec3(1.0);
        if (ShadowsEnabled != 0 && radiance.w > 0.5 && falloff * diffuse > 0.0)
            visibility = distanceToLight > ShadowDistance ? vec3(0.0)
                    : traceTransmission(localPosition + normal * 0.03, light.xyz + CameraFraction + vec3(CameraInCache), false);
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
    if (SunDirection.w > 0.0) {
        float directional = 0.25 + 0.75 * max(dot(normal, SunDirection.xyz), 0.0) * sunlight;
        base *= mix(1.0, directional, replacement * SunDirection.w);
    }
    vec3 illumination = max(base, direct) + indirect;
    vec3 lit = toSrgb(clamp(toLinear(albedo.rgb) * illumination, 0.0, 1.0));
    fragColor = linear_fog(vec4(lit, alpha), vertexDistance, FogStart, FogEnd, FogColor);
}
