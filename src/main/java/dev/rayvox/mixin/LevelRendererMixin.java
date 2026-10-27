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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void rayvox$beginWorld(CallbackInfo ci) { dev.rayvox.RenderBackends.beginFrame();RayvoxEngine.renderingWorld(true); }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void rayvox$endWorld(CallbackInfo ci) { dev.rayvox.RenderBackends.endFrame();RayvoxEngine.renderingWorld(false); }

    @WrapOperation(method="renderSectionLayer",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void rayvox$vulkanTerrain(com.mojang.blaze3d.vertex.VertexBuffer buffer,Operation<Void> original) {
        if(!dev.rayvox.RenderBackends.active())original.call(buffer);
    }

    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean rayvox$nearbyRayGeometry(net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
            net.minecraft.world.entity.Entity entity,net.minecraft.client.renderer.culling.Frustum frustum,double x,double y,double z,Operation<Boolean> original) {
        return dev.rayvox.RenderBackends.active()&&entity.distanceToSqr(x,y,z)<64*64||original.call(dispatcher,entity,frustum,x,y,z);
    }

    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean rayvox$entitySection(LevelRenderer renderer,net.minecraft.core.BlockPos position,Operation<Boolean> original) {
        return dev.rayvox.RenderBackends.active()||original.call(renderer,position);
    }

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
