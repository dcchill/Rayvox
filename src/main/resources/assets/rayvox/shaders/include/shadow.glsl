int voxelAt(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(CacheWidth * 16)))) return -1;
    ivec3 sectionSlot = (cell / 16 + CacheMinSlot) % CacheWidth;
    // A pending replacement keeps its last uploaded visibility until the new snapshot commits.
    if ((texelFetch(VoxelState, sectionSlot, 0).r & 1u) == 0u) return -1;
    return int(texelFetch(VoxelData, sectionSlot * 16 + cell % 16, 0).r);
}

bool shapeBlocks(ivec3 cell, vec3 origin, vec3 ray, float entry, float exit) {
    ivec3 slot = (cell / 16 + CacheMinSlot) % CacheWidth;
    uvec2 bits = texelFetch(VoxelShapes, slot * 16 + cell % 16, 0).rg;
    if (all(equal(bits, uvec2(0u))) || exit <= entry) return false;
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
        if (any(lessThan(sub, ivec3(0))) || any(greaterThan(sub, ivec3(3)))) return false;
        int index = sub.x + 4 * (sub.y + 4 * sub.z);
        uint word = index < 32 ? bits.x : bits.y;
        if ((word & (1u << uint(index % 32))) != 0u) return true;
        float nearest = min(next.x, min(next.y, next.z));
        if (nearest >= exit) return false;
        bvec3 crossed = lessThanEqual(next, vec3(nearest + 0.0000001));
        if (crossed.x) { sub.x += direction.x; next.x += delta.x; }
        if (crossed.y) { sub.y += direction.y; next.y += delta.y; }
        if (crossed.z) { sub.z += direction.z; next.z += delta.z; }
    }
    return true;
}

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
    for (int step = 0; step < 128; step++) {
        int material = voxelAt(cell);
        if (material < 0) return environment ? transmission : vec3(0.0);
        // Opaque emitters illuminate outward from their own cell.
        if (!environment && all(equal(cell, goal))) return transmission;
        float nearest = min(next.x, min(next.y, next.z));
        float exit = min(nearest, 1.0);
        if (material == 1 || (material == 2 && (AdvancedMaterials == 0 || shapeBlocks(cell, origin, ray, entry, exit)))) return vec3(0.0);
        if (AdvancedMaterials != 0 && (material == 3 || material >= 16)) {
            vec3 tint = MaterialTransmission[min(material, 63)];
            float distance = max(exit - entry, 0.0) * length(ray);
            transmission *= pow(max(tint, vec3(0.00001)), vec3(distance));
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

float traceVisibility(vec3 origin, vec3 target, bool environment) {
    return dot(traceTransmission(origin, target, environment), vec3(1.0 / 3.0));
}
