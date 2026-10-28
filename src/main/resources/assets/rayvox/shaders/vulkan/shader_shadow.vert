#version 450
layout(location=0) in vec3 position;
layout(location=1) in vec4 uvMaterial;
layout(location=2) in vec4 tint;
layout(location=0) out vec2 uv;
layout(location=1) out float alpha;
layout(push_constant) uniform Draw { mat4 lightVP; vec4 offset; } draw;
void main() {
    uv=uvMaterial.xy;
    alpha=uvMaterial.z>=2.0?0.0:tint.a;
    gl_Position=draw.lightVP*vec4(position+draw.offset.xyz,1.0);
}
