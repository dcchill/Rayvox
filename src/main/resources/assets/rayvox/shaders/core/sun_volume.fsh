#version 150

uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform usampler3D VoxelShapes;
uniform usampler3D VoxelOccupancy;
uniform int CacheWidth;
uniform ivec3 CacheMinSlot;
uniform ivec3 CameraInCache;
uniform vec3 CameraFraction;
uniform vec4 SunDirection;
uniform vec3 SunRadiance;
uniform int AdvancedMaterials;
uniform vec3 MaterialTransmission[64];
uniform vec2 VolumeSize;
uniform int VolumeSteps;
uniform float Rain;

#moj_import <rayvox:volume_depth.glsl>
#moj_import <rayvox:shadow.glsl>

out vec4 lighting;

void main() {
    lighting = vec4(0.0);
    if (SunDirection.w <= 0.0) return;
    vec2 uv = gl_FragCoord.xy / VolumeSize;
    vec3 surface = volumeSurface(uv);
    float distance = min(length(surface), volumeRange());
    if (distance < 0.01) return;
    vec3 direction = normalize(surface);
    vec3 camera = CameraFraction + vec3(CameraInCache);
    int steps = clamp(VolumeSteps, 4, 24);
    float stride = distance / float(steps);
    // Stable interleaved offsets break marching bands without temporally smearing moving silhouettes.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float g = 0.6;
    float cosine = dot(direction, SunDirection.xyz);
    float phase = (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * cosine, 0.01), 1.5);
    float density = mix(0.012, 0.025, clamp(Rain, 0.0, 1.0));
    vec3 scattered = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < steps; i++) {
        float travel = (float(i) + jitter) * stride;
        vec3 position = camera + direction * travel;
        int voxel = voxelAt(ivec3(floor(position)));
        if (voxel < 0) continue; // Unknown geometry must not become illuminated fog.
        int material = voxel & 63;
        if (material == 1 || material == 2) continue;
        float extinction = exp(-density * stride * (1.0 - smoothstep(volumeRange() * 0.75, volumeRange(), travel)));
        vec3 visibility = traceDirectionalTransmission(position, position + SunDirection.xyz * SunShadowDistance,
                camera, SunShadowDistance);
        scattered += transmittance * (1.0 - extinction) * visibility;
        transmittance *= extinction;
    }
    lighting = vec4(scattered * SunRadiance * (SunDirection.w * phase * 0.3), 1.0 - transmittance);
}
