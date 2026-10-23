package dev.rayvox;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(value = "rayvox", dist = Dist.CLIENT)
public final class Rayvox {
    private static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue CACHE_RADIUS;
    public static final ModConfigSpec.IntValue EXTRACT_BUDGET;
    public static final ModConfigSpec.IntValue UPLOAD_BUDGET;
    public static final ModConfigSpec.IntValue ACTIVE_LIGHTS;
    public static final ModConfigSpec.IntValue LIGHTS_PER_SECTION;
    public static final ModConfigSpec.BooleanValue SHADOWS;
    public static final ModConfigSpec.IntValue SHADOW_DISTANCE;
    public static final ModConfigSpec.BooleanValue HISTORY;
    public static final ModConfigSpec.BooleanValue DYNAMIC;
    public static final ModConfigSpec.BooleanValue SUNLIGHT;
    public static final ModConfigSpec.BooleanValue MATERIALS;
    public static final ModConfigSpec.BooleanValue INDIRECT;
    public static final KeyMapping DEBUG_KEY = new KeyMapping("key.rayvox.debug", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, "key.categories.rayvox");

    static {
        var builder = new ModConfigSpec.Builder();
        ENABLED = builder.comment("Enable Rayvox world-surface RGB lighting.").define("enabled", true);
        CACHE_RADIUS = builder.comment("Voxel radius in 16-block sections; one resolution level.")
                .defineInRange("cacheRadiusSections", 2, 1, 4);
        EXTRACT_BUDGET = builder.comment("Maximum sections extracted per tick, also bounded by a soft 2 ms limit.")
                .defineInRange("extractSectionsPerTick", 2, 1, 16);
        UPLOAD_BUDGET = builder.comment("Maximum 48 KiB material/albedo/shape section uploads per rendered frame.")
                .defineInRange("uploadSectionsPerFrame", 4, 1, 16);
        ACTIVE_LIGHTS = builder.defineInRange("activeLightLimit", 32, 1, LightSourceManager.MAX_LIGHTS);
        LIGHTS_PER_SECTION = builder.comment("Maximum point lights/rays per fragment, selected within 4-block cells. Key retained for existing configs.")
                .defineInRange("lightsPerSection", 4, 1, LightSourceManager.PER_SECTION);
        SHADOWS = builder.define("blockShadows", true);
        SHADOW_DISTANCE = builder.defineInRange("shadowDistance", 16, 1, 16);
        HISTORY = builder.comment("Reuse matching visible surfaces' static lighting; at most 128 MiB, otherwise bypass.")
                .define("lightingHistory", true);
        DYNAMIC = builder.comment("Up to four nearby players holding emissive block items; visual only.")
                .define("heldItemLights", true);
        SUNLIGHT = builder.comment("Experimental bounded sun/moon shadows; retains vanilla sky outside the voxel cache.")
                .define("directionalShadows", false);
        MATERIALS = builder.comment("Experimental RGB glass, water attenuation and quarter-block partial shadow shapes.")
                .define("advancedMaterials", false);
        INDIRECT = builder.comment("Experimental one-bounce voxel GI: one six-block gather per pixel, eight-frame accumulation. Expensive; no dynamic-light bounce.")
                .define("indirectPrototype", false);
        SPEC = builder.build();
    }

    public Rayvox(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(DEBUG_KEY));
        modBus.addListener(RayvoxEngine::registerShaders);
        NeoForge.EVENT_BUS.addListener(Rayvox::tick);
        NeoForge.EVENT_BUS.addListener(RayvoxEngine::renderStage);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        RayvoxEngine.updateLevel(mc.level);
        RayvoxEngine.tickCache();
        while (DEBUG_KEY.consumeClick()) {
            if (mc.level != null && mc.screen == null) mc.setScreen(new RayvoxDebugScreen());
        }
    }
}
