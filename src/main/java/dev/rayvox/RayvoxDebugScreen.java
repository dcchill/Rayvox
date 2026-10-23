package dev.rayvox;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class RayvoxDebugScreen extends Screen {
    private int scroll;
    private int maxScroll;
    RayvoxDebugScreen() { super(Component.translatable("screen.rayvox.debug")); }

    @Override
    protected void init() {
        int columnWidth = Math.min(220, (width - 24) / 2);
        int x = (width - columnWidth * 2 - 8) / 2;
        int y = height - 176;
        RayvoxEngine.selectLight();
        addRenderableWidget(Button.builder(toggleLabel(), button -> {
            Rayvox.ENABLED.set(!Rayvox.ENABLED.get());
            Rayvox.ENABLED.save();
            button.setMessage(toggleLabel());
        }).bounds(x, y, columnWidth, 20).build());
        addRenderableWidget(Button.builder(viewLabel(), button -> {
            RayvoxEngine.cycleDebugMode();
            button.setMessage(viewLabel());
        }).bounds(x, y + 24, columnWidth, 20).build());
        addRenderableWidget(Button.builder(shadowLabel(), button -> {
            Rayvox.SHADOWS.set(!Rayvox.SHADOWS.get());
            Rayvox.SHADOWS.save();
            button.setMessage(shadowLabel());
        }).bounds(x, y + 48, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.rayvox.place"), button ->
                RayvoxEngine.toggleTestLights()).bounds(x, y + 72, columnWidth, 20).build());
        var inspect = addRenderableWidget(Button.builder(Component.literal("Inspect aimed/selected light"), button ->
                minecraft.setScreen(new LightInspectorScreen(this))).bounds(x, y + 96, columnWidth, 20).build());
        inspect.active = RayvoxEngine.lights().selected != null && !RayvoxEngine.frozen();
        addRenderableWidget(Button.builder(Component.translatable("screen.rayvox.close"), button ->
                onClose()).bounds(x, y + 144, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Indirect light (prototype): " + (Rayvox.INDIRECT.get() ? "ON" : "OFF")), button -> {
            Rayvox.INDIRECT.set(!Rayvox.INDIRECT.get()); Rayvox.INDIRECT.save(); rebuildWidgets();
        }).bounds(x, y + 120, columnWidth, 20).build());
        int right = x + columnWidth + 8;
        addRenderableWidget(Button.builder(Component.literal("Lighting freeze: " + (RayvoxEngine.frozen() ? "ON" : "OFF")), button -> {
            RayvoxEngine.toggleFreeze();
            rebuildWidgets();
        }).bounds(right, y, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Lighting history: " + (Rayvox.HISTORY.get() ? "ON" : "OFF")), button -> {
            Rayvox.HISTORY.set(!Rayvox.HISTORY.get()); Rayvox.HISTORY.save(); rebuildWidgets();
        }).bounds(right, y + 24, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Held item lights: " + (Rayvox.DYNAMIC.get() ? "ON" : "OFF")), button -> {
            Rayvox.DYNAMIC.set(!Rayvox.DYNAMIC.get()); Rayvox.DYNAMIC.save(); rebuildWidgets();
        }).bounds(right, y + 48, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("GPU profiler: " + (RayvoxEngine.profiling() ? "ON" : "OFF")), button -> {
            RayvoxEngine.toggleProfiling(); rebuildWidgets();
        }).bounds(right, y + 72, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Sun/moon shadows (experimental): " + (Rayvox.SUNLIGHT.get() ? "ON" : "OFF")), button -> {
            Rayvox.SUNLIGHT.set(!Rayvox.SUNLIGHT.get()); Rayvox.SUNLIGHT.save(); rebuildWidgets();
        }).bounds(right, y + 96, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Advanced materials: " + (Rayvox.MATERIALS.get() ? "ON" : "OFF")), button -> {
            Rayvox.MATERIALS.set(!Rayvox.MATERIALS.get()); Rayvox.MATERIALS.save(); rebuildWidgets();
        }).bounds(right, y + 120, columnWidth, 20).build());
    }

    private Component viewLabel() { return Component.translatable("screen.rayvox.view", RayvoxEngine.debugModeName()); }
    private Component shadowLabel() { return Component.literal("Block shadows: " + (Rayvox.SHADOWS.get() ? "ON" : "OFF")); }

    private Component toggleLabel() {
        return Component.translatable("screen.rayvox.enable", Rayvox.ENABLED.get() ? "ON" : "OFF (vanilla)");
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(8, 8, width - 8, height - 184, 0xc0101010);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, 16, 16, 0xff8080);
        int y = 32 - scroll;
        graphics.enableScissor(8, 30, width - 8, height - 184);
        for (String line : new String[]{RayvoxEngine.status(), RayvoxEngine.gpu(),
                "Compute 4.3: " + RayvoxEngine.computeAvailable() + " (unused)", RayvoxEngine.lightStats(),
                RayvoxEngine.cacheStats(), RayvoxEngine.uploadStats(),
                RayvoxEngine.distantStats(),
                RayvoxEngine.textureColorStats(),
                RayvoxEngine.historyStats(), RayvoxEngine.timingStats(), RayvoxEngine.dynamicStats(),
                "History reuse: green=reused static light, red=recomputed",
                "Static rays: up to " + Rayvox.LIGHTS_PER_SECTION.get() + "; moving: up to 4; sun: 1; GI: 1 gather + 4 light rays",
                "DDA: 128 steps/ray; shadow distance " + Rayvox.SHADOW_DISTANCE.get() + "; GI distance 6",
                "GI: eight-frame stationary accumulation, static sources only; map-color reflectance",
                "Occupancy: cyan=opaque, orange=partial, blue=transmissive",
                "Dirty: green=clean, amber=pending, purple=missing",
                "Opaque/cutout terrain; unknown paths block RGB light",
                "CPU ray diagnostic uses conservative whole cells; advanced GPU shapes/filters can differ",
                LightDebugView.rayStatus,
                Rayvox.DEBUG_KEY.getTranslatedKeyMessage().getString() + " / Escape closes; light stays in world space"}) {
            for (var wrapped : font.split(Component.literal(line), Math.max(80, width - 32))) {
                graphics.drawString(font, wrapped, 16, y, 0xffffff);
                y += 11;
            }
        }
        maxScroll = Math.max(0, y + scroll - (height - 184));
        scroll = Math.min(scroll, maxScroll);
        graphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - (int)(vertical * 22), 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (Rayvox.DEBUG_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
