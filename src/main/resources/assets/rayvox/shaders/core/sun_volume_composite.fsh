#version 150
uniform sampler2D VolumeLight;

#moj_import <rayvox:volume_depth.glsl>
#moj_import <rayvox:color.glsl>

out vec4 lighting;

void main() {
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(PassDepth, 0));
    float receiver = min(length(volumeSurface(uv)), volumeRange());
    vec2 size = vec2(textureSize(VolumeLight, 0));
    vec2 p = uv * size - 0.5;
    vec2 fraction = fract(p);
    vec4 volume = vec4(0.0);
    float weightSum = 0.0;
    // Reject low-resolution samples across a foreground edge instead of spilling shafts over it.
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec2 sampleUv = clamp((floor(p) + vec2(x, y) + 0.5) / size, 0.5 / size, 1.0 - 0.5 / size);
        float sampleDistance = min(length(volumeSurface(sampleUv)), volumeRange());
        vec2 bilinear = mix(1.0 - fraction, fraction, vec2(x, y));
        float weight = bilinear.x * bilinear.y * exp(-abs(sampleDistance - receiver) * 4.0);
        volume += texture(VolumeLight, sampleUv) * weight;
        weightSum += weight;
    }
    if (weightSum < 0.0001) { lighting = vec4(0.0); return; }
    volume /= weightSum;
    // Minecraft stores native scene color in sRGB values; blend the fog in that same space.
    float opacity = clamp(volume.a, 0.0, 1.0);
    lighting = vec4(toSrgb(max(volume.rgb, vec3(0.0)) / max(opacity, 0.00001)) * opacity, opacity);
}
