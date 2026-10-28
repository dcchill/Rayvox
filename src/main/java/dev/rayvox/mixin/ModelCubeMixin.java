package dev.rayvox.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rayvox.PlayerShadowCapture;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.Cube.class)
abstract class ModelCubeMixin {
    @Unique private AABB rayvox$bounds;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rayvox$bounds(int u,int v,float x,float y,float z,float dx,float dy,float dz,
            float inflateX,float inflateY,float inflateZ,boolean mirror,float width,float height,Set<Direction> faces,CallbackInfo ci) {
        rayvox$bounds = new AABB((x-inflateX)/16.0,(y-inflateY)/16.0,(z-inflateZ)/16.0,
                (x+dx+inflateX)/16.0,(y+dy+inflateY)/16.0,(z+dz+inflateZ)/16.0);
    }

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void rayvox$capture(PoseStack.Pose pose,VertexConsumer consumer,int light,int overlay,int color,CallbackInfo ci) {
        if (PlayerShadowCapture.cube(pose, rayvox$bounds)) ci.cancel();
    }
}
