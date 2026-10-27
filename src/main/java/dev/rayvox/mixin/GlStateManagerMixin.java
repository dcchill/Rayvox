package dev.rayvox.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.rayvox.render.TextureSnapshots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
abstract class GlStateManagerMixin {
    @Inject(method="_bindTexture",at=@At("HEAD"))
    private static void rayvox$boundTexture(int texture,CallbackInfo ci){TextureSnapshots.bound(texture);}
}
