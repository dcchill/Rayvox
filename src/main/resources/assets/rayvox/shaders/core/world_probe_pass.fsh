#version 150
#define RAYVOX_WORLD_PROBES

uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform usampler3D VoxelShapes;
uniform sampler2D LightData;
uniform usampler2D LightLists;
uniform int CacheWidth;
uniform ivec3 CacheMinSlot;
uniform int ShadowsEnabled;
uniform float ShadowDistance;
uniform int AdvancedMaterials;
uniform vec3 MaterialTransmission[64];
uniform vec4 SunDirection;
uniform float SunShadowDistance;
uniform ivec3 ProbeOrigin;
bool WorldProbeKnown = true;

#moj_import <rayvox:shadow.glsl>
#moj_import <rayvox:lighting_common.glsl>
#moj_import <rayvox:sunlight.glsl>
#moj_import <rayvox:indirect.glsl>

out vec4 lighting;

void main() {
    int column = int(gl_FragCoord.x);
    int probe = column / 6;
    int direction = column % 6;
    ivec3 offset = ivec3(probe % 4, (probe / 4) % 4, probe / 16) * 4 + 2;
    vec3 origin = vec3(ProbeOrigin + offset);
    lighting = vec4(0.0);
    // Conservative placement: partial blocks and filters cannot host a probe either.
    if (voxelAt(ivec3(origin)) != 0) return;
    vec3 normal = historyNormal(float(direction + 1));
    vec3 result = vec3(0.0);
    for (int sampleIndex = 0; sampleIndex < 8; sampleIndex++)
        result += bounceSample(origin, normal, sampleIndex);
    if (!WorldProbeKnown) return;
    // Alpha distinguishes a valid black sample from an unusable/embedded probe.
    lighting = vec4(min(result / 8.0, vec3(65504.0)), 1.0);
}
