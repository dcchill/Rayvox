#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D SurfaceAtlas;
uniform int SurfaceReady;
uniform int TextureBump;
uniform int TranslucentSurface;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float AlphaCutoff;
uniform usampler3D VoxelData;
uniform usampler3D VoxelState;
uniform int DebugMode;
uniform int CacheWidth;
uniform ivec3 CacheMinSlot;
uniform ivec3 CameraInCache;
uniform vec3 CameraFraction;
uniform sampler2D LightData;
uniform usampler2D LightLists;
uniform int ShadowsEnabled;
uniform float ShadowDistance;
uniform float ShadowDrawDistance;
uniform vec4 ShadowBudget;
uniform float SunShadowDistance;
uniform int FarLightCount;
uniform int FarReady;
uniform vec4 FarLightRecords[16];
uniform int HistoryValid;
uniform int HistoryAvailable;
uniform sampler2D HistoryColor;
uniform sampler2D HistoryKey;
uniform int DynamicCount;
uniform vec4 DynamicRecords[8];
uniform vec4 SunDirection;
uniform int SunHistoryValid;
uniform usampler3D VoxelShapes;
#ifdef RAYVOX_BASIC_MATERIALS
const int AdvancedMaterials = 0;
#else
uniform int AdvancedMaterials;
#endif
uniform vec3 MaterialTransmission[64];
#ifdef RAYVOX_NO_INDIRECT
const int IndirectEnabled = 0;
#else
uniform int IndirectEnabled;
#endif
uniform sampler2D HistoryIndirect;
// Deferred pipeline: history stays geometry-valid across scene revisions; HistoryStale marks stored lighting outdated.
uniform int DeferredLighting;
uniform int HistoryStale;

#moj_import <rayvox:shadow.glsl>

in float vertexDistance;
in vec4 vertexColor;
in vec4 vanillaLight;
in vec4 skyLight;
in vec3 sourceLight;
in vec2 texCoord0;
in vec3 surfacePosition;
in vec3 surfaceNormal;

out vec4 fragColor;
out vec4 cachedLight;
out vec4 cachedKey;
out vec4 cachedIndirect;
out vec4 deferredInputs;
out vec4 geometryBase;
out vec4 geometryEmission;
out vec4 geometryAlbedo;

#moj_import <rayvox:history.glsl>

#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
float sunColorKey(float face, vec3 visibility, float mean) {
    vec2 chroma = mean > 0.0 ? visibility.rg / (3.0 * mean) : vec2(1.0 / 3.0);
    uvec2 encoded = uvec2(round(clamp(chroma, vec2(0.0), vec2(1.0)) * 1023.0));
    // Axis normal + two 10-bit chromaticity coordinates + tag: exactly 24 bits in RGBA32F.
    return float(8388608u | uint(face) | (encoded.x << 3u) | (encoded.y << 13u));
}

vec3 sunColorVisibility(float mean, float key) {
    uint encoded = uint(key);
    vec2 chroma = vec2((encoded >> 3u) & 1023u, (encoded >> 13u) & 1023u) / 1023.0;
    return clamp(vec3(chroma, max(1.0 - chroma.x - chroma.y, 0.0)) * (3.0 * mean), vec3(0.0), vec3(1.0));
}
#endif

#moj_import <rayvox:soft_shadow.glsl>
#moj_import <rayvox:lighting_common.glsl>
#moj_import <rayvox:sunlight.glsl>

bool farMatched(int index) {
    bool matched = false;
    for (int i = 0; i < 8; i++) {
        if (i >= FarLightCount) break;
        if (int(FarLightRecords[i * 2 + 1].w) == index + 1) matched = true;
    }
    return matched;
}

vec3 staticVisibility(vec3 origin, vec3 target, int index, bool shadows, bool occluded) {
    bool matched = FarReady != 0 && farMatched(index);
    float weight = staticWeight(target, FarReady != 0, matched);
    if (weight <= 0.0) return vec3(0.0);
    vec3 visibility = shadows ? blockVisibility(origin, target, occluded) : vec3(1.0);
    // Ready section lights provide stable RGB; matching near records contribute only the shadow correction.
    return (FarReady != 0 && matched ? visibility - vec3(1.0) : visibility) * weight;
}

#moj_import <rayvox:indirect.glsl>
#moj_import <rayvox:surface_material.glsl>
#moj_import <rayvox:surface_reflection.glsl>
#define RAYVOX_TERRAIN_ENTITY_LIGHTING
#moj_import <rayvox:entity_lighting.glsl>

void main() {
    cachedLight = vec4(0.0);
    cachedKey = vec4(0.0);
    cachedIndirect = vec4(0.0);
    deferredInputs = vec4(0.0);
    geometryBase = vec4(0.0);
    geometryEmission = vec4(0.0);
    geometryAlbedo = vec4(0.0);
    vec4 albedo = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    float alpha = albedo.a * vanillaLight.a;
    vec3 geometricNormal = surfaceNormal / max(length(surfaceNormal), 0.0001);
    vec4 surface = SurfaceReady != 0 && (TextureBump != 0 || MetalReflections != 0) ? texture(SurfaceAtlas, texCoord0) : vec4(vec3(128.0 / 255.0), 1);
    vec3 shadingNormal = TextureBump != 0 ? textureSurfaceNormal(geometricNormal, surfacePosition, texCoord0, surface.rg) : geometricNormal;
    vec2 material = vec2(MetalReflections != 0 ? surfaceResponse(surface.b) : 0.0, max(surface.a, 0.08));
    if (alpha < AlphaCutoff && !(TranslucentSurface != 0 && material.x < 0.0)) discard;
    // Fully opaque fog replaces RGB completely; retain the alpha test and skip invisible lighting work.
    if (DebugMode == 0 && vertexDistance > FogStart && vertexDistance >= FogEnd && FogColor.a == 1.0) {
        fragColor = vec4(FogColor.rgb, alpha);
        return;
    }

    if (TranslucentSurface != 0) {
        vec3 position = surfacePosition + CameraFraction + vec3(CameraInCache);
        vec3 reflection = material.x < 0.0 ? surfaceReflection(position, geometricNormal, shadingNormal, toLinear(albedo.rgb), material) : vec3(0.0);
        vec4 color = translucentSurfaceColor(vec4(albedo.rgb * vanillaLight.rgb, alpha), reflection);
        if (color.a < 0.001) discard;
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }

    if (DebugMode == 1 || DebugMode == 2) {
        vec3 normal = surfaceNormal / max(length(surfaceNormal), 0.0001);
        ivec3 local = ivec3(floor(surfacePosition + CameraFraction - normal * 0.02)) + CameraInCache;
        vec3 diagnostic = vec3(0.5, 0.05, 0.5);
        if (all(greaterThanEqual(local, ivec3(0))) && all(lessThan(local, ivec3(CacheWidth * 16)))) {
            ivec3 sectionSlot = cacheSlot(local);
            uint state = texelFetch(VoxelState, sectionSlot, 0).r;
            bool valid = (state & 1u) != 0u;
            bool dirty = (state & 2u) != 0u;
            if (DebugMode == 2) {
                diagnostic = dirty ? vec3(1.0, 0.6, 0.0) : valid ? vec3(0.1, 0.65, 0.2) : diagnostic;
            } else if (valid) {
                uint material = texelFetch(VoxelData, sectionSlot * 16 + local % 16, 0).r & 63u;
                diagnostic = material == 1u ? vec3(0.05, 0.8, 0.7) : material == 2u ? vec3(1.0, 0.45, 0.1)
                        : material >= 3u ? vec3(0.15, 0.4, 1.0) : vec3(0.08);
                if (dirty) diagnostic = mix(diagnostic, vec3(1.0, 0.8, 0.0), 0.6);
            } else if (dirty) diagnostic = vec3(1.0, 0.6, 0.0);
        }
        vec3 cell = fract(surfacePosition + CameraFraction);
        vec3 edge = min(cell, 1.0 - cell) + abs(normal);
        float grid = min(edge.x, min(edge.y, edge.z)) < 0.035 ? 0.45 : 1.0;
        fragColor = vec4(diagnostic * grid, alpha);
        return;
    }

    vec3 normal = surfaceNormal / max(length(surfaceNormal), 0.0001);
    vec3 localPosition = surfacePosition + CameraFraction + vec3(CameraInCache);
    float face = historyNormalKey(normal);
    bool bumped = TextureBump != 0 && SurfaceReady != 0;
    // shortcut: bumped slopes bypass reuse; add a separate signature attachment if sloped-model reuse matters.
    bool bumpedSlope = bumped && face > 6.0;
    if (bumped && !bumpedSlope) face = 4194311.0 + face + 8.0 * surfacePair(surfaceOct(shadingNormal));
    vec4 receiver = vec4(localPosition, face);
    ivec2 pixel = ivec2(0);
    bool projected = HistoryValid != 0 && historyPixel(surfacePosition, pixel);
    float footprint = max(length(dFdx(localPosition)), length(dFdy(localPosition)));
    vec4 prior = HistoryValid != 0 && projected ? texelFetch(HistoryKey, pixel, 0) : vec4(0.0);
    bool matchedSurface = !bumpedSlope && HistoryValid != 0 && projected && historySurface(prior, localPosition, normal, face, footprint);
    bool deferred = DeferredLighting != 0;
    // Legacy history is exact-revision; deferred history remains geometry-valid and tracks staleness per pixel.
    bool reused = matchedSurface && !deferred;
    ivec3 cell = ivec3(floor(localPosition - normal * 0.02));
    bool inCache = all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(CacheWidth * 16)));
    vec3 direct = reused ? texelFetch(HistoryColor, pixel, 0).rgb : vec3(0.0);
    float replacement = 0.0;
    float sunlight = 1.0;
    int deferredState = 0;
    if (deferred) {
        // States: 0 fresh (reuse), 1 stale (reuse, re-trace within the refresh interval), 2 new (estimate, always traced).
        vec3 anchor = matchedSurface ? prior.xyz : localPosition;
        vec3 anchorSurface = anchor - CameraFraction - vec3(CameraInCache);
        vec4 stored = matchedSurface ? texelFetch(HistoryColor, pixel, 0) : vec4(0.0, 0.0, 0.0, 1.0);
        bool storedStale = stored.a >= 2.0;
        deferredState = !matchedSurface ? 2 : storedStale || HistoryStale != 0 ? 1 : 0;
        if (matchedSurface) { direct = stored.rgb; sunlight = storedStale ? stored.a - 2.0 : stored.a; }
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
        if (deferredState == 0 && SunHistoryValid == 0 && SunDirection.w > 0.0 && dot(normal, SunDirection.xyz) > 0.0
                && length(surfacePosition) < SunShadowDistance) deferredState = 1;
#endif
        vec3 farBase = vec3(0.0);
        vec3 unshadowed = vec3(0.0);
        float farMask = 0.0;
        if (inCache) {
            ivec3 sectionSlot = cacheSlot(cell);
            int row = sectionSlot.x + CacheWidth * (sectionSlot.y + CacheWidth * sectionSlot.z);
            ivec3 tile = (cell % 16) / 4;
            int column = (tile.x + 4 * (tile.y + 4 * tile.z)) * 9;
            uint header = texelFetch(LightLists, ivec2(column, row), 0).r;
            if ((texelFetch(VoxelState, sectionSlot, 0).r & 1u) != 0u) {
                vec3 edge = min(localPosition, vec3(CacheWidth * 16) - localPosition);
                replacement = smoothstep(0.0, 16.0, min(edge.x, min(edge.y, edge.z)));
            }
            int count = int(header & 255u);
            if (deferredState == 0 && any(notEqual(HistoryCameraOffset, vec3(0.0)))) {
                for (int i = 0; i < 8; i++) {
                    if (i >= count) break;
                    int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
                    if (lightCulled(index)) continue;
                    vec4 light = texelFetch(LightData, ivec2(0, index), 0);
                    vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
                    vec3 toLight = light.xyz - localPosition;
                    if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
                    if (!historyStaticLight(localPosition + normal * 0.03, light.xyz,
                            ShadowsEnabled != 0 && radiance.w > 0.5)) { deferredState = 1; break; }
                }
            }
            // Ray-free terms the pass cannot rebuild: per-section far records and which list entries they match.
            for (int i = 0; i < 8 && (deferredState != 0 || EntityCount > 0); i++) {
                if (i >= count) break;
                int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
                if (lightCulled(index)) continue;
                vec4 light = texelFetch(LightData, ivec2(0, index), 0);
                vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
                vec3 contribution = nearContribution(light, radiance, anchor, shadingNormal);
                if (all(equal(contribution, vec3(0.0)))) continue;
                bool matched = FarReady != 0 && farMatched(index);
                if (matched) farMask += exp2(float(i));
                if (deferredState == 0) continue;
                float weight = staticWeight(light.xyz, FarReady != 0, matched);
                if (weight <= 0.0) continue;
                if (matched) farBase -= contribution * weight;
                unshadowed += contribution * weight;
            }
        }
        if (deferredState != 0 && FarReady != 0) {
            for (int i = 0; i < 8; i++) {
                if (i >= FarLightCount) break;
                farBase += nearContribution(FarLightRecords[i * 2], FarLightRecords[i * 2 + 1], anchorSurface, shadingNormal);
            }
        }
        if (deferredState == 2) {
            // No rays this frame: colour from Rayvox, intensity bounded by vanilla flood-fill light to limit leaks.
            vec3 estimate = FarReady != 0 ? max(farBase + unshadowed, vec3(0.0)) : unshadowed;
            const vec3 luma = vec3(0.2126, 0.7152, 0.0722);
            float bound = dot(toLinear(vanillaLight.rgb), luma) + 0.02;
            direct = estimate * min(1.0, bound / max(dot(estimate, luma), 0.0001));
            sunlight = 1.0;
        }
        cachedLight = vec4(direct, sunlight + (deferredState != 0 ? 2.0 : 0.0));
        cachedKey = vec4(anchor, face);
        deferredInputs = vec4(farBase, farMask + (FarReady != 0 ? 256.0 : 0.0) + float(deferredState) * 512.0);
    }
    if (!deferred && inCache) {
        ivec3 sectionSlot = cacheSlot(cell);
        int row = sectionSlot.x + CacheWidth * (sectionSlot.y + CacheWidth * sectionSlot.z);
        ivec3 tile = (cell % 16) / 4;
        int column = (tile.x + 4 * (tile.y + 4 * tile.z)) * 9;
        uint header = texelFetch(LightLists, ivec2(column, row), 0).r;
        if ((texelFetch(VoxelState, sectionSlot, 0).r & 1u) != 0u) {
            vec3 edge = min(localPosition, vec3(CacheWidth * 16) - localPosition);
            replacement = smoothstep(0.0, 16.0, min(edge.x, min(edge.y, edge.z)));
        }
        int count = int(header & 255u);
        if (reused && any(notEqual(HistoryCameraOffset, vec3(0.0)))) {
            for (int i = 0; i < 8; i++) {
                if (i >= count) break;
                int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
                if (lightCulled(index)) continue;
                vec4 light = texelFetch(LightData, ivec2(0, index), 0);
                vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
                vec3 toLight = light.xyz - localPosition;
                if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
                if (!historyStaticLight(localPosition + normal * 0.03, light.xyz,
                        ShadowsEnabled != 0 && radiance.w > 0.5)) { reused = false; break; }
            }
            if (!reused) direct = vec3(0.0);
        }
        for (int i = 0; i < 8 && !reused; i++) {
            if (i >= count) break;
            int index = int(texelFetch(LightLists, ivec2(column + i + 1, row), 0).r);
            if (lightCulled(index)) continue;
            vec4 light = texelFetch(LightData, ivec2(0, index), 0);
            vec4 radiance = texelFetch(LightData, ivec2(1, index), 0);
            vec3 toLight = light.xyz - localPosition;
            if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
            float distanceToLight = length(toLight);
            float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
            float diffuse = max(dot(shadingNormal, toLight / max(distanceToLight, 0.0001)), 0.0);
            if (falloff <= 0.0 || diffuse <= 0.0) continue;
            vec3 visibility = staticVisibility(localPosition + normal * 0.03, light.xyz, index,
                    ShadowsEnabled != 0 && radiance.w > 0.5, tileOccluded(header, i));
            direct += radiance.rgb * (falloff * falloff * diffuse * visibility);
        }
    }
    vec3 farDirect = vec3(0.0);
    if (!deferred && !reused && FarReady != 0) {
        for (int i = 0; i < 8; i++) {
            if (i >= FarLightCount) break;
            vec4 light = FarLightRecords[i * 2];
            vec3 toLight = light.xyz - surfacePosition;
            if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
            float distanceToLight = length(toLight);
            float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
            float diffuse = max(dot(shadingNormal, toLight / max(distanceToLight, 0.0001)), 0.0);
            farDirect += FarLightRecords[i * 2 + 1].rgb * (falloff * falloff * diffuse);
        }
        direct = max(farDirect + direct, vec3(0.0));
    }
    float sunFade = 0.0;
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    // Deferred mode carries scalar sun visibility; coloured sun transmission remains a legacy-path feature.
    vec3 sunTransmission = vec3(sunlight);
    float sunFacing = max(dot(normal, SunDirection.xyz), 0.0);
    sunFade = 1.0 - smoothstep(SunShadowDistance * 0.5, SunShadowDistance, length(surfacePosition));
    if (!deferred && SunDirection.w > 0.0 && sunFade > 0.0 && sunFacing > 0.0) {
        bool axisFace = face > 0.0 && face <= 6.0;
        if (reused && SunHistoryValid != 0 && (AdvancedMaterials == 0 || axisFace)) {
            sunlight = texelFetch(HistoryColor, pixel, 0).a;
            sunTransmission = vec3(sunlight);
            if (AdvancedMaterials != 0 && prior.w >= 8388608.0) {
                sunTransmission = sunColorVisibility(sunlight, prior.w);
                receiver.w = prior.w;
            }
        } else {
            vec3 origin = localPosition + normal * 0.03;
            sunTransmission = softWorldDirectionalTransmission(origin, origin + SunDirection.xyz * SunShadowDistance,
                    CameraFraction + vec3(CameraInCache), SunShadowDistance);
            sunlight = dot(sunTransmission, vec3(1.0 / 3.0));
            if (AdvancedMaterials != 0 && any(notEqual(sunTransmission, sunTransmission.rrr))) {
                if (axisFace) receiver.w = sunColorKey(face, sunTransmission, sunlight);
            } else sunTransmission = vec3(sunlight);
        }
    }
#endif
    if (!deferred) {
        cachedLight = vec4(direct, sunlight);
        // Keep the original sample position so nearest-pixel reuse cannot drift across a surface over many frames.
        cachedKey = reused ? vec4(prior.xyz, receiver.w) : receiver;
    }
    vec3 indirect = vec3(0.0);
    if (IndirectEnabled != 0 && replacement > 0.0) {
        bool giReuse = reused || (deferred && matchedSurface && HistoryStale == 0);
        vec4 accumulated = giReuse ? texelFetch(HistoryIndirect, pixel, 0) : vec4(0.0);
        float count = clamp(accumulated.a, 0.0, 8.0);
        indirect = deferred || count >= 8.0 ? accumulated.rgb : (accumulated.rgb * count
                + bounceSample(localPosition + normal * 0.03, normal, int(count))) / (count + 1.0);
        cachedIndirect = vec4(indirect, deferred ? count : min(count + 1.0, 8.0));
        indirect *= replacement;
    }
    // Deferred moving-light rays run after geometry, independently of static history.
    for (int i = 0; i < 4 && !deferred; i++) {
        if (i >= DynamicCount) break;
        vec4 light = DynamicRecords[i * 2];
        vec4 radiance = DynamicRecords[i * 2 + 1];
        vec3 toLight = light.xyz - surfacePosition;
        if (dot(toLight, toLight) >= light.w * light.w || dot(normal, toLight) <= 0.0) continue;
        float distanceToLight = length(toLight);
        float falloff = max(1.0 - distanceToLight / max(light.w, 0.001), 0.0);
        float diffuse = max(dot(shadingNormal, toLight / max(distanceToLight, 0.0001)), 0.0);
        vec3 visibility = vec3(1.0);
        if (ShadowsEnabled != 0 && radiance.w > 0.5 && falloff * diffuse > 0.0)
            visibility = blockVisibilityCurrent(localPosition + normal * 0.03, light.xyz + CameraFraction + vec3(CameraInCache));
        direct += radiance.rgb * (falloff * falloff * diffuse * visibility);
    }
    vec3 base = mix(toLinear(vanillaLight.rgb), toLinear(skyLight.rgb), FarReady != 0 ? 1.0 : replacement);
    if (deferred) {
        float fog = vertexDistance <= FogStart ? 0.0 : vertexDistance < FogEnd
                ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0;
        geometryBase = vec4(toSrgb(base), replacement);
        geometryEmission = vec4(sourceLight, fog * FogColor.a);
        geometryAlbedo = packSurface(vec4(albedo.rgb, alpha), shadingNormal, material);
    }
    if (!deferred) {
        direct=max(direct-entityDirectOcclusion(localPosition,normal,shadingNormal,0.0),vec3(0));
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
        sunTransmission=max(sunTransmission-entitySunOcclusion(localPosition,normal,shadingNormal),vec3(0));
#endif
    }
    if (DebugMode == 6) {
        if (HistoryAvailable == 0) {
            // Unavailable/off history is different from a new surface or a scene invalidation.
            fragColor = vec4(0.15, 0.35, 0.9, alpha);
            return;
        }
        // Deferred: green fresh, amber stale (awaiting refresh), red newly visible estimate.
        vec3 reuse = deferred ? (deferredState == 0 ? vec3(0.1, 0.8, 0.2) : deferredState == 1 ? vec3(0.9, 0.65, 0.05)
                : vec3(0.8, 0.15, 0.05)) : reused ? vec3(0.1, 0.8, 0.2) : vec3(0.8, 0.15, 0.05);
        fragColor = vec4(reuse, alpha);
        return;
    }
    if (DebugMode == 7) {
        fragColor = vec4(toSrgb(indirect / (vec3(1.0) + indirect)), alpha);
        return;
    }
    if (DebugMode == 5) {
        fragColor = vec4(toSrgb(direct / (vec3(1.0) + direct)), alpha);
        return;
    }
    // Source budgets and dirty flags must never switch vanilla block lighting back on under RGB shadows.
#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
    base = sunlitBase(base, shadingNormal, sunTransmission, length(surfacePosition));
#endif
    // A source's own glow is independent of diffuse normals, occlusion and source-list budgets.
    vec3 illumination = max(max(base, direct) + indirect, toLinear(sourceLight));
    vec3 diffuse = toLinear(albedo.rgb) * illumination * surfaceDiffuseWeight(material.x);
    // Keep self emission independent of the metallic diffuse reduction.
    diffuse = max(diffuse, toLinear(albedo.rgb) * toLinear(sourceLight));
    vec3 specular = deferred ? vec3(0.0) : surfaceReflection(localPosition, normal, shadingNormal, toLinear(albedo.rgb), material);
    vec3 lit = toSrgb(clamp(diffuse + specular, 0.0, 1.0));
    fragColor = linear_fog(vec4(lit, alpha), vertexDistance, FogStart, FogEnd, FogColor);
}
