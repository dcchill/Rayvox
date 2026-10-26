uniform sampler2D PassDepth;
uniform mat4 InverseClipMat;

// Current depth, rather than the retained history anchor, keeps moving shadows on the actual surface.
vec3 currentSurface(ivec2 pixel) {
    vec2 ndc = (vec2(pixel) + 0.5) / vec2(textureSize(PassDepth, 0)) * 2.0 - 1.0;
    vec4 position = InverseClipMat * vec4(ndc, texelFetch(PassDepth, pixel, 0).r * 2.0 - 1.0, 1.0);
    return position.xyz / position.w;
}
