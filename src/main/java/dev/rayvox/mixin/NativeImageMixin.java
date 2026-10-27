package dev.rayvox.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.rayvox.render.TextureSnapshots;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeImage.class)
abstract class NativeImageMixin {
    @Shadow private long pixels;
    @Shadow @Final private int width;
    @Shadow @Final private NativeImage.Format format;
    @Inject(method="_upload",at=@At("HEAD"))
    private void rayvox$capturePixels(int level,int x,int y,int sx,int sy,int w,int h,boolean blur,boolean clamp,boolean mipmap,boolean close,CallbackInfo ci) {
        dev.rayvox.render.RasterHooks.nativeBoundary();
        if(!TextureSnapshots.interested())return;
        TextureSnapshots.upload(TextureSnapshots.bound(),level,x,y,sx,sy,w,h,width,format.components(),pixels);
    }
}
