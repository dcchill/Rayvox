package dev.rayvox.benchmark.mixin;

import dev.rayvox.benchmark.ClientBenchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class FrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void rayvox$beginFrame(boolean tick, CallbackInfo ci) { ClientBenchmark.beginFrame(); }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void rayvox$rendered(boolean tick, CallbackInfo ci) { ClientBenchmark.rendered(); }

    @Inject(method = "runTick", at = @At("TAIL"))
    private void rayvox$endFrame(boolean tick, CallbackInfo ci) { ClientBenchmark.endFrame(); }
}
