#version 150

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec4 vanillaLight;
out vec4 skyLight;
out vec3 sourceLight;
out vec2 texCoord0;
out vec3 surfacePosition;
out vec3 surfaceNormal;

void main() {
    surfacePosition = Position + ChunkOffset;
    surfaceNormal = Normal;
    gl_Position = ProjMat * ModelViewMat * vec4(surfacePosition, 1.0);
    vertexDistance = fog_distance(surfacePosition, FogShape);
    vertexColor = Color;
    vanillaLight = minecraft_sample_lightmap(Sampler2, UV2);
    skyLight = minecraft_sample_lightmap(Sampler2, ivec2(0, UV2.y));
    int emission = UV2.x & 15;
    sourceLight = emission > 0 ? minecraft_sample_lightmap(Sampler2, ivec2(emission * 16, 0)).rgb : vec3(0.0);
    texCoord0 = UV0;
}
