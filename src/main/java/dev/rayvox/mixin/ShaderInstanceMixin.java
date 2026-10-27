package dev.rayvox.mixin;

import dev.rayvox.render.RasterHooks;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
abstract class ShaderInstanceMixin {
    @Inject(method="apply",at=@At("RETURN"))
    private void rayvox$applied(CallbackInfo ci){RasterHooks.applied((ShaderInstance)(Object)this);}
}
