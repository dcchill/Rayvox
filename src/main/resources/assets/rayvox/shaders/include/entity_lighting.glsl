vec3 entityDirectOcclusion(vec3 position,vec3 geometric,vec3 normal,float code) {
    if(EntityCount==0||ShadowsEnabled==0)return vec3(0);
    ivec3 cell=ivec3(floor(position-geometric*0.02));
    if(voxelAt(cell)<0)return vec3(0);
    ivec3 slot=cacheSlot(cell),tile=(cell%16)/4;
    int row=slot.x+CacheWidth*(slot.y+CacheWidth*slot.z);
    int column=(tile.x+4*(tile.y+4*tile.z))*9;
    uint header=texelFetch(LightLists,ivec2(column,row),0).r;
    code=mod(code,512.0);
    bool ready=code>=255.5;
    int mask=int(mod(code,256.0)+0.5);
    vec3 origin=position+geometric*0.03,camera=CameraFraction+vec3(CameraInCache);
    vec3 result=vec3(0);
    for(int i=0;i<8;i++) {
        if(i>=int(header&255u))break;
        int index=int(texelFetch(LightLists,ivec2(column+i+1,row),0).r);
        if(lightCulled(index)||tileOccluded(header,i))continue;
        vec4 light=texelFetch(LightData,ivec2(0,index),0),radiance=texelFetch(LightData,ivec2(1,index),0);
        if(radiance.w<=0.5||distance(origin,light.xyz)>ShadowDistance)continue;
        vec3 contribution=nearContribution(light,radiance,position,normal);
        if(all(equal(contribution,vec3(0))))continue;
#ifdef RAYVOX_TERRAIN_ENTITY_LIGHTING
        ready=FarReady!=0;
        bool matched=ready&&farMatched(index);
#else
        bool matched=ready&&((mask>>i)&1)!=0;
#endif
        float weight=staticWeight(light.xyz,ready,matched);
        float fade=1.0-smoothstep(ShadowDrawDistance*0.5,ShadowDrawDistance,max(length(origin-camera),length(light.xyz-camera)));
        if(weight*fade<=0.0)continue;
        result+=contribution*weight*fade*entityOcclusion(origin,light.xyz,camera,false,0.0);
    }
    return result;
}

vec3 entitySunOcclusion(vec3 position,vec3 geometric,vec3 normal) {
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    vec3 camera=CameraFraction+vec3(CameraInCache);
    if(EntityCount>0&&SunDirection.w>0.0&&dot(normal,SunDirection.xyz)>0.0&&length(position-camera)<SunShadowDistance) {
        vec3 origin=position+geometric*0.03;
        return entityOcclusion(origin,origin+SunDirection.xyz*SunShadowDistance,camera,true,SunShadowDistance);
    }
#endif
    return vec3(0);
}
