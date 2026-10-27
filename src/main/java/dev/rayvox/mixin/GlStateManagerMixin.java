package dev.rayvox.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.rayvox.render.TextureSnapshots;
import dev.rayvox.render.RasterHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
abstract class GlStateManagerMixin {
    @Inject(method={"_drawElements","_clear","_glBindFramebuffer","_glBlitFrameBuffer","_texSubImage2D","_deleteTexture"},at=@At("HEAD"))
    private static void rayvox$nativeBoundary(CallbackInfo ci){RasterHooks.nativeBoundary();}
    @Inject(method="_texParameter",at=@At("HEAD"))
    private static void rayvox$sampler(CallbackInfo ci){RasterHooks.nativeBoundary();RasterHooks.samplerChanged();}
    @Inject(method="_texImage2D",at=@At("HEAD"))
    private static void rayvox$textureStorage(CallbackInfo ci){TextureSnapshots.deleted(TextureSnapshots.bound());}
    @Inject(method="_bindTexture",at=@At("HEAD"))
    private static void rayvox$boundTexture(int texture,CallbackInfo ci){TextureSnapshots.bound(texture);}
}
