#version 150

// Stage B of the deferred pipeline: one evaluation per visible new/stale pixel, after all opaque terrain.
uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform usampler3D VoxelShapes;
uniform sampler2D LightData;
uniform usampler2D LightLists;
uniform sampler2D PassKey;
uniform sampler2D PassInputs;
uniform int CacheWidth;
uniform ivec3 CacheMinSlot;
uniform ivec3 CameraInCache;
uniform vec3 CameraFraction;
uniform int ShadowsEnabled;
uniform float ShadowDistance;
uniform float ShadowDrawDistance;
uniform vec4 ShadowBudget;
uniform float SunShadowDistance;
uniform vec4 SunDirection;
uniform int AdvancedMaterials;
uniform vec3 MaterialTransmission[64];
uniform int RefreshInterval;
uniform int FrameIndex;

#moj_import <rayvox:shadow.glsl>
#moj_import <rayvox:soft_shadow.glsl>
#moj_import <rayvox:lighting_common.glsl>

out vec4 lighting;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 key = texelFetch(PassKey, pixel, 0);
    if (key.w <= 0.0) discard;
    float code = texelFetch(PassInputs, pixel, 0).a;
    float state = floor(code / 512.0 + 0.001);
    if (state < 0.5) discard;
    if (state < 1.5 && RefreshInterval > 1) {
        // Stale pixels refresh in an interleaved screen pattern; newly visible pixels are always traced.
        int phase = RefreshInterval == 2 ? ((pixel.x + pixel.y) & 1)
                : RefreshInterval == 4 ? ((pixel.x & 1) + 2 * (pixel.y & 1)) : (pixel.x + 2 * pixel.y) % RefreshInterval;
        if (phase != FrameIndex % RefreshInterval) discard;
    }
    code -= state * 512.0;
    bool farReady = code >= 255.5;
    if (farReady) code -= 256.0;
    int mask = int(code + 0.5);
    vec3 base = texelFetch(PassInputs, pixel, 0).rgb;

    vec3 position = key.xyz;
    vec3 normal = historyNormal(key.w);
    vec3 origin = position + normal * 0.03;
    ivec3 cell = ivec3(floor(position - normal * 0.02));
    vec3 direct = vec3(0.0);
    if (all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(CacheWidth * 16)))) {
        ivec3 sectionSlot = cacheSlot(cell);
        int row = sectionSlot.x + CacheWidth * (sectionSlot.y + CacheWidth * sectionSlot.z);
        ivec3 tile = (cell % 16) / 4;
        int column = (tile.x + 4 * (tile.y + 4 * tile.z)) * 9;
        uint header = texelFetch(LightLists, ivec2(column, row), 0).r;
        int count = int(header & 255u);
        for (int i = 0; i < 8; i++) {
            if (i >= count) break;
            int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
            if (lightCulled(index)) continue;
            vec4 light = texelFetch(LightData, ivec2(0, index), 0);
            vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
            vec3 contribution = nearContribution(light, radiance, position, normal);
            if (all(equal(contribution, vec3(0.0)))) continue;
            bool matched = farReady && ((mask >> i) & 1) != 0;
            float weight = staticWeight(light.xyz, farReady, matched);
            if (weight <= 0.0) continue;
            vec3 visibility = ShadowsEnabled != 0 && radiance.w > 0.5
                    ? blockVisibility(origin, light.xyz, tileOccluded(header, i)) : vec3(1.0);
            direct += contribution * weight * visibility;
        }
    }
    if (farReady) direct = max(base + direct, vec3(0.0));

    float sunlight = 1.0;
    float facing = max(dot(normal, SunDirection.xyz), 0.0);
    float fade = 1.0 - smoothstep(SunShadowDistance * 0.5, SunShadowDistance,
            length(position - CameraFraction - vec3(CameraInCache)));
    if (SunDirection.w > 0.0 && fade > 0.0 && facing > 0.0) {
        vec3 transmission = softDirectionalTransmission(origin, origin + SunDirection.xyz * SunShadowDistance,
                CameraFraction + vec3(CameraInCache), SunShadowDistance);
        sunlight = dot(transmission, vec3(1.0 / 3.0));
    }
    lighting = vec4(direct, sunlight);
}
