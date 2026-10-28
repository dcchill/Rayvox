package dev.rayvox.mixin;

import dev.rayvox.SurfaceAtlas;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
    @Inject(method = "upload", at = @At("HEAD"))
    private void rayvox$blockAtlas(CallbackInfo ci) {
        var atlas = (TextureAtlas)(Object)this;
        if (atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) SurfaceAtlas.blocks(atlas.getId());
    }
}
