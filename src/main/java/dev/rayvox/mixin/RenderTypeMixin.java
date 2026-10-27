package dev.rayvox.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.rayvox.RenderBackends;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderType.class)
abstract class RenderTypeMixin {
    @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
    private void rayvox$captureMesh(MeshData mesh,Operation<Void> original){if(!RenderBackends.capture(mesh,(RenderType)(Object)this))original.call(mesh);}
}
