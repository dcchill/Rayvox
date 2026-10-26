package dev.rayvox.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.rayvox.EmissiveLight;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ModelBlockRenderer.class)
abstract class ModelBlockRendererMixin {
    @ModifyArg(method = "putQuadData", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFFF[IIZ)V"), index = 7)
    private int[] rayvox$emission(int[] lightmap, @Local(argsOnly = true) BlockAndTintGetter world,
            @Local(argsOnly = true) BlockState state, @Local(argsOnly = true) BlockPos position) {
        return EmissiveLight.encode(lightmap, state.getLightEmission(world, position));
    }
}
