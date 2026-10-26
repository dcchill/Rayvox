uniform sampler2D PassDepth;
uniform mat4 InverseClipMat;
uniform float SunShadowDistance;

vec3 volumeSurface(vec2 uv) {
    vec4 p = InverseClipMat * vec4(uv * 2.0 - 1.0, texture(PassDepth, uv).r * 2.0 - 1.0, 1.0);
    return p.xyz / max(abs(p.w), 0.000001) * sign(p.w);
}

float volumeRange() { return min(SunShadowDistance, 32.0); }
