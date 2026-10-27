uniform int CullingDisabled;

// Logical sections and the ring offset are each in [0, CacheWidth); one subtraction wraps their sum.
ivec3 cacheSlot(ivec3 cell) {
    ivec3 slot = cell / 16 + CacheMinSlot;
    return slot - ivec3(greaterThanEqual(slot, ivec3(CacheWidth))) * CacheWidth;
}

int voxelAt(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(CacheWidth * 16)))) {
#ifdef RAYVOX_WORLD_PROBES
        WorldProbeKnown = false;
#endif
        return -1;
    }
    ivec3 sectionSlot = cacheSlot(cell);
    // A pending replacement keeps its last uploaded visibility until the new snapshot commits.
    uint state = texelFetch(VoxelState, sectionSlot, 0).r;
    if ((state & 1u) == 0u) {
#ifdef RAYVOX_WORLD_PROBES
        WorldProbeKnown = false;
#endif
        return -1;
    }
    if ((state & 4u) != 0u) return 0;
    return int(texelFetch(VoxelData, sectionSlot * 16 + cell % 16, 0).r);
}

bool traceBox(uvec2 bits, ivec3 cell, vec3 origin, vec3 ray, float entry, float exit,
        out float hitDistance, out vec3 hitNormal, out float occupied) {
    hitDistance = entry;
    hitNormal = vec3(0.0);
    occupied = 0.0;
    vec3 low = vec3(bits.x & 511u, (bits.x >> 9u) & 511u, (bits.x >> 18u) & 511u) / 256.0;
    vec3 high = vec3(((bits.x >> 27u) | (bits.y << 5u)) & 511u,
            (bits.y >> 4u) & 511u, (bits.y >> 13u) & 511u) / 256.0;
    vec3 local = origin - vec3(cell);
    bvec3 parallel = equal(ray, vec3(0.0));
    if ((parallel.x && (local.x < low.x || local.x >= high.x))
            || (parallel.y && (local.y < low.y || local.y >= high.y))
            || (parallel.z && (local.z < low.z || local.z >= high.z))) return false;
    vec3 inverse = sign(ray) / max(abs(ray), vec3(0.0000001));
    vec3 a = (low - local) * inverse, b = (high - local) * inverse;
    vec3 near = mix(min(a, b), vec3(-1e20), parallel);
    vec3 far = mix(max(a, b), vec3(1e20), parallel);
    float first = max(near.x, max(near.y, near.z));
    float last = min(far.x, min(far.y, far.z));
    hitDistance = max(entry, first);
    occupied = max(min(exit, last) - hitDistance, 0.0);
    if (occupied <= 0.0) return false;
    if (first >= entry) hitNormal = near.z >= near.x && near.z >= near.y ? vec3(0, 0, -sign(ray.z))
            : near.y >= near.x ? vec3(0, -sign(ray.y), 0) : vec3(-sign(ray.x), 0, 0);
    return true;
}

bool traceShape(ivec3 cell, vec3 origin, vec3 ray, float entry, float exit, bool measure, bool boxEncoded,
        out float hitDistance, out vec3 hitNormal, out float occupied) {
    hitDistance = entry;
    hitNormal = vec3(0.0);
    occupied = 0.0;
    ivec3 slot = cacheSlot(cell);
    uvec2 bits = texelFetch(VoxelShapes, slot * 16 + cell % 16, 0).rg;
    if (boxEncoded) return traceBox(bits, cell, origin, ray, entry, exit, hitDistance, hitNormal, occupied);
    if (all(equal(bits, uvec2(0u))) || exit <= entry) return false;
    if (all(equal(bits, uvec2(0xffffffffu)))) { occupied = exit - entry; return true; }
    float start = entry + min(0.00001, (exit - entry) * 0.25);
    vec3 p = clamp((origin + ray * start - vec3(cell)) * 4.0, vec3(0.0), vec3(3.99999));
    ivec3 sub = ivec3(floor(p));
    ivec3 direction = ivec3(sign(ray));
    vec3 delta = 1.0 / max(abs(ray * 4.0), vec3(0.0000001));
    vec3 boundary = mix(p - vec3(sub), vec3(sub + 1) - p, greaterThan(ray, vec3(0.0)));
    vec3 next = vec3(start) + boundary * delta;
    if (direction.x == 0) next.x = 1e20;
    if (direction.y == 0) next.y = 1e20;
    if (direction.z == 0) next.z = 1e20;
    for (int step = 0; step < 16; step++) {
        if (any(lessThan(sub, ivec3(0))) || any(greaterThan(sub, ivec3(3)))) return occupied > 0.0;
        int index = sub.x + 4 * (sub.y + 4 * sub.z);
        uint word = index < 32 ? bits.x : bits.y;
        float nearest = min(next.x, min(next.y, next.z));
        if ((word & (1u << uint(index % 32))) != 0u) {
            if (!measure) return true;
            occupied += max(min(nearest, exit) - hitDistance, 0.0);
        }
        if (nearest >= exit) return occupied > 0.0;
        bvec3 crossed = lessThanEqual(next, vec3(nearest + 0.0000001));
        if (crossed.x) { sub.x += direction.x; next.x += delta.x; hitNormal = vec3(-direction.x, 0, 0); }
        if (crossed.y) { sub.y += direction.y; next.y += delta.y; hitNormal = vec3(0, -direction.y, 0); }
        if (crossed.z) { sub.z += direction.z; next.z += delta.z; hitNormal = vec3(0, 0, -direction.z); }
        hitDistance = nearest;
    }
    return true;
}

bool shapeHit(ivec3 cell, vec3 origin, vec3 ray, float entry, float exit, bool boxEncoded,
        out float hitDistance, out vec3 hitNormal) {
    float occupied;
    return traceShape(cell, origin, ray, entry, exit, false, boxEncoded, hitDistance, hitNormal, occupied);
}

float shapeThickness(ivec3 cell, vec3 origin, vec3 ray, float entry, float exit, bool boxEncoded) {
    float hitDistance, occupied;
    vec3 hitNormal;
    traceShape(cell, origin, ray, entry, exit, true, boxEncoded, hitDistance, hitNormal, occupied);
    return occupied;
}

bool shapeBlocks(ivec3 cell, vec3 origin, vec3 ray, float entry, float exit, bool boxEncoded) {
    float hitDistance;
    vec3 hitNormal;
    return shapeHit(cell, origin, ray, entry, exit, boxEncoded, hitDistance, hitNormal);
}

#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
float directionalShadowWeight(vec3 hit, vec3 camera, float rayDistance, float range) {
    return (1.0 - smoothstep(range * 0.5, range, length(hit - camera)))
            * (1.0 - smoothstep(range * 0.75, range, rayDistance));
}

#ifndef RAYVOX_NO_EMPTY_SECTION_SKIP
int directionalVoxelAt(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(CacheWidth * 16)))) return -1;
    ivec3 slot = cacheSlot(cell);
    uint state = texelFetch(VoxelState, slot, 0).r;
    if ((state & 1u) == 0u) return -1;
    if ((state & 4u) != 0u) return -2;
    return int(texelFetch(VoxelData, slot * 16 + cell % 16, 0).r);
}
#endif

vec3 traceDirectionalTransmission(vec3 origin, vec3 target, vec3 shadowCamera, float shadowRange) {
    vec3 ray = target - origin;
    ivec3 cell = ivec3(floor(origin));
    ivec3 direction = ivec3(sign(ray));
    vec3 delta = 1.0 / max(abs(ray), vec3(0.0000001));
    vec3 boundary = mix(origin - vec3(cell), vec3(cell + 1) - origin, greaterThan(ray, vec3(0.0)));
    vec3 next = boundary * delta;
    if (direction.x == 0) next.x = 1e20;
    if (direction.y == 0) next.y = 1e20;
    if (direction.z == 0) next.z = 1e20;
    vec3 transmission = vec3(1.0);
    float directionalOcclusion = 0.0;
    float entry = 0.0;
    float rayLength = length(ray);
    for (int step = 0; step < 128; step++) {
#ifdef RAYVOX_NO_EMPTY_SECTION_SKIP
        int voxel = voxelAt(cell);
#else
        int voxel = directionalVoxelAt(cell);
        if (voxel == -2) {
            // The uploaded section contains no ray-interacting material. Jump to its exit,
            // retaining the original ray parameter for optical distance and directional fades.
            ivec3 low = (cell / 16) * 16;
            vec3 boundary = mix(vec3(low), vec3(low + 16), greaterThan(ray, vec3(0.0)));
            vec3 sectionNext = (boundary - origin) * vec3(direction) * delta;
            if (direction.x == 0) sectionNext.x = 1e20;
            if (direction.y == 0) sectionNext.y = 1e20;
            if (direction.z == 0) sectionNext.z = 1e20;
            float nearest = min(sectionNext.x, min(sectionNext.y, sectionNext.z));
            if (nearest >= 1.0) return transmission * (1.0 - directionalOcclusion);
            bvec3 crossed = lessThanEqual(sectionNext, vec3(nearest + 0.0000001));
            cell = ivec3(floor(origin + ray * nearest));
            if (crossed.x) cell.x = low.x + (direction.x > 0 ? 16 : -1);
            if (crossed.y) cell.y = low.y + (direction.y > 0 ? 16 : -1);
            if (crossed.z) cell.z = low.z + (direction.z > 0 ? 16 : -1);
            next = mix(origin - vec3(cell), vec3(cell + 1) - origin, greaterThan(ray, vec3(0.0))) * delta;
            if (direction.x == 0) next.x = 1e20;
            if (direction.y == 0) next.y = 1e20;
            if (direction.z == 0) next.z = 1e20;
            entry = nearest;
            continue;
        }
#endif
        if (voxel < 0) return transmission * (1.0 - directionalOcclusion);
        int material = voxel & 63;
        float nearest = min(next.x, min(next.y, next.z));
        float exit = min(nearest, 1.0);
        float hitDistance = entry;
        vec3 hitNormal;
        bool blocked = material == 1 || (material == 2 && (AdvancedMaterials == 0
                || shapeHit(cell, origin, ray, entry, exit, (voxel & 64) != 0, hitDistance, hitNormal)));
        if (blocked) {
            float weight = directionalShadowWeight(origin + ray * hitDistance, shadowCamera,
                    hitDistance * rayLength, shadowRange);
            if (weight >= 1.0) return vec3(0.0);
            directionalOcclusion = max(directionalOcclusion, weight);
#ifndef RAYVOX_NO_DIRECTIONAL_EARLY_EXIT
            // Once moving away from the camera, both caster and ray-end weights only decrease.
            // Basic materials have no transmitting filters that could attenuate a later segment.
            if (AdvancedMaterials == 0 && dot(origin + ray * hitDistance - shadowCamera, ray) >= 0.0)
                return transmission * (1.0 - directionalOcclusion);
#endif
        }
        if (AdvancedMaterials != 0 && (material == 3 || material >= 16)) {
            vec3 tint = MaterialTransmission[min(material, 63)];
            float distance = shapeThickness(cell, origin, ray, entry, exit, (voxel & 64) != 0) * rayLength;
            if (distance > 0.0) {
                vec3 attenuation = pow(tint, vec3(distance));
                attenuation = mix(vec3(1.0), attenuation,
                        directionalShadowWeight(origin + ray * ((entry + exit) * 0.5), shadowCamera,
                                (entry + exit) * 0.5 * rayLength, shadowRange));
                transmission *= attenuation;
            }
            if (all(equal(transmission, vec3(0.0)))) return transmission;
        }
        if (nearest >= 1.0) return transmission * (1.0 - directionalOcclusion);
        bvec3 crossed = lessThanEqual(next, vec3(nearest + 0.0000001));
        if (crossed.x) { cell.x += direction.x; next.x += delta.x; }
        if (crossed.y) { cell.y += direction.y; next.y += delta.y; }
        if (crossed.z) { cell.z += direction.z; next.z += delta.z; }
        entry = nearest;
    }
    return vec3(0.0);
}

#endif

vec3 traceTransmission(vec3 origin, vec3 target, bool environment) {
    vec3 ray = target - origin;
    ivec3 cell = ivec3(floor(origin));
    ivec3 goal = ivec3(floor(target));
    ivec3 direction = ivec3(sign(ray));
    vec3 delta = 1.0 / max(abs(ray), vec3(0.0000001));
    vec3 boundary = mix(origin - vec3(cell), vec3(cell + 1) - origin, greaterThan(ray, vec3(0.0)));
    vec3 next = boundary * delta;
    if (direction.x == 0) next.x = 1e20;
    if (direction.y == 0) next.y = 1e20;
    if (direction.z == 0) next.z = 1e20;
    vec3 transmission = vec3(1.0);
    float entry = 0.0;
    float rayLength = length(ray);
    for (int step = 0; step < 128; step++) {
        int voxel = voxelAt(cell);
        if (voxel < 0) {
#ifdef RAYVOX_WORLD_PROBES
            // Missing geometry never establishes sky visibility for persistent irradiance.
            return vec3(0.0);
#else
            return environment ? transmission : vec3(0.0);
#endif
        }
        // Opaque emitters illuminate outward from their own cell.
        if (!environment && all(equal(cell, goal))) return transmission;
        int material = voxel & 63;
        float nearest = min(next.x, min(next.y, next.z));
        float exit = min(nearest, 1.0);
        if (material == 1 || (material == 2 && (AdvancedMaterials == 0 || shapeBlocks(cell, origin, ray, entry, exit, (voxel & 64) != 0)))) return vec3(0.0);
        if (AdvancedMaterials != 0 && (material == 3 || material >= 16)) {
            vec3 tint = MaterialTransmission[min(material, 63)];
            float distance = shapeThickness(cell, origin, ray, entry, exit, (voxel & 64) != 0) * rayLength;
            if (distance > 0.0) transmission *= pow(tint, vec3(distance));
            if (all(equal(transmission, vec3(0.0)))) return transmission;
        }
        if (nearest >= 1.0) return transmission;
        bvec3 crossed = lessThanEqual(next, vec3(nearest + 0.0000001));
        if (crossed.x) { cell.x += direction.x; next.x += delta.x; }
        if (crossed.y) { cell.y += direction.y; next.y += delta.y; }
        if (crossed.z) { cell.z += direction.z; next.z += delta.z; }
        entry = nearest;
    }
    return vec3(0.0);
}

#ifndef RAYVOX_NO_DIRECTIONAL_SHADOWS
float traceDirectionalVisibility(vec3 origin, vec3 direction, vec3 camera, float range) {
    return dot(traceDirectionalTransmission(origin, origin + direction * range, camera, range), vec3(1.0 / 3.0));
}
#endif

float traceVisibility(vec3 origin, vec3 target, bool environment) {
    return dot(traceTransmission(origin, target, environment), vec3(1.0 / 3.0));
}
