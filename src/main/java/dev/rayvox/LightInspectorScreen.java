package dev.rayvox;

import java.util.Locale;
import java.util.function.UnaryOperator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LightInspectorScreen extends Screen {
    private final Screen parent;
    private int color;
    LightInspectorScreen(Screen parent) { super(Component.literal("Rayvox light inspector")); this.parent = parent; }

    @Override protected void init() {
        int x = width / 2 - 110, y = height - 128;
        add("Cycle color", y, def -> {
            color = (color + 1) % 3;
            return new LightDefinition(color == 1 ? 0 : 1, color == 2 ? 0.65f : 0,
                    color == 1 ? 1 : 0, def.intensity(), def.radius(), def.shadows());
        });
        add("Cycle intensity", y + 24, def -> new LightDefinition(def.red(), def.green(), def.blue(),
                def.intensity() >= 4 ? 0.5f : def.intensity() + 0.5f, def.radius(), def.shadows()));
        add("Cycle radius", y + 48, def -> new LightDefinition(def.red(), def.green(), def.blue(), def.intensity(),
                def.radius() >= 16 ? 4 : Math.min(16, def.radius() + 2), def.shadows()));
        add("Toggle selected light shadows", y + 72, def -> new LightDefinition(def.red(), def.green(), def.blue(),
                def.intensity(), def.radius(), !def.shadows()));
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose()).bounds(x, y + 96, 220, 20).build());
    }

    private void add(String label, int y, UnaryOperator<LightDefinition> edit) {
        var button = addRenderableWidget(Button.builder(Component.literal(label), ignored -> {
            var manager = RayvoxEngine.lights();
            if (manager.selected != null) manager.adjust(edit.apply(manager.definition(manager.selected)));
        }).bounds(width / 2 - 110, y, 220, 20).build());
        button.active = RayvoxEngine.lights().selected != null;
    }

    @Override public void render(GuiGraphics graphics, int x, int y, float partialTick) {
        super.render(graphics, x, y, partialTick);
        var manager = RayvoxEngine.lights();
        var light = manager.selected;
        graphics.drawString(font, title, 16, 12, 0xff8080);
        if (light == null) { graphics.drawString(font, "Source removed or no longer active", 16, 32, 0xffffff); return; }
        var def = manager.definition(light);
        String[] lines = {light.type(), String.format(Locale.ROOT, "Position: %.2f, %.2f, %.2f (static)", light.x(), light.y(), light.z()),
                String.format(Locale.ROOT, "RGB: %.2f %.2f %.2f / intensity %.2f", def.red(), def.green(), def.blue(), def.intensity()),
                String.format(Locale.ROOT, "Color code: #%02X%02X%02X", Math.round(def.red() * 255), Math.round(def.green() * 255), Math.round(def.blue() * 255)),
                "Radius " + def.radius() + "; squared falloff; shadows " + def.shadows(),
                "Temporary client-only edits; reset on reload/world change"};
        graphics.enableScissor(8, 28, width - 8, height - 136);
        int offset = 30;
        for (String line : lines) { graphics.drawString(font, line, 16, offset, 0xffffff); offset += 13; }
        graphics.disableScissor();
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
