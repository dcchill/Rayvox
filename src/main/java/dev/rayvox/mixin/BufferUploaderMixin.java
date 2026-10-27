package dev.rayvox.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.BufferUploader;
import dev.rayvox.RayvoxEngine;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BufferUploader.class)
abstract class BufferUploaderMixin {
    @ModifyExpressionValue(method = "_drawWithShader", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getShader()Lnet/minecraft/client/renderer/ShaderInstance;"))
    private static ShaderInstance rayvox$entityShader(ShaderInstance vanilla) {
        return RayvoxEngine.entityShader(vanilla);
    }
}
