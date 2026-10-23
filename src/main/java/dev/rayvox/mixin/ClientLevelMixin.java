package dev.rayvox.mixin;

import dev.rayvox.RayvoxEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void rayvox$blockChanged(BlockPos position, BlockState before, BlockState after, CallbackInfo ci) {
        RayvoxEngine.blockChanged((ClientLevel)(Object)this, position.getX(), position.getY(), position.getZ());
    }

    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void rayvox$chunkLoaded(ChunkPos position, CallbackInfo ci) {
        RayvoxEngine.columnChanged((ClientLevel)(Object)this, position.x, position.z, false);
    }

    @Inject(method = "unload", at = @At("TAIL"))
    private void rayvox$chunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
        RayvoxEngine.columnChanged((ClientLevel)(Object)this, chunk.getPos().x, chunk.getPos().z, true);
    }
}
