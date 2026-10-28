package dev.rayvox;

import com.mojang.blaze3d.platform.InputConstants;
import dev.rayvox.render.AntiAliasing;
import dev.rayvox.render.UpscalingMode;
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
    public static final ModConfigSpec.EnumValue<dev.rayvox.render.BackendMode> RENDER_BACKEND;
    public static final ModConfigSpec.IntValue VULKAN_MEMORY;
        public static final ModConfigSpec.EnumValue<AntiAliasing> ANTI_ALIASING;
        public static final ModConfigSpec.EnumValue<UpscalingMode> UPSCALING;
    public static final ModConfigSpec.EnumValue<dev.rayvox.render.Upscaler> UPSCALER;
        public static final ModConfigSpec.IntValue UPSCALE_SHARPNESS;
    private static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue CACHE_RADIUS;
    public static final ModConfigSpec.IntValue EXTRACT_BUDGET;
    public static final ModConfigSpec.IntValue UPLOAD_BUDGET;
    public static final ModConfigSpec.IntValue ACTIVE_LIGHTS;
    public static final ModConfigSpec.IntValue LIGHTS_PER_SECTION;
    public static final ModConfigSpec.BooleanValue SHADOWS;
    public static final ModConfigSpec.BooleanValue SHADER_REFLECTIONS;
    public static final ModConfigSpec.BooleanValue CULLING;
    public static final ModConfigSpec.IntValue SHADOW_DISTANCE;
    public static final ModConfigSpec.IntValue SHADOW_DRAW_DISTANCE;
    public static final ModConfigSpec.BooleanValue HISTORY;
    public static final ModConfigSpec.IntValue HISTORY_MEMORY;
    public static final ModConfigSpec.BooleanValue DYNAMIC;
    public static final ModConfigSpec.BooleanValue SUNLIGHT;
    public static final ModConfigSpec.BooleanValue GODRAYS;
    public static final ModConfigSpec.IntValue SUN_SHADOW_DISTANCE;
    public static final ModConfigSpec.BooleanValue MATERIALS;
    public static final ModConfigSpec.BooleanValue TEXTURE_BUMP;
    public static final ModConfigSpec.BooleanValue METAL_REFLECTIONS;
    public static final ModConfigSpec.BooleanValue INDIRECT;
    public static final ModConfigSpec.BooleanValue WORLD_INDIRECT;
    public static final ModConfigSpec.IntValue INDIRECT_INTERVAL;
    public static final ModConfigSpec.BooleanValue ENTITY_SHADOWS;
    public static final ModConfigSpec.IntValue SHADOW_SOFTNESS;
    public static final ModConfigSpec.EnumValue<LightingPipeline> PIPELINE;
    public static final ModConfigSpec.IntValue REFRESH_INTERVAL;
    public static final ModConfigSpec.EnumValue<QualityPreset> PRESET;
    public static final ModConfigSpec.IntValue GOVERNOR_TARGET;
    public static final ModConfigSpec.IntValue GOVERNOR_MIN;
    public static final ModConfigSpec.IntValue GOVERNOR_MAX;
    public static final KeyMapping DEBUG_KEY = new KeyMapping("key.rayvox.debug", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, "key.categories.rayvox");

    static {
        var builder = new ModConfigSpec.Builder();
        RENDER_BACKEND = builder.comment("Renderer selection; applies between frames. AUTO retains OpenGL until Vulkan playable acceptance is complete. VULKAN_VANILLA preserves Minecraft lighting; VULKAN_SHADER adds rasterized sun/moon shadows and screen-space reflections without ray-tracing hardware; VULKAN_PATH_TRACING uses path-traced lighting.")
                .defineEnum("renderBackend",dev.rayvox.render.BackendMode.AUTO);
        VULKAN_MEMORY = builder.comment("Vulkan GPU memory budget in MiB; applies when a Vulkan renderer initializes.")
                .defineInRange("vulkanMemoryMiB",2048,512,8192);
        ANTI_ALIASING = builder.comment("World-only anti-aliasing; OFF, FXAA, or path-tracing-only TAA. Does not filter the HUD.")
                .defineEnum("antiAliasing", AntiAliasing.OFF);
        UPSCALING = builder.comment("Internal render resolution. Raster Vulkan uses spatial upscaling; path tracing retains temporal reconstruction. ADAPTIVE uses delayed GPU timings on the path tracer only and stays native in raster modes.")
                .defineEnum("upscaling", UpscalingMode.ADAPTIVE);
        UPSCALER = builder.comment("Path-tracing upscaler: RECONSTRUCTION or optional NVIDIA DLSS. DLSS includes AA; unavailable DLSS falls back to reconstruction and FXAA (or selected TAA). Requires an NGX-enabled build and supported NVIDIA GPU/driver.")
                .defineEnum("upscaler", dev.rayvox.render.Upscaler.RECONSTRUCTION);
        UPSCALE_SHARPNESS = builder.comment("World-only upscaling sharpness, 0..100; 0 disables sharpening. Does not sharpen the HUD.")
                .defineInRange("upscaleSharpness", 20, 0, 100);
        ENABLED = builder.comment("Enable the selected Rayvox renderer/lighting. OFF uses native vanilla rendering.").define("enabled", true);
        CACHE_RADIUS = builder.comment("Voxel radius in 16-block sections; one resolution level.")
                .defineInRange("cacheRadiusSections", 2, 1, 4);
        EXTRACT_BUDGET = builder.comment("Maximum sections extracted per tick, also bounded by a soft 2 ms limit.")
                .defineInRange("extractSectionsPerTick", 2, 1, 16);
        UPLOAD_BUDGET = builder.comment("Maximum 48 KiB material/albedo/shape section uploads per rendered frame.")
                .defineInRange("uploadSectionsPerFrame", 4, 1, 16);
        ACTIVE_LIGHTS = builder.comment("Maximum nearby sources receiving detailed shadows; ready sections retain RGB lighting outside this budget.")
                .defineInRange("activeLightLimit", 32, 1, LightSourceManager.MAX_LIGHTS);
        LIGHTS_PER_SECTION = builder.comment("Maximum point lights/rays per fragment, selected within 4-block cells. Key retained for existing configs.")
                .defineInRange("lightsPerSection", 4, 1, LightSourceManager.PER_SECTION);
        SHADOWS = builder.define("blockShadows", true);
        SHADER_REFLECTIONS = builder.comment("Screen-space reflections in VULKAN_SHADER; only visible surfaces can be reflected.")
                .define("shaderReflections", true);
        CULLING = builder.comment("Cull lighting and shadow work using the camera frustum and conservative voxel occlusion.")
                .define("lightingCulling", true);
        ENTITY_SHADOWS = builder.comment("Real-time shadows from up to 16 nearby visible living/solid entities. Uses rounded bounds rather than animated model geometry.")
                .define("entityShadows", true);
        SHADOW_SOFTNESS = builder.comment("Shadow edge softness: 0 is sharp; 1..4 use four stable area-light samples. Higher GPU cost than sharp shadows.")
                .defineInRange("shadowSoftness", 2, 0, 4);
        SHADOW_DISTANCE = builder.comment("Maximum block-light shadow ray length; independent of camera draw distance.")
                .defineInRange("shadowDistance", 16, 1, 16);
        SHADOW_DRAW_DISTANCE = builder.comment("Block shadow draw distance from the camera in blocks. Fades to unshadowed RGB light. Expands the voxel cache while shadows are enabled.")
                .defineInRange("shadowDrawDistance", 32, 16, 64);
        HISTORY = builder.comment("Reuse matching visible surfaces' static lighting within the configured GPU memory budget.")
                .define("lightingHistory", true);
        HISTORY_MEMORY = builder.comment("Maximum GPU memory for lighting history in MiB. 1024 supports full-resolution 4K history with deferred GI. Lower budgets may bypass history or fall back to LEGACY.")
                .defineInRange("lightingHistoryMemoryMiB", 1024, 64, 2048);
        DYNAMIC = builder.comment("Up to four nearby players holding emissive block items; visual only.")
                .define("heldItemLights", true);
        SUNLIGHT = builder.comment("Traced warm sunlight and cool moonlight; retains vanilla sky outside the voxel cache.")
                .define("directionalShadows", true);
        GODRAYS = builder.comment("Quarter-resolution volumetric sunlight shafts, bounded to 32 blocks. Requires directional shadows; additional GPU cost.")
                .define("godrays", true);
        SUN_SHADOW_DISTANCE = builder.comment("Sun/moon shadow range in blocks, with gradual receiver and caster fades. Expands the voxel cache only while directional shadows are enabled.")
                .defineInRange("directionalShadowDistance", 48, 16, 64);
        TEXTURE_BUMP = builder.comment("OpenGL: subtle bump normals generated from block textures.")
                .define("textureBumpDetail", true);
        METAL_REFLECTIONS = builder.comment("OpenGL: metallic highlights and faint glass/ice reflections; four voxel rays bounded to eight blocks.")
                .define("metalReflections", true);
        MATERIALS = builder.comment("Experimental RGB glass, water attenuation and box/quarter-block partial shadow shapes.")
                .define("advancedMaterials", false);
        INDIRECT = builder.comment("Experimental one-bounce voxel GI: six-block gathers with eight-sample accumulation. Expensive; no dynamic-light bounce.")
                .define("indirectPrototype", false);
        WORLD_INDIRECT = builder.comment("Experimental world-space indirect probes, four-block spacing and six directions. Requires indirectPrototype, DEFERRED and lightingHistory. Missing probes use surface gathers; excludes moving lights and entity shadows.")
                .define("worldSpaceIndirect", false);
        INDIRECT_INTERVAL = builder.comment("DEFERRED GI: frames between progressive gather samples, 1..8. New surfaces start immediately. LEGACY keeps its existing per-frame gather.")
                .defineInRange("indirectUpdateInterval", 1, 1, 8);
        PIPELINE = builder.comment("LEGACY traces static shadows inside terrain shading (reference). DEFERRED samples reprojected lighting in terrain shading and traces new/stale visible pixels once in a separate pass; newly visible surfaces use a bounded unshadowed estimate for one frame. Requires lightingHistory.")
                .defineEnum("lightingPipeline", LightingPipeline.LEGACY);
        REFRESH_INTERVAL = builder.comment("DEFERRED only: frames over which stale cached pixels are re-traced (1, 2 or 4). Newly visible pixels are always traced.")
                .defineInRange("deferredRefreshInterval", 1, 1, 4);
        PRESET = builder.comment("Last applied hardware preset; CUSTOM means settings were edited individually.")
                .defineEnum("qualityPreset", QualityPreset.CUSTOM);
        GOVERNOR_TARGET = builder.comment("Adaptive quality target FPS (30, 60, 90, 120); 0 keeps fixed quality. Only lowers settings, never above configured values.")
                .defineInRange("governorTargetFps", 0, 0, 240);
        GOVERNOR_MIN = builder.comment("Lowest adaptive quality level, 0..4.").defineInRange("governorMinLevel", 0, 0, 4);
        GOVERNOR_MAX = builder.comment("Highest adaptive quality level, 0..4. Level 4 applies no caps.").defineInRange("governorMaxLevel", 4, 0, 4);
        SPEC = builder.build();
    }

    public Rayvox(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(DEBUG_KEY));
        modBus.addListener(RayvoxEngine::registerShaders);
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            // Clear glass needs sorted alpha blending so empty texels can carry faint reflections.
            var translucent = net.minecraft.client.renderer.RenderType.translucent();
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(net.minecraft.world.level.block.Blocks.GLASS, translucent);
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(net.minecraft.world.level.block.Blocks.GLASS_PANE, translucent);
        }));
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
