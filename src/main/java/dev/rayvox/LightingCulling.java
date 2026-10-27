package dev.rayvox;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import net.minecraft.world.phys.AABB;

/** Camera-relative volume tests. Source positions alone are never a visibility test. */
final class LightingCulling {
    private final FrustumIntersection frustum = new FrustumIntersection();
    private double x, y, z;
    private boolean ready;
    int low, high, rejected;

    void camera(double x, double y, double z, Matrix4fc view, Matrix4fc projection) {
        this.x = x; this.y = y; this.z = z;
        frustum.set(new Matrix4f(projection).mul(view));
        ready = true;
    }

    boolean light(LightSourceManager.Light light, LightDefinition definition) {
        // Include surface anchors, normal offsets, and the largest area-light sample.
        float radius = definition.radius() + 0.5f;
        return !ready || frustum.testSphere((float)(light.x() - x), (float)(light.y() - y),
                (float)(light.z() - z), radius);
    }

    boolean caster(AABB bounds, double reach) {
        // A caster outside the view can shadow a receiver up to a full ray length away.
        return !ready || frustum.testAab((float)(bounds.minX - x - reach), (float)(bounds.minY - y - reach),
                (float)(bounds.minZ - z - reach), (float)(bounds.maxX - x + reach),
                (float)(bounds.maxY - y + reach), (float)(bounds.maxZ - z + reach));
    }

    boolean shadowCaster(AABB bounds, DirectionalLight sun, double sunReach, double pointReach) {
        if (caster(bounds, pointReach + 0.2)) return true;
        if (sun.strength() <= 0) return false;
        // Sun rays point toward the source; the shadow extends in the opposite direction.
        var projected = bounds.minmax(bounds.move(-sun.x() * sunReach, -sun.y() * sunReach, -sun.z() * sunReach));
        return caster(projected, sunReach * 0.004 + 0.05);
    }

    void sources(LightSourceManager sources) {
        reset();
        for (int i = 0; i < sources.active.size(); i++) {
            var light = sources.active.get(i);
            if (light(light, sources.definition(light))) continue;
            if (i < 32) low |= 1 << i;
            else high |= 1 << (i - 32);
            rejected++;
        }
    }

    void reset() { low = high = rejected = 0; }
}
