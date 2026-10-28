package dev.rayvox.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.rayvox.SurfaceAtlas;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
abstract class SpriteContentsMixin {
    @Shadow @Final private ResourceLocation name;
    @Shadow @Final private int width;
    @Shadow @Final private int height;

    @Inject(method = "upload", at = @At("TAIL"))
    private void rayvox$surface(int x, int y, int sx, int sy, NativeImage[] images, CallbackInfo ci) {
        SurfaceAtlas.upload(name, x, y, sx, sy, width, height, images);
    }
}
