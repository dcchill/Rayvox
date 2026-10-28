#version 150
uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform usampler3D VoxelShapes;
uniform sampler2D LightData;
uniform usampler2D LightLists;
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
uniform vec4 DynamicRecords[8];
uniform sampler2D SceneColor;
uniform sampler2D PassKey;
uniform sampler2D PassLighting;
uniform sampler2D PassIndirect;
uniform sampler2D PassDynamic;
uniform sampler2D PassBase;
uniform sampler2D PassEmission;
uniform sampler2D PassAlbedo;
uniform sampler2D PassInputs;
uniform int IndirectEnabled;
uniform int DynamicCount;
uniform int DebugMode;
uniform vec4 SunDirection;
uniform float SunShadowDistance;
uniform vec4 FogColor;

#moj_import <rayvox:shadow.glsl>
#moj_import <rayvox:soft_shadow.glsl>
#moj_import <rayvox:lighting_common.glsl>
#moj_import <rayvox:surface_material.glsl>
#moj_import <rayvox:current_surface.glsl>
#moj_import <rayvox:sunlight.glsl>
#moj_import <rayvox:indirect.glsl>
#moj_import <rayvox:surface_reflection.glsl>
#moj_import <rayvox:entity_lighting.glsl>

out vec4 lighting;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 key = texelFetch(PassKey, pixel, 0);
    // Preserve sky and native rendering that did not produce a supported terrain surface.
    if (key.w <= 0.0) { lighting = texelFetch(SceneColor, pixel, 0); return; }
    vec4 surface = texelFetch(PassAlbedo, pixel, 0);
    vec4 albedo = surfaceAlbedo(surface);
    vec3 normal = surfaceDirection(surface);
    vec2 material = surfaceProperties(surface);
    if (MetalReflections == 0) material.x = 0.0;
    vec4 baseData = texelFetch(PassBase, pixel, 0);
    vec4 emission = texelFetch(PassEmission, pixel, 0);
    vec4 stored = texelFetch(PassLighting, pixel, 0);
    vec3 position = currentSurface(pixel) + CameraFraction + vec3(CameraInCache);
    vec3 geometric = historyNormal(key.w);
    vec3 direct = max(stored.rgb-entityDirectOcclusion(position,geometric,normal,texelFetch(PassInputs,pixel,0).a),vec3(0));
    if (DynamicCount > 0) direct += texelFetch(PassDynamic, pixel, 0).rgb;
    vec3 indirect = IndirectEnabled != 0 ? texelFetch(PassIndirect, pixel, 0).rgb * baseData.a : vec3(0.0);
    if (DebugMode == 6) {
        float state = floor(texelFetch(PassInputs, pixel, 0).a / 512.0 + 0.001);
        vec3 reuse = state < 0.5 ? vec3(0.1, 0.8, 0.2) : state < 1.5 ? vec3(0.9, 0.65, 0.05) : vec3(0.8, 0.15, 0.05);
        lighting = vec4(reuse, albedo.a); return;
    }
    if (DebugMode == 5 || DebugMode == 7) {
        vec3 value = DebugMode == 5 ? direct : indirect;
        lighting = vec4(toSrgb(value / (vec3(1.0) + value)), albedo.a); return;
    }
    vec3 base = toLinear(baseData.rgb);
    if (SunDirection.w > 0.0) {
        float sunlight = stored.a >= 2.0 ? stored.a - 2.0 : stored.a;
        sunlight=max(sunlight-dot(entitySunOcclusion(position,geometric,normal),vec3(1.0/3.0)),0.0);
        base = sunlitBase(base, normal, vec3(sunlight), length(currentSurface(pixel)));
    }
    vec3 illumination = max(max(base, direct) + indirect, toLinear(emission.rgb));
    vec3 diffuse = toLinear(albedo.rgb) * illumination * surfaceDiffuseWeight(material.x);
    diffuse = max(diffuse, toLinear(albedo.rgb) * toLinear(emission.rgb));
    vec3 specular = surfaceReflection(position, historyNormal(key.w), normal, toLinear(albedo.rgb), material);
    vec3 lit = toSrgb(clamp(diffuse + specular, 0.0, 1.0));
    lighting = vec4(mix(lit, FogColor.rgb, emission.a), albedo.a);
}
