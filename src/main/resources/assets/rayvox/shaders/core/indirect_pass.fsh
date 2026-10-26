#version 150

uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform usampler3D VoxelShapes;
uniform sampler2D LightData;
uniform usampler2D LightLists;
uniform sampler2D PassKey;
uniform sampler2D PassInputs;
uniform sampler2D PassIndirect;
uniform int CacheWidth;
uniform ivec3 CacheMinSlot;
uniform ivec3 CameraInCache;
uniform vec3 CameraFraction;
uniform int ShadowsEnabled;
uniform float ShadowDistance;
uniform float ShadowDrawDistance;
uniform vec4 ShadowBudget;
uniform int AdvancedMaterials;
uniform vec3 MaterialTransmission[64];
uniform int RefreshInterval;
uniform int FrameIndex;
uniform vec4 SunDirection;
uniform float SunShadowDistance;

#moj_import <rayvox:shadow.glsl>
#moj_import <rayvox:soft_shadow.glsl>
#moj_import <rayvox:lighting_common.glsl>
#moj_import <rayvox:sunlight.glsl>
#moj_import <rayvox:indirect.glsl>

out vec4 lighting;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 key = texelFetch(PassKey, pixel, 0);
    if (key.w <= 0.0) discard;
    lighting = vec4(0.0);
    vec3 normal = historyNormal(key.w);
    ivec3 cell = ivec3(floor(key.xyz - normal * 0.02));
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(CacheWidth * 16)))
            || (texelFetch(VoxelState, cacheSlot(cell), 0).r & 1u) == 0u) return;

    float state = floor(texelFetch(PassInputs, pixel, 0).a / 512.0 + 0.001);
    vec4 accumulated = state >= 1.0 ? vec4(0.0) : texelFetch(PassIndirect, pixel, 0);
    float count = clamp(accumulated.a, 0.0, 8.0);
    // Freshly invalidated GI starts immediately; later samples follow the independent work cadence.
    bool sampleNow = count < 1.0 || RefreshInterval <= 1
            || ((pixel.x / 8 + pixel.y / 8 + FrameIndex) % RefreshInterval) == 0;
    if (count >= 8.0 || !sampleNow) { lighting = accumulated; return; }
    vec3 gathered = bounceSample(key.xyz + normal * 0.03, normal, int(count));
    lighting = vec4((accumulated.rgb * count + gathered) / (count + 1.0), count + 1.0);
}
