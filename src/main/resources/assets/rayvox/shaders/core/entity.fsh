#version 150

#moj_import <minecraft:fog.glsl>
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float AlphaCutoff;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform usampler3D VoxelShapes;
uniform usampler3D VoxelOccupancy;
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
uniform int DynamicCount;
uniform vec4 DynamicRecords[8];
uniform vec4 SunDirection;
uniform float SunShadowDistance;

#moj_import <rayvox:shadow.glsl>
#moj_import <rayvox:soft_shadow.glsl>
#moj_import <rayvox:lighting_common.glsl>
#moj_import <rayvox:sunlight.glsl>

in float vertexDistance;
in vec4 vertexColor;
in vec4 vanillaLight;
in vec4 skyLight;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 surfacePosition;
in vec3 surfaceNormal;
out vec4 fragColor;

void main() {
    vec4 textureColor = texture(Sampler0, texCoord0);
    if (textureColor.a < AlphaCutoff) discard;
    vec4 albedo = textureColor * vertexColor * ColorModulator;
    albedo.rgb = mix(overlayColor.rgb, albedo.rgb, overlayColor.a);
    vec3 normal = normalize(surfaceNormal);
    vec3 camera = CameraFraction + vec3(CameraInCache);
    vec3 position = surfacePosition + camera;
    vec3 origin = position + normal * 0.03;
    // Entities move freely through cells: always evaluate current geometry, never terrain history.
    ivec3 cell = ivec3(floor(position));
    vec3 direct = vec3(0.0);
    float replacement = 0.0;
    if (all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(CacheWidth * 16)))) {
        ivec3 slot = cacheSlot(cell);
        if ((texelFetch(VoxelState, slot, 0).r & 1u) != 0u) {
            vec3 edge = min(position, vec3(CacheWidth * 16) - position);
            replacement = smoothstep(0.0, 16.0, min(edge.x, min(edge.y, edge.z)));
        }
        int row = slot.x + CacheWidth * (slot.y + CacheWidth * slot.z);
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
            vec3 visibility = ShadowsEnabled != 0 && radiance.w > 0.5
                    ? blockVisibility(origin, light.xyz, tileOccluded(header, i)) : vec3(1.0);
            direct += contribution * visibility * staticWeight(light.xyz, false, false);
        }
    }
    for (int i = 0; i < 4; i++) {
        if (i >= DynamicCount) break;
        vec4 light = DynamicRecords[i * 2];
        light.xyz += camera;
        vec4 radiance = DynamicRecords[i * 2 + 1];
        vec3 contribution = nearContribution(light, radiance, position, normal);
        if (all(equal(contribution, vec3(0.0)))) continue;
        vec3 visibility = ShadowsEnabled != 0 && radiance.w > 0.5
                ? blockVisibility(origin, light.xyz) : vec3(1.0);
        direct += contribution * visibility;
    }
    vec3 base = mix(toLinear(vanillaLight.rgb), toLinear(skyLight.rgb), replacement);
    vec3 transmission = vec3(1.0);
    if (SunDirection.w > 0.0 && dot(normal, SunDirection.xyz) > 0.0 && length(surfacePosition) < SunShadowDistance)
        transmission = softDirectionalTransmission(origin, origin + SunDirection.xyz * SunShadowDistance,
                camera, SunShadowDistance);
    base = sunlitBase(base, normal, transmission, length(surfacePosition));
    vec3 lit = toSrgb(clamp(toLinear(albedo.rgb) * max(base, direct), 0.0, 1.0));
    fragColor = linear_fog(vec4(lit, albedo.a * vanillaLight.a), vertexDistance, FogStart, FogEnd, FogColor);
}
