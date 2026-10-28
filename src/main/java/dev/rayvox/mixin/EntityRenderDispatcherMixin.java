package dev.rayvox.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rayvox.RayvoxEngine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method="renderShadow",at=@At("HEAD"),cancellable=true)
    private static void rayvox$replaceBlob(PoseStack pose,MultiBufferSource buffers,Entity entity,
            float strength,float partialTick,LevelReader level,float radius,CallbackInfo ci) {
        if(RayvoxEngine.replacesEntityShadow(entity))ci.cancel();
    }
}
