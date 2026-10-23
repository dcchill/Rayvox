package dev.rayvox;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class LightDebugView {
    static String rayStatus = "Select a light, then aim at a receiving surface";

    static void render(RenderLevelStageEvent event, LightSourceManager lights, VoxelGrid grid, int mode) {
        if (mode != 3 && mode != 4) return;
        var mc = Minecraft.getInstance();
        var pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        if (mode == 3) {
            for (var light : lights.active) {
                var def = lights.definition(light);
                Vec3 center = new Vec3(light.x(), light.y(), light.z());
                LevelRenderer.renderLineBox(pose, lines, light.x() - camera.x - 0.1, light.y() - camera.y - 0.1,
                        light.z() - camera.z - 0.1, light.x() - camera.x + 0.1, light.y() - camera.y + 0.1,
                        light.z() - camera.z + 0.1, def.red(), def.green(), def.blue(), 1);
                for (int axis = 0; axis < 3; axis++) for (int segment = 0; segment < 32; segment++) {
                    double a = segment * Math.PI / 16, b = (segment + 1) * Math.PI / 16;
                    line(pose, lines, center.add(ring(axis, a, def.radius())), center.add(ring(axis, b, def.radius())),
                            camera, def.red(), def.green(), def.blue());
                }
            }
        } else if (lights.selected != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            var direction = hit.getDirection();
            Vec3 origin = hit.getLocation().add(direction.getStepX() * 0.03, direction.getStepY() * 0.03, direction.getStepZ() * 0.03);
            var light = lights.selected;
            Vec3 target = new Vec3(light.x(), light.y(), light.z());
            var result = ShadowRay.trace(grid, origin.x, origin.y, origin.z, target.x, target.y, target.z);
            Vec3 end = result.visible() ? target : origin.add(target.subtract(origin).normalize().scale(result.distance()));
            line(pose, lines, origin, end, camera, result.visible() ? 0 : 1, result.visible() ? 1 : 0,
                    result.unknown() ? 1 : 0);
            if (!result.visible()) {
                line(pose, lines, end, target, camera, 0.5f, 0.5f, 0.5f);
                LevelRenderer.renderLineBox(pose, lines, result.x() - camera.x, result.y() - camera.y, result.z() - camera.z,
                        result.x() + 1 - camera.x, result.y() + 1 - camera.y, result.z() + 1 - camera.z, 1, 0, 0, 1);
            }
            rayStatus = String.format(java.util.Locale.ROOT, "CPU snapshot ray: %s; cell %d,%d,%d; %.2f blocks",
                    result.unknown() ? "unknown" : result.visible() ? "visible" : "blocked", result.x(), result.y(), result.z(), result.distance());
        } else if (mode == 4) rayStatus = "Select a light, then aim at a receiving surface";
        buffers.endBatch(RenderType.lines());
    }

    private static Vec3 ring(int axis, double angle, double radius) {
        double a = Math.cos(angle) * radius, b = Math.sin(angle) * radius;
        return switch (axis) { case 0 -> new Vec3(0, a, b); case 1 -> new Vec3(a, 0, b); default -> new Vec3(a, b, 0); };
    }

    private static void line(PoseStack pose, VertexConsumer buffer, Vec3 a, Vec3 b, Vec3 camera, float r, float g, float blue) {
        Vec3 direction = b.subtract(a).normalize();
        for (Vec3 point : new Vec3[]{a, b}) buffer.addVertex(pose.last().pose(), (float)(point.x - camera.x),
                (float)(point.y - camera.y), (float)(point.z - camera.z)).setColor(r, g, blue, 1)
                .setNormal((float)direction.x, (float)direction.y, (float)direction.z);
    }
}
