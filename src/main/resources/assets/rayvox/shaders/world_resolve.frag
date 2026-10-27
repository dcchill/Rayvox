#version 330 core
in vec2 uv;
uniform sampler2D sourceImage;
uniform bool upscale;
uniform bool fxaa;
uniform float sharpness;
uniform vec2 sourceSize;
out vec4 color;
float luma(vec3 c){return dot(c,vec3(.299,.587,.114));}
vec2 contentSize(){return sourceSize.x>0.0?sourceSize:vec2(textureSize(sourceImage,0));}
vec2 contentUv(vec2 p){vec2 size=vec2(textureSize(sourceImage,0));return clamp(p,.5/size,(contentSize()-.5)/size);}
vec3 sampleColor(vec2 p){return textureLod(sourceImage,contentUv(p),0.0).rgb;}
float cubic(float x){x=abs(x);return x<1.0?1.5*x*x*x-2.5*x*x+1.0:x<2.0?-.5*x*x*x+2.5*x*x-4.0*x+2.0:0.0;}
vec3 spatial(vec2 p,vec2 texel) {
    vec2 position=p/texel-.5,base=floor(position),f=fract(position);
    vec3 result=vec3(0),lo=vec3(1),hi=vec3(0);float total=0.0;
    for(int y=-1;y<=2;y++)for(int x=-1;x<=2;x++) {
        ivec2 size=ivec2(contentSize());
        vec3 c=texelFetch(sourceImage,clamp(ivec2(base)+ivec2(x,y),ivec2(0),size-1),0).rgb;
        float w=cubic(float(x)-f.x)*cubic(float(y)-f.y);result+=c*w;total+=w;
        if(x>=0&&x<=1&&y>=0&&y<=1){lo=min(lo,c);hi=max(hi,c);}
    }
    result=clamp(result/max(total,.001),lo,hi);
    vec3 neighbors=(sampleColor(p+vec2(texel.x,0))+sampleColor(p-vec2(texel.x,0))+sampleColor(p+vec2(0,texel.y))+sampleColor(p-vec2(0,texel.y)))*.25;
    // Bound sharpening by the local range: no ringing or new bright/dark extrema.
    return clamp(result+(result-neighbors)*sharpness*.5,lo,hi);
}
vec3 antiAlias(vec2 p,vec2 texel) {
    vec3 center=sampleColor(p);
    float nw=luma(sampleColor(p+vec2(-1,1)*texel)),ne=luma(sampleColor(p+vec2(1,1)*texel));
    float sw=luma(sampleColor(p+vec2(-1,-1)*texel)),se=luma(sampleColor(p+vec2(1,-1)*texel)),m=luma(center);
    float minimum=min(m,min(min(nw,ne),min(sw,se))),maximum=max(m,max(max(nw,ne),max(sw,se)));
    if(maximum-minimum<max(.0312,maximum*.125))return center;
    vec2 direction=vec2(-((nw+ne)-(sw+se)),(nw+sw)-(ne+se));
    float reduce=max((nw+ne+sw+se)*(.25*.125),1.0/128.0);
    direction=clamp(direction/(min(abs(direction.x),abs(direction.y))+reduce),vec2(-8),vec2(8))*texel;
    vec3 a=.5*(sampleColor(p+direction*(1.0/3.0-.5))+sampleColor(p+direction*(2.0/3.0-.5)));
    vec3 b=a*.5+.25*(sampleColor(p-direction*.5)+sampleColor(p+direction*.5));
    float value=luma(b);return value<minimum||value>maximum?a:b;
}
void main() {
    vec2 texel=1.0/vec2(textureSize(sourceImage,0));
    vec2 sourceUv=uv*contentSize()*texel;
    color=vec4(fxaa?antiAlias(sourceUv,texel):upscale?spatial(sourceUv,texel):sampleColor(sourceUv),textureLod(sourceImage,contentUv(sourceUv),0.0).a);
}