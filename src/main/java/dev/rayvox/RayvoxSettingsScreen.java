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
    private int next, columnWidth, left,rowSpacing;

    RayvoxSettingsScreen(Screen parent) {
        super(Component.literal("Rayvox rendering settings"));
        this.parent = parent;
    }

    @Override protected void init() {
        next = 0;
        rowSpacing=Math.max(20,Math.min(24,(height-68)/8));
        columnWidth = Math.min(220, (width - 32) / 2);
        left = (width - columnWidth * 2 - 8) / 2;
        boolean vulkan=RenderBackends.active();
        if(vulkan) {
            renderer();
            toggle("Rayvox rendering",Rayvox.ENABLED,"Enable the selected renderer.");
            toggle("Sun and moon",Rayvox.SUNLIGHT,"Enable directional sun and moon illumination.");
            toggle("Held item lights",Rayvox.DYNAMIC,"Emissive held blocks illuminate the path-traced world.");
            slider("GPU memory",Rayvox.VULKAN_MEMORY,512,8192," MiB","Renderer memory budget. Restart required.");
        } else if (performance) {
            slider("Vulkan memory",Rayvox.VULKAN_MEMORY,512,8192," MiB","Vulkan GPU memory budget. Restart required.");
            toggle("Lighting culling", Rayvox.CULLING,
                    "Skip lighting and shadow work outside the view or fully blocked by opaque geometry. Disable to compare performance.");
            slider("History memory", Rayvox.HISTORY_MEMORY, 64, 2048, " MiB",
                    "GPU memory limit for lighting history. Full-resolution 4K with deferred indirect lighting needs about 791 MiB. Too small a budget disables reuse.");
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
            cycle("Preset", () -> Rayvox.PRESET.get().name(), () -> {
                var presets = new QualityPreset[]{QualityPreset.PERFORMANCE, QualityPreset.BALANCED, QualityPreset.QUALITY};
                int index = java.util.Arrays.asList(presets).indexOf(Rayvox.PRESET.get());
                presets[(index + 1) % presets.length].apply();
                rebuildWidgets();
            }, "Performance: integrated GPUs. Balanced: midrange. Quality: high-end. Selects pipeline, refresh, shadow, light and governor budgets.");
            cycle("Pipeline", () -> Rayvox.PIPELINE.get().name(), () -> {
                Rayvox.PIPELINE.set(Rayvox.PIPELINE.get() == LightingPipeline.LEGACY ? LightingPipeline.DEFERRED : LightingPipeline.LEGACY);
                Rayvox.PRESET.set(QualityPreset.CUSTOM);
            }, "LEGACY: original inline tracing (reference). DEFERRED: terrain samples cached lighting; a separate pass traces only new/stale pixels. Requires lighting history.");
            slider("Deferred refresh", Rayvox.REFRESH_INTERVAL, 1, 4, " frames",
                    "Frames over which stale cached pixels are re-traced. Higher is cheaper but lags lighting changes. New surfaces are always traced.");
            slider("Indirect update interval", Rayvox.INDIRECT_INTERVAL, 1, 8, " frames",
                    "DEFERRED GI gathers progressive samples in a separate pass. Higher intervals reduce work while converging; new surfaces start immediately.");
            cycle("Governor target", () -> Rayvox.GOVERNOR_TARGET.get() == 0 ? "OFF" : Rayvox.GOVERNOR_TARGET.get() + " FPS", () -> {
                int[] targets = {0, 30, 60, 90, 120};
                int next = 0;
                for (int i = 0; i < targets.length; i++) if (targets[i] == Rayvox.GOVERNOR_TARGET.get()) next = (i + 1) % targets.length;
                Rayvox.GOVERNOR_TARGET.set(targets[next]);
                Rayvox.PRESET.set(QualityPreset.CUSTOM);
            }, "Lowers shadow softness, distances, lights per cell, GI and refresh rate when Rayvox GPU time exceeds the frame budget. Holds when CPU-bound.");
            slider("Governor min level", Rayvox.GOVERNOR_MIN, 0, 4, "", "Lowest quality level the governor may select.");
            slider("Governor max level", Rayvox.GOVERNOR_MAX, 0, 4, "", "Highest quality level; 4 applies no caps to configured settings.");
        } else {
            renderer();
            toggle("RGB lighting", Rayvox.ENABLED, "Enable Rayvox lighting.");
            toggle("Block shadows", Rayvox.SHADOWS, "Enable shadow rays for block and held lights; also gates sky shadows.");
            slider("Shadow draw distance", Rayvox.SHADOW_DRAW_DISTANCE, 16, 64, " blocks",
                    "Camera range for block and held-light shadows. Fades to unshadowed RGB light outside this range.");
            slider("Shadow ray length", Rayvox.SHADOW_DISTANCE, 1, 16, " blocks",
                    "Maximum source-to-surface shadow ray length. Shorter rays suppress distant light to prevent leaks.");
            toggle("Sunlight", Rayvox.SUNLIGHT, "Warm sun and cool moon illumination with traced shadows. Requires block shadows and a normal sky.");
            toggle("Godrays", Rayvox.GODRAYS, "Volumetric sunlight through terrain gaps. Requires Sunlight. Evaluated at quarter resolution; adds GPU work.");
            slider("Sky shadow draw distance", Rayvox.SUN_SHADOW_DISTANCE, 16, 64, " blocks",
                    "Sun/moon shadow range. Larger distances expand the voxel cache and cost more memory and GPU time.");
            toggle("Held item lights", Rayvox.DYNAMIC, "Light from emissive blocks held by nearby players.");
            toggle("Lighting history", Rayvox.HISTORY, "Reuse lighting on stationary surfaces to reduce GPU work.");
            toggle("Advanced materials", Rayvox.MATERIALS, "Experimental colored glass, water and partial block shadow shapes.");
            toggle("Indirect light", Rayvox.INDIRECT, "Experimental one-bounce lighting. Expensive.");
            toggle("World indirect cache", Rayvox.WORLD_INDIRECT, "Experimental four-block probes reuse bounce lighting as you move. Requires Indirect light, DEFERRED pipeline and Lighting history.");
            toggle("Entity shadows", Rayvox.ENTITY_SHADOWS, "Real-time shadows from up to 16 nearby entities using rounded body bounds. Requires block shadows.");
            slider("Shadow softness", Rayvox.SHADOW_SOFTNESS, 0, 4, "",
                    "0: sharp. 1–4: progressively softer block, entity and sky shadows using four area-light samples. Costs more GPU time.");
        }
        addRenderableWidget(Button.builder(Component.literal(vulkan?"Diagnostics...":performance ? "Lighting options..." : "Performance options..."), button -> {
            if(vulkan){minecraft.setScreen(new RayvoxDebugScreen());return;}
            performance = !performance;
            rebuildWidgets();
        }).bounds(left, height - 28, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + columnWidth + 8, height - 28, columnWidth, 20).build());
    }

    private int x() { return left + (next % 2) * (columnWidth + 8); }
    private int y() { return 40 + (next++ / 2) * rowSpacing; }

    private void renderer() {
        cycle("Renderer",()->Rayvox.RENDER_BACKEND.get().name(),()->{
            var modes=dev.rayvox.render.BackendMode.values();
            Rayvox.RENDER_BACKEND.set(modes[(Rayvox.RENDER_BACKEND.get().ordinal()+1)%modes.length]);Rayvox.RENDER_BACKEND.save();
        },"Restart required. AUTO chooses a validated available backend. Vulkan requires a compatible ray-tracing GPU and Fast/Fancy graphics.");
    }

    private void toggle(String label, ModConfigSpec.BooleanValue setting, String help) {
        var button = addRenderableWidget(Button.builder(toggleLabel(label, setting), clicked -> {
            setting.set(!setting.get());
            Rayvox.PRESET.set(QualityPreset.CUSTOM);
            setting.save();
            clicked.setMessage(toggleLabel(label, setting));
        }).bounds(x(), y(), columnWidth, 20).build());
        button.setTooltip(Tooltip.create(Component.literal(help)));
    }

    private void cycle(String label, java.util.function.Supplier<String> value, Runnable action, String help) {
        var button = addRenderableWidget(Button.builder(Component.literal(label + ": " + value.get()), clicked -> {
            action.run();
            clicked.setMessage(Component.literal(label + ": " + value.get()));
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
                if (selected != setting.get()) { setting.set(selected); Rayvox.PRESET.set(QualityPreset.CUSTOM); }
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
        graphics.drawCenteredString(font, RenderBackends.active()?"Vulkan path tracing":performance ? "OpenGL performance" : "OpenGL lighting", width / 2, 26, 0xaaaaaa);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
