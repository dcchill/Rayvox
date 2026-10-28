package dev.rayvox;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Runs the ordinary animated player model into a CPU-only sink, including first-person players. */
public final class PlayerShadowCapture {
    // shortcut: 32 cubes cover vanilla player layers; raise the shared limit for larger custom models.
    static final int MAX_PARTS = 32;
    record Part(AABB bounds, Matrix4f inverse) {}
    private static final ThreadLocal<List<Part>> capture = new ThreadLocal<>();
    private static final VertexConsumer SINK = new VertexConsumer() {
        public VertexConsumer addVertex(float x,float y,float z){return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setNormal(float x,float y,float z){return this;}
    };

    private PlayerShadowCapture() {}

    static List<Part> collect(AbstractClientPlayer player, Vec3 camera, float partialTick) {
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof PlayerRenderer model)) return List.of();
        var parts = new ArrayList<Part>();
        var stack = new PoseStack();
        var position = player.getPosition(partialTick).subtract(camera).add(model.getRenderOffset(player, partialTick));
        stack.translate(position.x, position.y, position.z);
        capture.set(parts);
        try {
            model.render(player, Mth.rotLerp(partialTick, player.yRotO, player.getYRot()), partialTick,
                    stack, type -> SINK, 0xF000F0);
        } finally { capture.remove(); }
        return List.copyOf(parts);
    }

    /** Called at cube compilation; suppressing vertex emission avoids drawing the extra model evaluation. */
    public static boolean cube(PoseStack.Pose pose, AABB local) {
        var parts = capture.get();
        if (parts == null) return false;
        if (parts.size() < MAX_PARTS) {
            var center = local.getCenter();
            var matrix = new Matrix4f(pose.pose()).translate((float)center.x, (float)center.y, (float)center.z)
                    .scale((float)Math.max(local.getXsize() * 0.5, 0.0001),
                            (float)Math.max(local.getYsize() * 0.5, 0.0001), (float)Math.max(local.getZsize() * 0.5, 0.0001));
            if (Math.abs(matrix.determinant()) > 1e-12) {
                var origin = matrix.transformPosition(new Vector3f());
                float x = Math.abs(matrix.m00()) + Math.abs(matrix.m10()) + Math.abs(matrix.m20());
                float y = Math.abs(matrix.m01()) + Math.abs(matrix.m11()) + Math.abs(matrix.m21());
                float z = Math.abs(matrix.m02()) + Math.abs(matrix.m12()) + Math.abs(matrix.m22());
                parts.add(new Part(new AABB(origin.x-x,origin.y-y,origin.z-z,origin.x+x,origin.y+y,origin.z+z), matrix.invert()));
            }
        }
        return true;
    }
}
