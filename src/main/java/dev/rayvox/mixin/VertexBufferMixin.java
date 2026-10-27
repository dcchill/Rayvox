package dev.rayvox.mixin;

import com.mojang.blaze3d.vertex.*;
import dev.rayvox.render.RasterHooks;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
abstract class VertexBufferMixin {
    @Shadow @Final private VertexBuffer.Usage usage;
    @Inject(method="upload",at=@At("HEAD"))
    private void rayvox$vertices(MeshData mesh,CallbackInfo ci){RasterHooks.upload((VertexBuffer)(Object)this,mesh);}
    @Inject(method="uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V",at=@At("HEAD"))
    private void rayvox$indices(ByteBufferBuilder.Result indices,CallbackInfo ci){RasterHooks.indices((VertexBuffer)(Object)this,indices.byteBuffer());}
    @Inject(method="draw",at=@At("HEAD"),cancellable=true)
    private void rayvox$draw(CallbackInfo ci){if(RasterHooks.draw((VertexBuffer)(Object)this,usage==VertexBuffer.Usage.STATIC))ci.cancel();}
    @Inject(method="close",at=@At("HEAD"))
    private void rayvox$close(CallbackInfo ci){RasterHooks.close((VertexBuffer)(Object)this);}
}
