package dev.rayvox.mixin;

import dev.rayvox.RayvoxEngine;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void rayvox$close(CallbackInfo ci) { RayvoxEngine.close(); }
}
