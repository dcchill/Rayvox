package dev.rayvox.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTarget.class)
public interface RenderTargetAccess {
    @Accessor("colorTextureId") void rayvox$color(int texture);
    @Accessor("depthBufferId") void rayvox$depth(int texture);
}
