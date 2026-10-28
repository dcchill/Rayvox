package dev.rayvox.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import dev.rayvox.render.TextureSnapshots;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractTexture.class)
abstract class AbstractTextureMixin {
    @Shadow protected int id;
    @Inject(method="releaseId",at=@At("HEAD"))
    private void rayvox$forgetTexture(CallbackInfo ci){if(id!=-1){TextureSnapshots.deleted(id);dev.rayvox.SurfaceAtlas.deleted(id);}}
}
