uniform int EntityCount;
uniform sampler2D EntityData;
uniform int ShadowSoftness;

bool entityBoxHit(vec3 start, vec3 ray) {
    vec3 inverse = 1.0 / mix(ray, vec3(1e-20), lessThan(abs(ray), vec3(1e-20)));
    vec3 a = (-vec3(1) - start) * inverse, b = (vec3(1) - start) * inverse;
    vec3 lo = min(a,b), hi = max(a,b);
    return max(max(lo.x,lo.y),max(lo.z,0.001)) < min(min(hi.x,hi.y),min(hi.z,0.999));
}

float entityVisibility(vec3 origin, vec3 target) {
    float visibility=1.0,fade=0.0;
    int count=clamp(EntityCount,0,16),entity=0,part=0,end=0;
    vec3 segment=target-origin;
    // One traversal loop avoids nested per-model loops and register spilling on Intel GL drivers.
    for(int step=0;step<count*33;step++) {
        if(part<end) {
            vec4 x=texelFetch(EntityData,ivec2(0,part),0),y=texelFetch(EntityData,ivec2(1,part),0),z=texelFetch(EntityData,ivec2(2,part),0);
            part++;
            vec3 local=vec3(dot(x,vec4(origin,1)),dot(y,vec4(origin,1)),dot(z,vec4(origin,1)));
            vec3 ray=vec3(dot(x.xyz,segment),dot(y.xyz,segment),dot(z.xyz,segment));
            if(entityBoxHit(local,ray)){visibility=min(visibility,1.0-fade);part=end;}
        } else {
            if(entity>=count)break;
            vec4 caster=texelFetch(EntityData,ivec2(0,entity),0);
            vec4 bounds=texelFetch(EntityData,ivec2(1,entity),0);
            int parts=int(texelFetch(EntityData,ivec2(2,entity++),0).x);
            if(caster.w<=0.0)continue;
            vec3 start=(origin-caster.xyz)/bounds.xyz,targetLocal=(target-caster.xyz)/bounds.xyz;
            vec3 ray=segment/bounds.xyz;
            if(parts>0) {
                // A held light inside its owner's bounds must still illuminate outwards.
                if(all(lessThanEqual(abs(targetLocal),vec3(1.001)))||!entityBoxHit(start,ray))continue;
                part=int(bounds.w);end=part+min(parts,32);fade=caster.w;
            } else {
                if(dot(targetLocal,targetLocal)<=1.001)continue;
                float a=dot(ray,ray);if(a<1e-6)continue;
                float b=dot(start,ray),discriminant=b*b-a*(dot(start,start)-1.0);
                if(discriminant<=0.0)continue;
                float root=sqrt(discriminant);
                if((-b+root)/a>0.001&&(-b-root)/a<0.999)visibility=min(visibility,1.0-caster.w);
            }
        }
        if(visibility<=0.0)break;
    }
    return visibility;
}

mat2x3 areaBasis(vec3 ray, float radius) {
    float distance=length(ray);
    if(distance<0.0001)return mat2x3(vec3(0),vec3(0));
    vec3 direction=ray/distance;
    vec3 tangent=normalize(cross(abs(direction.y)<0.99?vec3(0,1,0):vec3(1,0,0),direction));
    return mat2x3(tangent*radius,cross(direction,tangent)*radius);
}
vec3 areaOffset(mat2x3 basis,int index) {
    return basis*vec2((index&1)==0?-0.7071:0.7071,(index&2)==0?-0.7071:0.7071);
}

vec3 softTransmission(vec3 origin, vec3 target) {
    int samples = ShadowSoftness > 0 ? 4 : 1;
    vec3 camera = CameraFraction + vec3(CameraInCache);
    vec3 result = vec3(0.0);
    mat2x3 basis=areaBasis(target-origin,float(ShadowSoftness)*0.035);
    // Uniform loop bounds avoid duplicating the full voxel tracer four times on eager-unrolling drivers.
    for (int i = 0; i < samples; i++) {
        vec3 light = target + (samples > 1 ? areaOffset(basis,i) : vec3(0.0));
        float entity = entityVisibility(origin - camera, light - camera);
        if (CullingDisabled == 0 && entity <= 0.0) continue;
        result += traceTransmission(origin, light, false) * entity;
    }
    return result / float(samples);
}

// Cached world visibility has no entity traversal; keep this separate so GL drivers can remove it entirely.
vec3 softWorldTransmission(vec3 origin,vec3 target) {
    int samples=ShadowSoftness>0?4:1;
    mat2x3 basis=areaBasis(target-origin,float(ShadowSoftness)*0.035);
    vec3 result=vec3(0);
    for(int i=0;i<samples;i++)result+=traceTransmission(origin,target+(samples>1?areaOffset(basis,i):vec3(0)),false);
    return result/float(samples);
}

vec3 sampledTransmission(vec3 origin,vec3 target,int index) {
    if(index==-2||ShadowSoftness==0&&index>=0)return softTransmission(origin,target);
    if(index<0)return softWorldTransmission(origin,target);
    vec3 light=target+areaOffset(areaBasis(target-origin,float(ShadowSoftness)*0.035),index);
    vec3 camera=CameraFraction+vec3(CameraInCache);
    return traceTransmission(origin,light,false)*entityVisibility(origin-camera,light-camera);
}

#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
vec3 softWorldDirectionalTransmission(vec3 origin,vec3 target,vec3 camera,float range) {
    int samples=ShadowSoftness>0?4:1;
    mat2x3 basis=areaBasis(target-origin,length(target-origin)*float(ShadowSoftness)*0.001);
    vec3 result=vec3(0);
    for(int i=0;i<samples;i++)result+=traceDirectionalTransmission(origin,target+(samples>1?areaOffset(basis,i):vec3(0)),camera,range);
    return result/float(samples);
}

vec3 softDirectionalTransmission(vec3 origin, vec3 target, vec3 camera, float range) {
    int samples = ShadowSoftness > 0 ? 4 : 1;
    vec3 result = vec3(0.0);
    mat2x3 basis=areaBasis(target-origin,length(target-origin)*float(ShadowSoftness)*0.001);
    for (int i = 0; i < samples; i++) {
        vec3 light = target + (samples > 1 ? areaOffset(basis,i) : vec3(0.0));
        float entity = entityVisibility(origin - camera, light - camera);
        if (CullingDisabled == 0 && entity <= 0.0) continue;
        result += traceDirectionalTransmission(origin, light, camera, range) * entity;
    }
    return result / float(samples);
}
#endif

// Only rays actually blocked by an entity need a voxel trace to correct cached terrain lighting.
vec3 entityOcclusion(vec3 origin,vec3 target,vec3 camera,bool directional,float range) {
    if(EntityCount==0)return vec3(0);
    int samples=ShadowSoftness>0?4:1;
    float radius=directional?length(target-origin)*float(ShadowSoftness)*0.001:float(ShadowSoftness)*0.035;
    mat2x3 basis=areaBasis(target-origin,radius);
    vec3 result=vec3(0);
    for(int i=0;i<samples;i++) {
        vec3 light=target+(samples>1?areaOffset(basis,i):vec3(0));
        float blocked=1.0-entityVisibility(origin-camera,light-camera);
        if(blocked<=0.0)continue;
        vec3 visibility;
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
        if(directional)visibility=traceDirectionalTransmission(origin,light,camera,range);
        else
#endif
        visibility=traceTransmission(origin,light,false);
        result+=visibility*blocked;
    }
    return result/float(samples);
}
