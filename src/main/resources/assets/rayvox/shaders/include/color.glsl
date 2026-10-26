vec3 toLinear(vec3 value) {
    return mix(value / 12.92, pow((value + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), value));
}

vec3 toSrgb(vec3 value) {
    return mix(value * 12.92, 1.055 * pow(value, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), value));
}

