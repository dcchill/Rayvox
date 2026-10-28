#version 450
layout(location=0) in vec2 uv;
layout(location=1) in float alpha;
layout(set=0,binding=0) uniform sampler2D atlas;
void main() { if(alpha*texture(atlas,uv).a<0.1)discard; }
