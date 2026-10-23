package dev.rayvox.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.Uniform;
import dev.rayvox.RayvoxEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Uniform;set(FFF)V"))
    private void rayvox$sectionLights(Uniform uniform, float x, float y, float z, Operation<Void> original,
            RenderType layer, double cameraX, double cameraY, double cameraZ, Matrix4f modelView, Matrix4f projection) {
        original.call(uniform, x, y, z);
        RayvoxEngine.sectionLights((int)Math.round(cameraX + x), (int)Math.round(cameraY + y),
                (int)Math.round(cameraZ + z), cameraX, cameraY, cameraZ);
    }
    @ModifyExpressionValue(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getShader()Lnet/minecraft/client/renderer/ShaderInstance;"))
    private ShaderInstance rayvox$terrainShader(ShaderInstance vanilla, RenderType layer,
            double cameraX, double cameraY, double cameraZ, Matrix4f modelView, Matrix4f projection) {
        return RayvoxEngine.terrainShader(vanilla, layer, cameraX, cameraY, cameraZ);
    }
}
