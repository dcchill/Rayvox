package dev.rayvox.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.rayvox.render.RasterHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
abstract class RenderTargetMixin {
    @Inject(method="copyDepthFrom",at=@At("HEAD"),cancellable=true)
    private void rayvox$depth(RenderTarget source,CallbackInfo ci){if(RasterHooks.copyDepth((RenderTarget)(Object)this,source))ci.cancel();}
    @Inject(method="destroyBuffers",at=@At("HEAD"))
    private void rayvox$detach(CallbackInfo ci){RasterHooks.targetChanged((RenderTarget)(Object)this);}
}
