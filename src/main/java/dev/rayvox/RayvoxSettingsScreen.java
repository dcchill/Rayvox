package dev.rayvox;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

final class RayvoxSettingsScreen extends Screen {
    private final Screen parent;
    private boolean performance;
    private int next, columnWidth, left;

    RayvoxSettingsScreen(Screen parent) {
        super(Component.literal("Rayvox lighting settings"));
        this.parent = parent;
    }

    @Override protected void init() {
        next = 0;
        columnWidth = Math.min(220, (width - 32) / 2);
        left = (width - columnWidth * 2 - 8) / 2;
        if (performance) {
            slider("Voxel cache radius", Rayvox.CACHE_RADIUS, 1, 4, " sections",
                    "Minimum cache radius. Shadow draw distances may expand it; larger caches use more memory.");
            slider("Shadowed lights", Rayvox.ACTIVE_LIGHTS, 1, LightSourceManager.MAX_LIGHTS, "",
                    "Maximum nearby sources receiving detailed shadows. Ready sections keep RGB lighting beyond this budget.");
            slider("Shadow rays per cell", Rayvox.LIGHTS_PER_SECTION, 1, LightSourceManager.PER_SECTION, "",
                    "Maximum nearby block lights evaluated in detail per surface. Higher limits cost more GPU time.");
            slider("Extraction per tick", Rayvox.EXTRACT_BUDGET, 1, 16, " sections",
                    "Maximum sections scanned each tick, also subject to the soft 2 ms budget.");
            slider("Uploads per frame", Rayvox.UPLOAD_BUDGET, 1, 16, " sections",
                    "Higher limits refresh edited terrain faster but can cause frame spikes.");
        } else {
            toggle("RGB lighting", Rayvox.ENABLED, "Enable Rayvox lighting.");
            toggle("Block shadows", Rayvox.SHADOWS, "Enable shadow rays for block and held lights; also gates sky shadows.");
            slider("Shadow draw distance", Rayvox.SHADOW_DRAW_DISTANCE, 16, 64, " blocks",
                    "Camera range for block and held-light shadows. Fades to unshadowed RGB light outside this range.");
            slider("Shadow ray length", Rayvox.SHADOW_DISTANCE, 1, 16, " blocks",
                    "Maximum source-to-surface shadow ray length. Shorter rays suppress distant light to prevent leaks.");
            toggle("Sky shadows", Rayvox.SUNLIGHT, "Experimental sun/moon shadows. Requires block shadows and a dimension with skylight.");
            slider("Sky shadow draw distance", Rayvox.SUN_SHADOW_DISTANCE, 16, 64, " blocks",
                    "Sun/moon shadow range. Larger distances expand the voxel cache and cost more memory and GPU time.");
            toggle("Held item lights", Rayvox.DYNAMIC, "Light from emissive blocks held by nearby players.");
            toggle("Lighting history", Rayvox.HISTORY, "Reuse lighting on stationary surfaces to reduce GPU work.");
            toggle("Advanced materials", Rayvox.MATERIALS, "Experimental colored glass, water and partial block shadow shapes.");
            toggle("Indirect light", Rayvox.INDIRECT, "Experimental one-bounce lighting. Expensive.");
            toggle("Entity shadows", Rayvox.ENTITY_SHADOWS, "Real-time shadows from up to 16 nearby entities using rounded body bounds. Requires block shadows.");
            slider("Shadow softness", Rayvox.SHADOW_SOFTNESS, 0, 4, "",
                    "0: sharp. 1–4: progressively softer block, entity and sky shadows using four area-light samples. Costs more GPU time.");
        }
        addRenderableWidget(Button.builder(Component.literal(performance ? "Lighting options..." : "Performance options..."), button -> {
            performance = !performance;
            rebuildWidgets();
        }).bounds(left, height - 28, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + columnWidth + 8, height - 28, columnWidth, 20).build());
    }

    private int x() { return left + (next % 2) * (columnWidth + 8); }
    private int y() { return 40 + (next++ / 2) * 24; }

    private void toggle(String label, ModConfigSpec.BooleanValue setting, String help) {
        var button = addRenderableWidget(Button.builder(toggleLabel(label, setting), clicked -> {
            setting.set(!setting.get());
            setting.save();
            clicked.setMessage(toggleLabel(label, setting));
        }).bounds(x(), y(), columnWidth, 20).build());
        button.setTooltip(Tooltip.create(Component.literal(help)));
    }

    private static Component toggleLabel(String label, ModConfigSpec.BooleanValue setting) {
        return Component.literal(label + ": " + (setting.get() ? "ON" : "OFF"));
    }

    private void slider(String label, ModConfigSpec.IntValue setting, int min, int max, String unit, String help) {
        var slider = addRenderableWidget(new AbstractSliderButton(x(), y(), columnWidth, 20, Component.empty(),
                (setting.get() - min) / (double)(max - min)) {
            { updateMessage(); }
            @Override protected void updateMessage() {
                setMessage(Component.literal(label + ": " + setting.get() + unit));
            }
            @Override protected void applyValue() {
                int selected = min + (int)Math.round(value * (max - min));
                if (selected != setting.get()) setting.set(selected);
            }
        });
        slider.setTooltip(Tooltip.create(Component.literal(help)));
    }

    @Override public void removed() {
        // Persist sliders once when leaving; dragging must not write the config every frame.
        Rayvox.ENABLED.save();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        graphics.drawCenteredString(font, performance ? "Performance" : "Lighting", width / 2, 26, 0xaaaaaa);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
