#version 150
#define RAYVOX_NO_DIRECTIONAL_SHADOWS

uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform usampler3D VoxelShapes;
uniform sampler2D PassKey;
uniform sampler2D PassAlbedo;
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
uniform int DynamicCount;
uniform vec4 DynamicRecords[8];

#moj_import <rayvox:shadow.glsl>
#moj_import <rayvox:soft_shadow.glsl>
#moj_import <rayvox:lighting_common.glsl>
#moj_import <rayvox:current_surface.glsl>
#moj_import <rayvox:surface_material.glsl>

out vec4 lighting;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 key = texelFetch(PassKey, pixel, 0);
    if (key.w <= 0.0) discard;
    vec3 geometric = historyNormal(key.w);
    vec3 normal = surfaceDirection(texelFetch(PassAlbedo, pixel, 0));
    vec3 position = currentSurface(pixel);
    vec3 camera = CameraFraction + vec3(CameraInCache);
    vec3 direct = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        if (i >= DynamicCount) break;
        vec4 light = DynamicRecords[i * 2];
        vec4 radiance = DynamicRecords[i * 2 + 1];
        vec3 contribution = nearContribution(light, radiance, position, normal);
        if (all(equal(contribution, vec3(0.0)))) continue;
        vec3 visibility = ShadowsEnabled != 0 && radiance.w > 0.5
                ? blockVisibilityCurrent(position + camera + geometric * 0.03, light.xyz + camera) : vec3(1.0);
        direct += contribution * visibility;
    }
    lighting = vec4(direct, 1.0);
}
