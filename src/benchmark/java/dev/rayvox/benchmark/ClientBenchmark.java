package dev.rayvox.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.rayvox.Rayvox;
import dev.rayvox.RayvoxEngine;
import dev.rayvox.RenderBackends;
import dev.rayvox.render.AntiAliasing;
import dev.rayvox.render.BackendMode;
import dev.rayvox.render.UpscalingMode;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Real client, world, mixins and renderer; loaded only by runBenchmarkClient. */
@Mod(value = "rayvox_benchmark", dist = Dist.CLIENT)
public final class ClientBenchmark {
    private static ClientBenchmark instance;
    private static final String SCENE = System.getProperty("rayvox.benchmark.scene", "room");
    private static final boolean VULKAN = SCENE.startsWith("vulkan-");
    private static final boolean VANILLA_VULKAN = SCENE.startsWith("vulkan-vanilla");
    private static final boolean VANILLA_UPSCALING = SCENE.equals("vulkan-vanilla-upscaling");
    private static final boolean TEMPORAL = SCENE.equals("temporal");
    private static final boolean WORLD_CACHE = SCENE.equals("world-cache");
    private static final boolean INDIRECT_PIPELINE = SCENE.equals("pipeline-gi") || WORLD_CACHE;
    private static final boolean GOVERNOR_PIPELINE = SCENE.equals("pipeline-governor");
    private static final boolean HELD_PIPELINE = SCENE.equals("pipeline-held");
    private static final boolean PIPELINE = SCENE.equals("pipeline") || INDIRECT_PIPELINE || GOVERNOR_PIPELINE || HELD_PIPELINE;
    private static final boolean ENTITIES = SCENE.equals("entities") || SCENE.equals("vulkan-entities") || SCENE.equals("vulkan-vanilla-entities");
    private static final boolean DIRECTIONAL_GLASS = SCENE.equals("directional-glass");
    private static final boolean DIRECTIONAL = SCENE.equals("directional") || SCENE.equals("directional-basic") || DIRECTIONAL_GLASS || SCENE.equals("vulkan-outdoor") || SCENE.equals("vulkan-vanilla-outdoor") || VANILLA_UPSCALING;
    private static final boolean DIRECTIONAL_MATERIALS = !SCENE.equals("directional-basic");
    private static final String WORLD = VANILLA_UPSCALING ? "rayvox-benchmark-upscaling-v1" : ENTITIES ? "rayvox-benchmark-entities-v1" : DIRECTIONAL_GLASS ? "rayvox-benchmark-directional-glass"
            : DIRECTIONAL ? "rayvox-benchmark-directional" : "rayvox-benchmark-room-v3";
    private final long boot = System.nanoTime();
    // Opt-in captures with coarse native-reference quality checks. 103 seconds of planned phases.
    private final List<Phase> phases = VANILLA_UPSCALING ? List.of(
            new Phase("native-static", false, 8, UpscalingMode.NATIVE, AntiAliasing.OFF, 0),
            new Phase("upscale-native-off", true, 6, UpscalingMode.NATIVE, AntiAliasing.OFF, 0),
            new Phase("upscale-native-fxaa", true, 3, UpscalingMode.NATIVE, AntiAliasing.FXAA, 0),
            new Phase("upscale-quality-off", true, 3, UpscalingMode.QUALITY, AntiAliasing.OFF, 20),
            new Phase("upscale-quality-fxaa", true, 3, UpscalingMode.QUALITY, AntiAliasing.FXAA, 20),
            new Phase("upscale-balanced-off", true, 3, UpscalingMode.BALANCED, AntiAliasing.OFF, 50),
            new Phase("upscale-balanced-fxaa", true, 3, UpscalingMode.BALANCED, AntiAliasing.FXAA, 50),
            new Phase("upscale-performance-off", true, 3, UpscalingMode.PERFORMANCE, AntiAliasing.OFF, 100),
            new Phase("upscale-performance-fxaa", true, 3, UpscalingMode.PERFORMANCE, AntiAliasing.FXAA, 100),
            new Phase("upscale-adaptive-fxaa", true, 3, UpscalingMode.ADAPTIVE, AntiAliasing.FXAA, 20),
            new Phase("upscale-resized", true, 4, UpscalingMode.PERFORMANCE, AntiAliasing.FXAA, 100),
            new Phase("upscale-backend-off", false, 3, UpscalingMode.QUALITY, AntiAliasing.FXAA, 20),
            new Phase("upscale-backend-return", true, 5, UpscalingMode.QUALITY, AntiAliasing.FXAA, 20),
            new Phase("upscale-reloaded", true, 5, UpscalingMode.QUALITY, AntiAliasing.FXAA, 20),
            new Phase("upscale-native-return", true, 3, UpscalingMode.NATIVE, AntiAliasing.OFF, 0)) : SCENE.equals("vulkan-vanilla-lifecycle") ? List.of(
            new Phase("native-static",false,false,false,false,8,3),
            new Phase("vanilla-vulkan-static",true,false,false,false,10,3),
            new Phase("vulkan-edited",true,false,false,false,4,3),
            new Phase("vulkan-reloaded",true,false,false,false,6,3),
            new Phase("vulkan-resized",true,false,false,false,4,3),
            new Phase("vulkan-night",true,false,false,false,4,3),
            new Phase("vulkan-nightvision",true,false,false,false,4,3),
            new Phase("vulkan-rejected",true,false,false,false,4,3),
            new Phase("native-moving",false,false,false,true,4,3),
            new Phase("vanilla-vulkan-return",true,false,false,false,8,3),
            new Phase("vulkan-nether",true,false,false,false,15,3),
            new Phase("native-nether",false,false,false,false,4,3),
            new Phase("vanilla-nether",true,false,false,false,8,3)) : VANILLA_VULKAN ? List.of(
            new Phase("native-static",false,false,false,false,8,4),
            new Phase("vanilla-vulkan-static",true,false,false,false,12,5),
            new Phase("vanilla-vulkan-moving",true,false,false,true,8,5),
            new Phase("native-moving",false,false,false,true,4,4),
            new Phase("vanilla-vulkan-return",true,false,false,false,8,4)) : SCENE.equals("vulkan-lifecycle") ? List.of(
            new Phase("vulkan-static",true,true,true,false,45,5),
            new Phase("vulkan-edited",true,true,true,false,10,5),
            new Phase("vulkan-reloaded",true,true,true,false,15,5),
            new Phase("vulkan-resized",true,true,true,false,10,5),
            new Phase("vulkan-nether",true,true,true,false,30,5)) : VULKAN ? List.of(
            new Phase("vulkan-static",true,true,true,false,45,10),
            new Phase("vulkan-moving",true,true,true,true,20,10)) : WORLD_CACHE ? List.of(
            new Phase("deferred-gi-control", true, false, true, false, 8, 4),
            new Phase("deferred-world-static", true, false, true, false, 8, 4),
            new Phase("deferred-world-moving", true, false, true, true, 8, 4),
            new Phase("deferred-world-disabled", true, false, true, false, 4, 4)) : HELD_PIPELINE ? List.of(
            new Phase("legacy-held-static", true, false, false, false, 8, 6),
            new Phase("deferred-held-static", true, false, false, false, 8, 6),
            new Phase("legacy-held-moving", true, false, false, true, 8, 6),
            new Phase("deferred-held-moving", true, false, false, true, 8, 6),
            new Phase("deferred-held-removed", true, false, false, false, 8, 6)) : GOVERNOR_PIPELINE ? List.of(
            new Phase("deferred-fixed-moving", true, true, true, true, 10, 6),
            new Phase("deferred-cpu-bound-moving", true, true, true, true, 8, 6),
            new Phase("deferred-governor-moving", true, true, true, true, 16, 12),
            new Phase("deferred-cpu-recovery-moving", true, true, true, true, 16, 6)) : INDIRECT_PIPELINE ? List.of(
            new Phase("legacy-gi-static", true, true, true, false, 8, 6),
            new Phase("deferred-gi-static", true, true, true, false, 8, 6),
            new Phase("legacy-gi-moving", true, true, true, true, 8, 6),
            new Phase("deferred-gi-moving", true, true, true, true, 8, 6),
            new Phase("deferred-gi-slow-moving", true, true, true, true, 8, 6)) : PIPELINE ? List.of(
            new Phase("legacy-static", true, false, false, false, 8, 6),
            new Phase("deferred-static", true, false, false, false, 8, 6),
            new Phase("legacy-moving", true, false, false, true, 8, 6),
            new Phase("deferred-moving", true, false, false, true, 8, 6),
            new Phase("deferred-sparse-moving", true, false, false, true, 8, 6),
            new Phase("deferred-history-off", true, false, false, false, 4, 4)) : TEMPORAL ? List.of(
            new Phase("reference-moving", true, false, false, true, 8, 6),
            new Phase("history-static", true, false, false, false, 8, 6),
            new Phase("history-moving", true, false, false, true, 8, 6)) : ENTITIES ? List.of(
            new Phase("entity-sharp", true, false, false, false, 12, 6),
            new Phase("entity-soft", true, false, false, false, 8, 6)) : DIRECTIONAL ? List.of(
            new Phase("sun-static", true, DIRECTIONAL_MATERIALS, false, false, 20, 6, 64, 8, 0),
            new Phase("sun-moving", true, DIRECTIONAL_MATERIALS, false, true, 8, 8, 64, 8, 0),
            new Phase("moon-static", true, DIRECTIONAL_MATERIALS, false, false, 8, 6, 64, 8, 0),
            new Phase("moon-moving", true, DIRECTIONAL_MATERIALS, false, true, 8, 8, 64, 8, 0)) : List.of(
            new Phase("vanilla-static", false, false, false, false, 8, 4),
            new Phase("rayvox-static", true, false, false, false, 8, 4),
            new Phase("rayvox-moving", true, false, false, true, 8, 6),
            new Phase("advanced-moving", true, true, false, true, 4, 6),
            new Phase("indirect-static", true, true, true, false, 6, 4),
            new Phase("dense-moving", true, true, false, true, 10, 8, 64, 8, 64),
            new Phase("partial-basic", true, false, false, false, 8, 4, 64, 8, 64),
            new Phase("partial-advanced", true, true, false, false, 6, 4, 64, 8, 64));
    private final int[] queries = new int[128], queryPhase = new int[64];
    private final boolean[] pending = new boolean[64];
    // A hidden window has no presentation backpressure; bound the queue to two frames for meaningful frame times.
    private final long[] frameFences = new long[2];
    private int frameFence;
    private int phase = -1, framePhase = -1, activeQuery = -1, nextQuery, ticks, phaseTick;
    private int upscalingOutputWidth = 1280, upscalingOutputHeight = 720;
    private boolean upscalingWorldObserved, upscalingGuiObserved;
    private BufferedImage upscalingNativeReference;
    private String upscalingNativeImageHash;
    private final Map<UpscalingSettings, String> upscalingOffImageHashes = new LinkedHashMap<>();
    private long phaseStart, measuredStart, lastFrame, renderStart, phasePassDraws, phaseIndirectDraws, phaseCompositionDraws, phaseDynamicDraws;
    private boolean opening, preparing, finished, timers;
    private int shadowEntityId;
    private CompletableFuture<Void> setup;
    private final List<Map<String, Object>> reports = new ArrayList<>();

    public ClientBenchmark(IEventBus bus) {
        if (!Boolean.getBoolean("rayvox.benchmark")) throw new IllegalStateException("Benchmark run property missing");
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::tick);
        if (VANILLA_UPSCALING) {
            NeoForge.EVENT_BUS.addListener(this::observeUpscalingWorld);
            NeoForge.EVENT_BUS.addListener(this::observeUpscalingGui);
        }
    }

    private void tick(ClientTickEvent.Post event) {
        if (finished) return;
        var mc = Minecraft.getInstance();
        try {
            ticks++;
            if (System.nanoTime() - boot > (PIPELINE || VULKAN ? 600_000_000_000L : 240_000_000_000L))
                throw new IllegalStateException("Client benchmark timed out: " + mc.screen);
            if (!opening && mc.getOverlay() == null) {
                if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().getFileName().toString().equals("benchmark-client"))
                    throw new IllegalStateException("Benchmark requires its isolated benchmark-client directory");
                if (RayvoxEngine.status().startsWith("Vanilla fallback")) throw new IllegalStateException(RayvoxEngine.status());
                if (!RayvoxEngine.status().equals("Terrain shader ready")) return;
                configure(mc);
                opening = true;
                var flows = mc.createWorldOpenFlows();
                if (Files.isRegularFile(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve("level.dat")))
                    flows.openWorld(WORLD, () -> fail(new IllegalStateException("World load cancelled")));
                else {
                    var rules = new GameRules();
                    rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                    rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                    rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                    rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
                    flows.createFreshLevel(WORLD, new LevelSettings("Rayvox benchmark", GameType.SPECTATOR, false,
                                    Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                            new WorldOptions(42, false, false),
                            access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                            mc.screen);
                }
            }
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            mc.mouseHandler.releaseMouse();
            if (!preparing) {
                preparing = true;
                var server = mc.getSingleplayerServer();
                setup = CompletableFuture.runAsync(() -> {
                    if (DIRECTIONAL) prepareDirectional(server.overworld());
                    else prepareWorld(server.overworld());
                    if(VULKAN)prepareVulkanMaterials(server.overworld());
                    if (ENTITIES) {
                        var world = server.overworld();
                        var area = new net.minecraft.world.phys.AABB(-16, 64, -16, 16, 76, 16);
                        world.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, area)
                                .forEach(net.minecraft.world.entity.Entity::discard);
                        var villager = net.minecraft.world.entity.EntityType.VILLAGER.create(world);
                        if (villager == null) throw new IllegalStateException("Cannot create shadow test entity");
                        villager.setNoAi(true);
                        villager.moveTo(-4, 65, 5, 180, 0);
                        world.addFreshEntity(villager);
                        shadowEntityId = villager.getId();
                        if(VANILLA_VULKAN)world.setBlock(new BlockPos(0,65,6),Blocks.CHEST.defaultBlockState(),3);
                    }
                    var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                    if (player == null) throw new IllegalStateException("Integrated server player missing");
                    player.setGameMode(HELD_PIPELINE ? GameType.CREATIVE : GameType.SPECTATOR);
                    if (HELD_PIPELINE) { player.getAbilities().flying = true; player.onUpdateAbilities(); }
                    player.teleportTo(server.overworld(), .5, DIRECTIONAL ? 68 : 66, DIRECTIONAL ? 28 : 13.5, 180, DIRECTIONAL ? 25 : 12);
                }, server);
            }
            if (!setup.isDone()) return;
            setup.join();
            if (mc.getOverlay() != null) return;
            if (phase < 0) {
                mc.setScreen(null);
                timers = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
                if (timers) glGenQueries(queries);
                beginPhase(0);
            }
            var current = phases.get(phase);
            if(VANILLA_VULKAN&&ENTITIES)mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.FLAME,.5,66,8,0,.01,0);
            double x = .5 + (current.moving ? Math.sin((ticks - phaseTick) * .035) * (DIRECTIONAL ? 24 : 6) : 0);
            mc.player.setPos(x, DIRECTIONAL ? 68 : 66, DIRECTIONAL ? 28 : 13.5);
            mc.player.setYRot(180); mc.player.setXRot(DIRECTIONAL ? 25 : 12);
            if (ENTITIES) {
                double entityX = -4 + Math.sin(ticks * 0.08) * 2;
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var entity = server.overworld().getEntity(shadowEntityId);
                    if (entity != null) entity.setPos(entityX, 65, 5);
                });
            }
        } catch (Throwable error) { fail(error); }
    }

    private static void configure(Minecraft mc) {
        if(Boolean.getBoolean("rayvox.benchmark.requirePackaged")&&!Rayvox.class.getResource("Rayvox.class").toString().contains("rayvox-packaged.jar"))throw new IllegalStateException("Packaged benchmark loaded development Rayvox classes");
        // Saved interactive settings (or an interrupted upscaling run) must not contaminate native parity scenes.
        if (VANILLA_VULKAN) {
            Rayvox.ANTI_ALIASING.set(AntiAliasing.OFF);
            Rayvox.UPSCALING.set(UpscalingMode.NATIVE);
            Rayvox.UPSCALE_SHARPNESS.set(0);
        }
        if(VULKAN)Rayvox.RENDER_BACKEND.set(VANILLA_VULKAN?dev.rayvox.render.BackendMode.VULKAN_VANILLA:dev.rayvox.render.BackendMode.VULKAN_PATH_TRACING);
        if (GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_VISIBLE) != GLFW.GLFW_FALSE)
            throw new IllegalStateException("Benchmark window must be hidden");
        mc.options.pauseOnLostFocus = false;
        mc.options.hideGui = true;
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        mc.options.renderDistance().set(Integer.getInteger("rayvox.benchmark.renderDistance",6));
        mc.options.simulationDistance().set(5);
        mc.options.bobView().set(false);
        mc.options.fov().set(70);
        mc.getWindow().updateVsync(false);
        mc.getWindow().setFramerateLimit(260);
        if (PIPELINE && !RayvoxEngine.profiling()) RayvoxEngine.toggleProfiling();
    }

    private static void prepareWorld(ServerLevel world) {
        world.setDayTime(18000);
        world.setWeatherParameters(0, 24000, false, false);
        var cursor = new BlockPos.MutableBlockPos();
        for (int z = -16; z <= 16; z++) for (int y = 64; y <= 76; y++) for (int x = -16; x <= 16; x++) {
            var block = y == 64 ? Blocks.WHITE_CONCRETE : y == 76 ? Blocks.LIGHT_GRAY_CONCRETE
                    : Math.abs(x) == 16 || Math.abs(z) == 16 ? Blocks.GRAY_CONCRETE : Blocks.AIR;
            world.setBlock(cursor.set(x, y, z), block.defaultBlockState(), 18);
        }
        for (int z : new int[]{-11, 2}) for (int x = -10; x <= 10; x += 5) {
            world.setBlock(cursor.set(x, 65, z), Blocks.STONE_BRICKS.defaultBlockState(), 18);
            world.setBlock(cursor.set(x, 66, z), (x < 0 ? Blocks.SOUL_TORCH : Blocks.TORCH).defaultBlockState(), 18);
        }
        for (int x : new int[]{-5, 5}) for (int y = 65; y <= 68; y++)
            world.setBlock(cursor.set(x, y, -3), Blocks.STONE_BRICKS.defaultBlockState(), 18);
        for (int x = -11; x <= -7; x++) for (int y = 65; y <= 69; y++)
            world.setBlock(cursor.set(x, y, -7), Blocks.BLUE_STAINED_GLASS_PANE.defaultBlockState()
                    .setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true), 18);
        for (int x = 7; x <= 11; x++) for (int y = 65; y <= 69; y++)
            world.setBlock(cursor.set(x, y, -7), Blocks.RED_STAINED_GLASS_PANE.defaultBlockState()
                    .setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true), 18);
        for (int x = -2; x <= 2; x++) world.setBlock(cursor.set(x, 65, -3), Blocks.STONE_SLAB.defaultBlockState(), 18);
    }

    private static void prepareDirectional(ServerLevel world) {
        world.setDayTime(6000);
        world.setWeatherParameters(0, 24000, false, false);
        world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, world.getServer());
        var cursor = new BlockPos.MutableBlockPos();
        for (int z = -72; z <= 72; z++) for (int x = -72; x <= 72; x++) {
            world.setBlock(cursor.set(x, 64, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 18);
            for (int y = 65; y <= 90; y++) world.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(), 18);
        }
        for (int x : new int[]{-24, -8, 8, 24}) for (int z : new int[]{-20, 0, 20})
            for (int y = 65; y <= 88; y++) for (int dx = 0; dx < 2; dx++)
                world.setBlock(cursor.set(x + dx, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 18);
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++)
            world.setBlock(cursor.set(x, 82, z), (DIRECTIONAL_GLASS ? Blocks.RED_STAINED_GLASS : Blocks.STONE_BRICKS).defaultBlockState(), 18);
    }

    private void beginPhase(int index) {
        phase = index; phaseTick = ticks; phaseStart = System.nanoTime(); measuredStart = 0;
        phasePassDraws = RayvoxEngine.lightingPassDraws();
        phaseIndirectDraws = RayvoxEngine.indirectPassDraws();
        phaseCompositionDraws = RayvoxEngine.compositionPassDraws();
        phaseDynamicDraws = RayvoxEngine.dynamicPassDraws();
        var config = phases.get(index);
        if (VANILLA_UPSCALING) {
            Rayvox.ANTI_ALIASING.set(config.upscaling.antiAliasing());
            Rayvox.UPSCALING.set(config.upscaling.mode());
            Rayvox.UPSCALE_SHARPNESS.set(config.upscaling.sharpness());
        }
        if(VANILLA_VULKAN)dev.rayvox.RenderBackends.requestMode(config.enabled?dev.rayvox.render.BackendMode.VULKAN_VANILLA:dev.rayvox.render.BackendMode.OPENGL);
        if(VULKAN) {
            var client=Minecraft.getInstance();var server=client.getSingleplayerServer();
            if(config.name.equals("vulkan-edited"))setup=CompletableFuture.runAsync(()->server.overworld().setBlock(new BlockPos(0,65,6),Blocks.OBSIDIAN.defaultBlockState(),3),server);
            if(config.name.equals("vulkan-reloaded"))setup=client.reloadResourcePacks();
            if(config.name.equals("vulkan-resized"))GLFW.glfwSetWindowSize(client.getWindow().getWindow(),1920,1080);
            if(config.name.equals("vulkan-night"))setup=CompletableFuture.runAsync(()->server.overworld().setDayTime(18000),server);
            if(config.name.equals("vulkan-nightvision"))setup=CompletableFuture.runAsync(()->server.getPlayerList().getPlayer(client.player.getUUID()).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,1200)),server);
            if(config.name.equals("vulkan-rejected")&&dev.rayvox.RenderBackends.raster()!=null&&!dev.rayvox.RenderBackends.raster().rayTracingSupported())dev.rayvox.RenderBackends.requestMode(dev.rayvox.render.BackendMode.VULKAN_PATH_TRACING);
            if(config.name.equals("vulkan-nether"))setup=CompletableFuture.runAsync(()-> {
                var nether=server.getLevel(net.minecraft.world.level.Level.NETHER);prepareWorld(nether);
                server.getPlayerList().getPlayer(client.player.getUUID()).teleportTo(nether,.5,66,13.5,180,12);
            },server);
        }
        config.lastQualityLevel = RayvoxEngine.governorLevel();
        Rayvox.ENABLED.set(config.enabled); Rayvox.MATERIALS.set(config.materials); Rayvox.INDIRECT.set(config.indirect);
        Rayvox.WORLD_INDIRECT.set(WORLD_CACHE && config.name.startsWith("deferred-world-") && !config.name.endsWith("disabled"));
        Rayvox.HISTORY.set(!config.name.equals("reference-moving") && !config.name.equals("deferred-history-off"));
        Rayvox.PIPELINE.set(PIPELINE && config.name.startsWith("deferred-")
                ? dev.rayvox.LightingPipeline.DEFERRED : dev.rayvox.LightingPipeline.LEGACY);
        Rayvox.REFRESH_INTERVAL.set(config.name.equals("deferred-sparse-moving") ? 4 : 1);
        Rayvox.INDIRECT_INTERVAL.set(config.name.equals("deferred-gi-slow-moving") ? 4 : 1);
        Rayvox.GOVERNOR_TARGET.set(GOVERNOR_PIPELINE && !config.name.equals("deferred-fixed-moving") ? 60 : 0);
        Rayvox.GOVERNOR_MIN.set(0); Rayvox.GOVERNOR_MAX.set(4);
        if (PIPELINE) { Rayvox.SHADOW_SOFTNESS.set(2); Rayvox.SHADOW_DRAW_DISTANCE.set(32); }
        Rayvox.SHADOWS.set(true); Rayvox.SUNLIGHT.set(DIRECTIONAL); Rayvox.DYNAMIC.set(HELD_PIPELINE);
        if (HELD_PIPELINE) {
            var mc = Minecraft.getInstance();
            boolean removed = config.name.endsWith("removed");
            mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, removed
                    ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(Blocks.TORCH));
            var server = mc.getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, removed
                        ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(Blocks.TORCH));
                player.inventoryMenu.broadcastChanges();
            }, server);
        }
        if (ENTITIES) {
            Rayvox.ENTITY_SHADOWS.set(true);
            Rayvox.SHADOW_SOFTNESS.set(config.name.equals("entity-sharp") ? 0 : 2);
        }
        Rayvox.CACHE_RADIUS.set(2); Rayvox.ACTIVE_LIGHTS.set(config.limit); Rayvox.LIGHTS_PER_SECTION.set(config.perTile);
        if (DIRECTIONAL && !VANILLA_UPSCALING) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> server.overworld().setDayTime(config.name.startsWith("moon-") ? 18000 : 6000), server);
        } else if (config.expectedLights == 64) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                prepareDense(server.overworld());
                if (config.name.startsWith("partial-")) preparePartial(server.overworld());
            }, server);
        }
        if (VANILLA_UPSCALING) {
            var client = Minecraft.getInstance();
            if (config.name.equals("upscale-resized") || config.name.equals("upscale-native-return")) {
                boolean odd = config.name.equals("upscale-resized");
                upscalingOutputWidth = odd ? 1279 : 1280;
                upscalingOutputHeight = odd ? 719 : 720;
                GLFW.glfwSetWindowSize(client.getWindow().getWindow(), upscalingOutputWidth, upscalingOutputHeight);
            }
            if (config.name.equals("upscale-reloaded")) setup = client.reloadResourcePacks();
        }
        while (glGetError() != GL_NO_ERROR) { }
        LogUtils.getLogger().info("Rayvox benchmark phase {}", config.name);
    }

    private static void prepareDense(ServerLevel world) {
        var cursor = new BlockPos.MutableBlockPos();
        for (int z : new int[]{-11, 2}) for (int x = -10; x <= 10; x += 5) for (int y = 65; y <= 66; y++)
            world.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(), 18);
        for (int z = -14; z <= 14; z += 4) for (int x = -14; x <= 14; x += 4) {
            world.setBlock(cursor.set(x, 65, z), Blocks.STONE_BRICKS.defaultBlockState(), 18);
            world.setBlock(cursor.set(x, 66, z), (x < 0 ? Blocks.SOUL_TORCH : Blocks.TORCH).defaultBlockState(), 18);
        }
    }

    private static void prepareVulkanMaterials(ServerLevel world) {
        var position=new BlockPos.MutableBlockPos();
        if(SCENE.equals("vulkan-reflective")) {
            for(int x=-6;x<=6;x++)for(int z=-4;z<=6;z++)world.setBlock(position.set(x,64,z),
                    (x<0?Blocks.GOLD_BLOCK:Blocks.IRON_BLOCK).defaultBlockState(),18);
        }
        if(SCENE.equals("vulkan-glass-water")||SCENE.equals("vulkan-vanilla-glass-water")||VANILLA_UPSCALING) {
            for(int x=-7;x<=7;x++)for(int z=-5;z<=5;z++)world.setBlock(position.set(x,64,z),Blocks.WATER.defaultBlockState(),18);
            for(int x=-5;x<=5;x++)for(int y=65;y<=68;y++)world.setBlock(position.set(x,y,4),
                    (x<0?Blocks.RED_STAINED_GLASS:Blocks.GLASS).defaultBlockState(),18);
            world.setBlock(new BlockPos(3,65,7),Blocks.CHEST.defaultBlockState(),18);
            world.setBlock(new BlockPos(-3,65,7),Blocks.LAVA.defaultBlockState(),18);
        }
    }

    private static void preparePartial(ServerLevel world) {
        var cursor = new BlockPos.MutableBlockPos();
        var bars = Blocks.IRON_BARS.defaultBlockState().setValue(BlockStateProperties.EAST, true)
                .setValue(BlockStateProperties.WEST, true);
        for (int x = -2; x <= 2; x++) for (int y = 65; y <= 69; y++)
            world.setBlock(cursor.set(x, y, 8), bars, 18);
        for (int x : new int[]{-4, 4}) for (int y = 65; y <= 69; y++)
            world.setBlock(cursor.set(x, y, 8), Blocks.CHAIN.defaultBlockState(), 18);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        world.setBlock(cursor.set(-7, 65, 8), door, 18);
        world.setBlock(cursor.set(-7, 66, 8), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 18);
        var trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        for (int x = 6; x <= 7; x++) for (int y = 65; y <= 66; y++)
            world.setBlock(cursor.set(x, y, 8), trapdoor, 18);
    }

    public static void beginFrame() { if (instance != null) instance.start(); }
    public static void rendered() { if (instance != null) instance.renderedFrame(); }
    public static void endFrame() { if (instance != null) instance.finishFrame(); }

    private void start() {
        framePhase = -1; renderStart = 0;
        upscalingWorldObserved = upscalingGuiObserved = false;
        if (finished || phase < 0) return;
        if (frameFences[frameFence] != 0) {
            int result;
            long deadline = System.nanoTime() + 10_000_000_000L;
            do {
                result = glClientWaitSync(frameFences[frameFence], 0, 0);
                // Finite native waits may busy-spin in the driver and masquerade as game CPU work.
                if (result == GL_TIMEOUT_EXPIRED) java.util.concurrent.locks.LockSupport.parkNanos(250_000L);
            } while (result == GL_TIMEOUT_EXPIRED && System.nanoTime() < deadline);
            if (result == GL_WAIT_FAILED || result == GL_TIMEOUT_EXPIRED) {
                fail(new IllegalStateException("Benchmark frame fence wait failed or timed out")); return;
            }
            glDeleteSync(frameFences[frameFence]);
            frameFences[frameFence] = 0;
        }
        poll();
        if (System.nanoTime() - phaseStart < phases.get(phase).warm * 1_000_000_000L) return;
        if (VANILLA_UPSCALING && (!setup.isDone() || Minecraft.getInstance().getOverlay() != null)) return;
        // Keep initial chunk residency out of steady-state timing. The recorded
        // warm-up duration and source/artifact hashes identify the measured run.
        if(VULKAN&&dev.rayvox.RenderBackends.pendingSections()>0)return;
        if (measuredStart == 0) measuredStart = System.nanoTime();
        framePhase = phase; renderStart = System.nanoTime();
        if (timers && !pending[nextQuery]) {
            activeQuery = nextQuery; nextQuery = (nextQuery + 1) % pending.length;
            queryPhase[activeQuery] = phase;
            glQueryCounter(queries[activeQuery * 2], GL_TIMESTAMP);
        }
    }

    private void renderedFrame() {
        if (finished || phase < 0) return;
        var current = phases.get(phase);
        if (GOVERNOR_PIPELINE && current.lastQualityLevel != RayvoxEngine.governorLevel()) {
            current.lastQualityLevel = RayvoxEngine.governorLevel();
            current.qualityDecisions.add(RayvoxEngine.governorStats());
            LogUtils.getLogger().info("Rayvox governor decision in {}: {}", current.name, RayvoxEngine.governorStats());
        }
        if (framePhase >= 0) {
            phases.get(framePhase).cpu.add(System.nanoTime() - renderStart);
            if (activeQuery >= 0) {
                glQueryCounter(queries[activeQuery * 2 + 1], GL_TIMESTAMP);
                pending[activeQuery] = true; activeQuery = -1;
            }
        }
        // Actual CPU work (not a sleep) tests overlapping CPU/GPU bottlenecks without changing the GPU workload.
        long cpuLoad = current.name.equals("deferred-cpu-bound-moving") ? 30_000_000L
                : current.name.equals("deferred-cpu-recovery-moving") ? 80_000_000L : 0;
        if (cpuLoad > 0) {
            // Submit the end timestamps before CPU load so unflushed commands do not time GPU idle as lighting.
            glFlush();
            long until = System.nanoTime() + cpuLoad;
            while (System.nanoTime() < until) Thread.onSpinWait();
        }
    }

    private void finishFrame() {
        long now = System.nanoTime();
        try {
            if (!finished && phase >= 0) {
                if (VANILLA_UPSCALING && framePhase == phase) {
                    var current = phases.get(phase);
                    if (!upscalingWorldObserved || !upscalingGuiObserved)
                        throw new IllegalStateException("Missing world/GUI resolution observations in " + current.name);
                    current.framebufferStatus = assertNativeUpscalingTarget(current);
                    current.nativeTargetChecks++;
                }
                if (framePhase == phase && lastFrame != 0) {
                    phases.get(phase).wall.add(now - lastFrame);
                    int level = RayvoxEngine.governorLevel();
                    if (level < 0 || level >= phases.get(phase).qualityFrames.length)
                        throw new IllegalStateException("Governor selected an out-of-range quality level " + level);
                    phases.get(phase).qualityFrames[level]++;
                    double cpuMs = RayvoxEngine.cpuWorkMillis();
                    if (Double.isFinite(cpuMs) && cpuMs >= 0) phases.get(phase).cpuWork.add((long)(cpuMs * 1_000_000));
                    if(dev.rayvox.RenderBackends.active()) {
                        var raster=dev.rayvox.RenderBackends.rasterTimings();var trace=dev.rayvox.RenderBackends.timings();var samples=phases.get(phase);
                        if(raster!=null)samples.gpuSample("raster",raster.gpuMs());
                        if(trace!=null){samples.gpuSample("uploadsAndBuilds",trace.gpuBuildMs());samples.gpuSample("trace",trace.gpuTraceMs());samples.gpuSample("reconstruction",trace.gpuReconstructMs());samples.gpuSample("exposure",trace.gpuExposureMs());samples.gpuSample("composite",trace.gpuCompositeMs());}
                    }
                }
                var current = phases.get(phase);
                // Driver compilation can consume the warmup frame; measure a full window afterwards.
                if (measuredStart != 0 && now - measuredStart >= current.seconds * 1_000_000_000L) {
                    var mc = Minecraft.getInstance();
                    if (!VULKAN && current.enabled && (RayvoxEngine.lights().active.size() != current.expectedLights || !RayvoxEngine.status().equals("Terrain shader ready")))
                        throw new IllegalStateException("Rayvox did not activate: " + RayvoxEngine.status() + "; " + RayvoxEngine.lightStats());
                    if (ENTITIES && !VULKAN && RayvoxEngine.entityShadowCount() == 0)
                        throw new IllegalStateException("Moving entity was not published to the terrain shader");
                    if (ENTITIES && !VULKAN && RayvoxEngine.entityLightingDraws() == 0)
                        throw new IllegalStateException("Moving entity did not use the RGB lighting shader");
                    if(ENTITIES&&VULKAN&&!VANILLA_VULKAN&&dev.rayvox.RenderBackends.dynamicMeshCount()==0)
                        throw new IllegalStateException("Animated entity geometry was not captured by Vulkan");
                    if (PIPELINE && configDeferred(current) != RayvoxEngine.deferredActive())
                        throw new IllegalStateException("Unexpected pipeline: " + RayvoxEngine.pipelineStats());
                    if (PIPELINE && configDeferred(current) && RayvoxEngine.lightingPassDraws() <= phasePassDraws)
                        throw new IllegalStateException("Deferred pass did not draw");
                    if (PIPELINE && configDeferred(current) != (RayvoxEngine.compositionPassDraws() > phaseCompositionDraws))
                        throw new IllegalStateException("Unexpected same-frame composition activity in " + current.name);
                    if (HELD_PIPELINE && RayvoxEngine.dynamicLightCount() != (current.name.endsWith("removed") ? 0 : 1))
                        throw new IllegalStateException("Held-light collection failed in " + current.name + ": " + RayvoxEngine.dynamicStats());
                    if (HELD_PIPELINE && configDeferred(current) && !current.name.endsWith("removed")
                            && RayvoxEngine.dynamicPassDraws() <= phaseDynamicDraws)
                        throw new IllegalStateException("Current-frame held-light pass did not draw");
                    if ((current.name.equals("deferred-cpu-bound-moving") || current.name.equals("deferred-cpu-recovery-moving"))
                            && RayvoxEngine.governorLevel() != 4)
                        throw new IllegalStateException("Governor did not hold/recover quality during " + current.name + ": "
                                + RayvoxEngine.governorStats() + "; quality frames " + Arrays.toString(current.qualityFrames)
                                + "; CPU work " + stats(current.cpuWork) + "; decisions " + current.qualityDecisions);
                    if (PIPELINE && (configDeferred(current) && current.indirect)
                            != (RayvoxEngine.indirectPassDraws() > phaseIndirectDraws))
                        throw new IllegalStateException("Unexpected indirect pass activity in " + current.name
                                + ": " + RayvoxEngine.governorStats());
                    int error = glGetError();
                    if (WORLD_CACHE && Rayvox.WORLD_INDIRECT.get()
                            && (RayvoxEngine.worldIndirectReadyBricks() == 0 || RayvoxEngine.worldIndirectDraws() == 0))
                        throw new IllegalStateException("World probe producer did not publish usable bricks: " + RayvoxEngine.worldLightingStats());
                    if (WORLD_CACHE && !Rayvox.WORLD_INDIRECT.get() && RayvoxEngine.worldIndirectReadyBricks() != 0)
                        throw new IllegalStateException("Disabled world probes remained active");
                    if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error in " + current.name + ": " + error);
                    Path screenshot = mc.gameDirectory.toPath().resolve(current.name + ".png");
                    var report = new LinkedHashMap<String, Object>();
                    if (VANILLA_UPSCALING) report.put("imageStats", captureUpscaling(current, screenshot));
                    else try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(screenshot); }
                    report.put("phase", current.name); report.put("engine", RayvoxEngine.status());
                    report.put("renderer",dev.rayvox.RenderBackends.status());
                    if(VULKAN)report.put("vulkan",dev.rayvox.RenderBackends.diagnostics());
                    if(VULKAN) {
                        report.put("antiAliasing", Rayvox.ANTI_ALIASING.get().name());
                        report.put("upscaling", Rayvox.UPSCALING.get().name());
                        report.put("upscaleSharpness", Rayvox.UPSCALE_SHARPNESS.get());
                    }
                    if(VANILLA_UPSCALING)reportUpscaling(current, report);
                    if(VULKAN)report.put("vulkanTimings",VANILLA_VULKAN?dev.rayvox.RenderBackends.rasterTimings():dev.rayvox.RenderBackends.timings());
                    if(VULKAN)report.put("vulkanDynamicMeshes",dev.rayvox.RenderBackends.dynamicMeshCount());
                    if(VULKAN&&current.enabled&&!dev.rayvox.RenderBackends.active())throw new IllegalStateException("Vulkan benchmark fell back: "+dev.rayvox.RenderBackends.status());
                    if(VANILLA_VULKAN&&current.enabled&&(dev.rayvox.RenderBackends.rasterTimings()==null||dev.rayvox.RenderBackends.rasterTimings().terrainDraws()==0))throw new IllegalStateException("Vulkan vanilla did not draw terrain");
                    if(VANILLA_VULKAN&&current.enabled&&ENTITIES&&(dev.rayvox.RenderBackends.rasterTimings().entityDraws()==0||dev.rayvox.RenderBackends.rasterTimings().particleDraws()==0))throw new IllegalStateException("Vulkan vanilla missed entity or particle draws");
                    if(VANILLA_VULKAN&&!VANILLA_UPSCALING&&current.name.equals("vanilla-vulkan-static")&&!ENTITIES)report.put("nativeImageDifference",compareImages(mc.gameDirectory.toPath().resolve("native-static.png"),screenshot));
                    if(VANILLA_VULKAN&&dev.rayvox.RenderBackends.raster()!=null&&!dev.rayvox.RenderBackends.raster().validationMessages().isEmpty())throw new IllegalStateException("Vulkan validation errors: "+dev.rayvox.RenderBackends.raster().validationMessages());
                    report.put("activeLightLimit", current.limit); report.put("lightsPerTile", current.perTile);
                    if (ENTITIES) report.put("entityCasters", RayvoxEngine.entityShadowCount());
                    if (ENTITIES) report.put("entityLightingDraws", RayvoxEngine.entityLightingDraws());
                    report.put("cache", RayvoxEngine.cacheStats()); report.put("lights", RayvoxEngine.lightStats());
                    report.put("history", RayvoxEngine.historyStats()); report.put("screenshot", screenshot.toAbsolutePath().toString());
                    report.put("pipeline", RayvoxEngine.pipelineStats()); report.put("governor", RayvoxEngine.governorStats());
                    if (WORLD_CACHE) {
                        report.put("worldLighting", RayvoxEngine.worldLightingStats());
                        report.put("worldIndirectReadyBricks", RayvoxEngine.worldIndirectReadyBricks());
                        report.put("worldIndirectDraws", RayvoxEngine.worldIndirectDraws());
                    }
                    report.put("governorTargetFps", Rayvox.GOVERNOR_TARGET.get());
                    report.put("qualityLevelFrames", current.qualityFrames);
                    report.put("qualityDecisions", current.qualityDecisions);
                    report.put("deferredPassDraws", RayvoxEngine.lightingPassDraws() - phasePassDraws);
                    report.put("indirectPassDraws", RayvoxEngine.indirectPassDraws() - phaseIndirectDraws);
                    report.put("compositionPassDraws", RayvoxEngine.compositionPassDraws() - phaseCompositionDraws);
                    report.put("dynamicPassDraws", RayvoxEngine.dynamicPassDraws() - phaseDynamicDraws);
                    report.put("dynamicLights", RayvoxEngine.dynamicLightCount());
                    if (Double.isFinite(RayvoxEngine.spanMillis())) report.put("opaqueSpanMs", RayvoxEngine.spanMillis());
                    if (RayvoxEngine.deferredActive() && Double.isFinite(RayvoxEngine.passMillis()))
                        report.put("deferredPassMs", RayvoxEngine.passMillis());
                    if (RayvoxEngine.deferredActive() && current.indirect && Double.isFinite(RayvoxEngine.indirectPassMillis()))
                        report.put("indirectPassMs", RayvoxEngine.indirectPassMillis());
                    measurements(current, report);
                    reports.add(report);
                    if (phase + 1 < phases.size()) beginPhase(phase + 1);
                    else complete();
                }
            }
        } catch (Throwable error) { fail(error); }
        if (!finished && phase >= 0) {
            // Vulkan already bounds submissions to two slots using native fences.
            // A second GL limiter measures Windows park/timer latency as frame time.
            if(!dev.rayvox.RenderBackends.active()) {
                frameFences[frameFence] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (frameFences[frameFence] == 0) fail(new IllegalStateException("Cannot allocate benchmark frame fence"));
                frameFence = (frameFence + 1) % frameFences.length;
            }
            glFlush();
        }
        lastFrame = now;
    }

    private void observeUpscalingWorld(RenderLevelStageEvent event) {
        if (finished || phase < 0 || framePhase != phase || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        try {
            var current = phases.get(phase);
            var settings = current.upscaling;
            var expectedBackend = current.enabled ? BackendMode.VULKAN_VANILLA : BackendMode.OPENGL;
            if (RenderBackends.selected() != expectedBackend || RenderBackends.requestedMode() != expectedBackend
                    || RenderBackends.active() != current.enabled || (RenderBackends.raster() != null) != current.enabled)
                throw new IllegalStateException("Unexpected backend in " + current.name + ": " + RenderBackends.status());
            if (Rayvox.ANTI_ALIASING.get() != settings.antiAliasing() || Rayvox.UPSCALING.get() != settings.mode()
                    || Rayvox.UPSCALE_SHARPNESS.get() != settings.sharpness())
                throw new IllegalStateException("AA/upscaling settings changed in " + current.name);
            var main = Minecraft.getInstance().getMainRenderTarget();
            int expectedWidth = current.enabled ? settings.mode().dimension(upscalingOutputWidth) : upscalingOutputWidth;
            int expectedHeight = current.enabled ? settings.mode().dimension(upscalingOutputHeight) : upscalingOutputHeight;
            if (main.width != expectedWidth || main.height != expectedHeight
                    || main.viewWidth != expectedWidth || main.viewHeight != expectedHeight)
                throw new IllegalStateException("Wrong world resolution in " + current.name + ": " + main.width + "x" + main.height
                        + ", expected " + expectedWidth + "x" + expectedHeight);
            int[] viewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, viewport);
            if (viewport[0] != 0 || viewport[1] != 0 || viewport[2] != expectedWidth || viewport[3] != expectedHeight)
                throw new IllegalStateException("Wrong world viewport in " + current.name + ": " + Arrays.toString(viewport)
                        + ", expected [0, 0, " + expectedWidth + ", " + expectedHeight + "]");
            if (current.enabled) {
                var diagnostics = RenderBackends.diagnostics();
                if (diagnostics == null || diagnostics.width() != expectedWidth || diagnostics.height() != expectedHeight)
                    throw new IllegalStateException("Internal Vulkan dimensions disagree with world target in " + current.name);
            }
            current.worldWidth = main.width; current.worldHeight = main.height;
            current.worldViewport = viewport;
            current.worldTargetChecks++;
            upscalingWorldObserved = true;
        } catch (Throwable error) { fail(error); }
    }

    // AFTER_LEVEL still sees the internal target. The production GameRenderer hook resolves after
    // those listeners, so check restoration here (before any GUI layers), not in AFTER_LEVEL itself.
    private void observeUpscalingGui(RenderGuiEvent.Pre event) {
        if (finished || phase < 0 || framePhase != phase) return;
        try {
            var current = phases.get(phase);
            if (!upscalingWorldObserved) throw new IllegalStateException("GUI rendered before world observation in " + current.name);
            current.framebufferStatus = assertNativeUpscalingTarget(current);
            current.guiTargetChecks++;
            upscalingGuiObserved = true;
        } catch (Throwable error) { fail(error); }
    }

    private int assertNativeUpscalingTarget(Phase current) {
        var mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        if (mc.getWindow().getWidth() != upscalingOutputWidth || mc.getWindow().getHeight() != upscalingOutputHeight
                || main.width != upscalingOutputWidth || main.height != upscalingOutputHeight
                || main.viewWidth != upscalingOutputWidth || main.viewHeight != upscalingOutputHeight)
            throw new IllegalStateException("Native target was not restored in " + current.name + ": " + main.width + "x" + main.height
                    + ", window " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
                    + ", expected " + upscalingOutputWidth + "x" + upscalingOutputHeight);
        if (main.frameBufferId <= 0) throw new IllegalStateException("Missing main framebuffer in " + current.name);
        int previous = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, main.frameBufferId);
            int status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete main framebuffer in " + current.name + ": " + status);
            return status;
        } finally { glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previous); }
    }

    private void reportUpscaling(Phase current, Map<String, Object> report) {
        setup.join();
        if (current.worldTargetChecks == 0 || current.guiTargetChecks == 0 || current.nativeTargetChecks == 0)
            throw new IllegalStateException("No measured resolution checks in " + current.name);
        var main = Minecraft.getInstance().getMainRenderTarget();
        report.put("backend", RenderBackends.selected().name());
        report.put("internalWidth", current.worldWidth); report.put("internalHeight", current.worldHeight);
        report.put("worldViewport", current.worldViewport);
        report.put("nativeWidth", main.width); report.put("nativeHeight", main.height);
        report.put("nativeViewWidth", main.viewWidth); report.put("nativeViewHeight", main.viewHeight);
        report.put("mainFramebufferStatus", current.framebufferStatus);
        report.put("mainFramebufferComplete", current.framebufferStatus == GL_FRAMEBUFFER_COMPLETE);
        report.put("resolutionAssertions", Map.of("worldFrames", current.worldTargetChecks,
                "preGuiFrames", current.guiTargetChecks, "postRenderFrames", current.nativeTargetChecks));
        report.put("nativeImageParitySkipped", false);
        report.put("warmupSeconds", current.warm); report.put("measurementSeconds", current.seconds);
    }

    private Map<String, Object> captureUpscaling(Phase current, Path screenshot) throws Exception {
        var main = Minecraft.getInstance().getMainRenderTarget();
        if (main.frameBufferId <= 0) throw new IllegalStateException("Missing capture framebuffer in " + current.name);
        int width = main.width, height = main.height;
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), previousReadBuffer = glGetInteger(GL_READ_BUFFER);
        int alignment = glGetInteger(GL_PACK_ALIGNMENT), rowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int skipPixels = glGetInteger(GL_PACK_SKIP_PIXELS), skipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteBuffer pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), 3));
        int mainReadBuffer = -1;
        try {
            // Bypass RenderSystem's cached texture binding: read the actual main framebuffer, not a stale texture.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, main.frameBufferId);
            mainReadBuffer = glGetInteger(GL_READ_BUFFER);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete capture framebuffer in " + current.name);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, pixels);
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new IllegalStateException("Framebuffer read failed in " + current.name + ": " + error);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 3;
                    row[x] = (pixels.get(offset) & 255) << 16 | (pixels.get(offset + 1) & 255) << 8
                            | (pixels.get(offset + 2) & 255);
                }
                image.setRGB(0, height - 1 - y, width, 1, row, 0, width);
            }
        } finally {
            // Read-buffer selection belongs to each FBO; restore both FBOs as well as every affected pack state.
            if (mainReadBuffer != -1) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, main.frameBufferId);
                glReadBuffer(mainReadBuffer);
            }
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
            glReadBuffer(previousReadBuffer);
            glPixelStorei(GL_PACK_ALIGNMENT, alignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
            glPixelStorei(GL_PACK_SKIP_ROWS, skipRows);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
            MemoryUtil.memFree(pixels);
        }
        if (!ImageIO.write(image, "png", screenshot.toFile())) throw new IllegalStateException("PNG writer unavailable");
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] rgbRow = new byte[width * 3];
        double minimum = 1, maximum = 0, total = 0, squares = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                rgbRow[x * 3] = (byte) r; rgbRow[x * 3 + 1] = (byte) g; rgbRow[x * 3 + 2] = (byte) b;
                double luminance = (.2126 * r + .7152 * g + .0722 * b) / 255;
                minimum = Math.min(minimum, luminance); maximum = Math.max(maximum, luminance);
                total += luminance; squares += luminance * luminance;
            }
            digest.update(rgbRow);
        }
        double count = (double) width * height, mean = total / count;
        double variance = Math.max(0, squares / count - mean * mean);
        String hash = HexFormat.of().formatHex(digest.digest());
        var stats = new LinkedHashMap<String, Object>();
        stats.put("capture", "glReadPixels RGB from main GL_READ_FRAMEBUFFER / GL_COLOR_ATTACHMENT0");
        stats.put("width", width); stats.put("height", height);
        stats.put("luminanceMin", minimum); stats.put("luminanceMax", maximum);
        stats.put("luminanceMean", mean); stats.put("luminanceSpatialVariance", variance);
        stats.put("luminanceLimits", Map.of("minMean", .02, "maxMean", .98, "minSpatialVariance", .0005));
        stats.put("sha256RgbTopDown", hash);
        if (mean < .02 || mean > .98 || variance < .0005)
            throw new IllegalStateException("Blank/corrupt capture in " + current.name + ": " + stats);
        if (current.name.equals("native-static")) {
            upscalingNativeReference = image;
            upscalingNativeImageHash = hash;
        }
        if (upscalingNativeReference == null) throw new IllegalStateException("Missing fixed native capture in " + current.name);
        stats.put("nativeReference", "native-static.png");
        stats.put("nativeReferenceSha256", upscalingNativeImageHash);
        stats.put("nativeImageDifference", compareUpscalingImages(upscalingNativeReference, image, current.name));
        stats.put("differsFromNativeReference", !hash.equals(upscalingNativeImageHash));
        if (current.enabled && current.upscaling.antiAliasing() == AntiAliasing.OFF)
            upscalingOffImageHashes.put(current.upscaling, hash);
        if (current.upscaling.antiAliasing() == AntiAliasing.FXAA) {
            if (hash.equals(upscalingNativeImageHash))
                throw new IllegalStateException("FXAA capture is identical to the native reference in " + current.name + ": " + hash);
            var off = new UpscalingSettings(current.upscaling.mode(), AntiAliasing.OFF, current.upscaling.sharpness());
            String offHash = upscalingOffImageHashes.get(off);
            if (offHash != null) {
                stats.put("aaOffSha256", offHash);
                stats.put("differsFromAaOff", !hash.equals(offHash));
                if (hash.equals(offHash)) throw new IllegalStateException("OFF/FXAA captures are identical in " + current.name + ": " + hash);
            }
        }
        return stats;
    }

    private static Map<String, Object> compareUpscalingImages(BufferedImage reference, BufferedImage actual, String phase) {
        long total = 0, large = 0, pixels = (long) actual.getWidth() * actual.getHeight();
        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++) {
            // Compare the same normalized view even in the odd-sized resize phases.
            int referenceX = (int) ((2L * x + 1) * reference.getWidth() / (2L * actual.getWidth()));
            int referenceY = (int) ((2L * y + 1) * reference.getHeight() / (2L * actual.getHeight()));
            int expected = reference.getRGB(referenceX, referenceY), value = actual.getRGB(x, y), maximum = 0;
            for (int channel = 0; channel < 3; channel++) {
                int difference = Math.abs((expected >> (channel * 8) & 255) - (value >> (channel * 8) & 255));
                total += difference; maximum = Math.max(maximum, difference);
            }
            if (maximum > 32) large++;
        }
        double mean = total / (pixels * 3.0 * 255), fraction = large / (double) pixels;
        if (mean > .07 || fraction > .20)
            throw new IllegalStateException("AA/upscaling image differs excessively from fixed native reference in " + phase
                    + ": mean=" + mean + ", pixels over 32 fraction=" + fraction);
        return Map.of("meanAbsoluteRgb", mean, "pixelsOver32Fraction", fraction,
                "maxMeanAbsoluteRgb", .07, "maxPixelsOver32Fraction", .20,
                "toleranceNote", "Coarse spatial quality only; allows half-resolution filtering, sharpening and animated water; resized views use normalized coordinates");
    }

    private void poll() {
        if (!timers) return;
        for (int i = 0; i < pending.length; i++) if (pending[i]
                && glGetQueryObjecti(queries[i * 2 + 1], GL_QUERY_RESULT_AVAILABLE) != 0) {
            long start = glGetQueryObjectui64(queries[i * 2], GL_QUERY_RESULT);
            long end = glGetQueryObjectui64(queries[i * 2 + 1], GL_QUERY_RESULT);
            phases.get(queryPhase[i]).gpu.add(end - start); pending[i] = false;
        }
    }

    private void complete() throws Exception {
        glFinish(); poll();
        releaseFences();
        if (timers) glDeleteQueries(queries);
        for (int i = 0; i < phases.size(); i++) {
            measurements(phases.get(i), reports.get(i));
        }
        var mc = Minecraft.getInstance();
        var result = new LinkedHashMap<String, Object>();
        result.put("success", true); result.put("gpu", RayvoxEngine.gpu());
        result.put("width", mc.getMainRenderTarget().width); result.put("height", mc.getMainRenderTarget().height);
        result.put("renderDistance", mc.options.renderDistance().get()); result.put("phases", reports);
        result.put("scene", VANILLA_UPSCALING ? "vulkan-vanilla-upscaling-v1" : WORLD_CACHE ? "world-cache-v1" : HELD_PIPELINE ? "pipeline-held-v1" : GOVERNOR_PIPELINE ? "pipeline-governor-v2" : INDIRECT_PIPELINE ? "pipeline-gi-v2" : PIPELINE ? "pipeline-v3" : TEMPORAL ? "temporal-v1" : ENTITIES ? "entities-v1" : DIRECTIONAL_GLASS ? "directional-glass-v1"
                : DIRECTIONAL ? (DIRECTIONAL_MATERIALS ? "directional-v1" : "directional-basic-v1") : "fixed-room-v3"); result.put("seed", 42);
        if (VANILLA_UPSCALING) {
            result.put("plannedPhaseSeconds", phases.stream().mapToInt(p -> p.warm + p.seconds).sum());
            result.put("nativeImageParity", "Coarse fixed native-static reference: mean absolute RGB <= 0.07 and pixels with any channel difference > 32 <= 0.20; not exact pixel parity");
            result.put("aaCaptureDifference", "FXAA hashes must differ from native reference and matching OFF captures; animated water can also change hashes, so this is not proof of AA quality");
        }
        result.put("hidden", GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE);
        result.put("maxFramesInFlight", frameFences.length);
        result.put("createdAt", java.time.Instant.now().toString());
        result.put("build", JsonParser.parseString(Files.readString(mc.gameDirectory.toPath().resolve("benchmark-meta.json"))));
        result.put("rayvoxArtifact",Rayvox.class.getResource("Rayvox.class").toString());
        if(VULKAN)result.put("vulkanBindingArtifact",Class.forName("org.lwjgl.vulkan.VK").getResource("VK.class").toString());
        result.put("vulkanFixedResolution",Boolean.getBoolean("rayvox.vulkan.fixedResolution"));
        write(result); finished = true; mc.stop();
    }

    private static Map<String, Object> stats(LongArrayList data) {
        var report = new LinkedHashMap<String, Object>();
        report.put("samples", data.size());
        if (data.isEmpty()) return report;
        long[] sorted = data.toLongArray(); Arrays.sort(sorted);
        report.put("mean", Arrays.stream(sorted).average().orElseThrow() / 1e6);
        report.put("median", sorted[sorted.length / 2] / 1e6);
        report.put("p95", sorted[(int)((sorted.length - 1) * .95)] / 1e6);
        report.put("p99", sorted[(int)((sorted.length - 1) * .99)] / 1e6);
        return report;
    }
    private static Map<String,Object> compareImages(Path nativeCapture,Path vulkanCapture)throws Exception {
        try(var nativeStream=Files.newInputStream(nativeCapture);var vulkanStream=Files.newInputStream(vulkanCapture);
                var original=com.mojang.blaze3d.platform.NativeImage.read(nativeStream);var actual=com.mojang.blaze3d.platform.NativeImage.read(vulkanStream)) {
            if(original.getWidth()!=actual.getWidth()||original.getHeight()!=actual.getHeight())throw new IllegalStateException("Image comparison size mismatch");
            long total=0,large=0,pixels=(long)actual.getWidth()*actual.getHeight();
            for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++) {
                int expected=original.getPixelRGBA(x,y),value=actual.getPixelRGBA(x,y),maximum=0;
                for(int channel=0;channel<3;channel++){int difference=Math.abs((expected>>(channel*8)&255)-(value>>(channel*8)&255));total+=difference;maximum=Math.max(maximum,difference);}
                if(maximum>32)large++;
            }
            double mean=total/(pixels*3.0*255),fraction=large/(double)pixels;
            if(mean>.02||fraction>.01)throw new IllegalStateException("Vulkan vanilla image differs from native: mean="+mean+", large fraction="+fraction);
            return Map.of("meanAbsoluteRgb",mean,"pixelsOver32Fraction",fraction,"toleranceNote","Allows lightmap flicker and animated particles between captures");
        }
    }

    private static void measurements(Phase phase, Map<String, Object> report) {
        if (phase.wall.isEmpty()) throw new IllegalStateException("No measured frames for " + phase.name);
        var wall = stats(phase.wall);
        report.put("frameMs", wall); report.put("renderThreadMs", stats(phase.cpu)); report.put("gpuRenderMs", stats(phase.gpu));
        report.put("cpuWorkMs", stats(phase.cpuWork));
        var gpuStages=new LinkedHashMap<String,Object>();phase.gpuStages.forEach((name,samples)->gpuStages.put(name,stats(samples)));report.put("vulkanStageMs",gpuStages);
        report.put("meanFps", 1000.0 / ((Number)wall.get("mean")).doubleValue());
    }

    private static boolean configDeferred(Phase phase) {
        return phase.name.startsWith("deferred-") && !phase.name.equals("deferred-history-off");
    }

    private void fail(Throwable error) {
        if (finished) return;
        finished = true; LogUtils.getLogger().error("Rayvox client benchmark failed", error);
        releaseFences();
        try { write(Map.of("success", false, "error", error.toString(), "completedPhases", reports)); }
        catch (Exception failure) { LogUtils.getLogger().error("Cannot write benchmark failure", failure); }
        Minecraft.getInstance().stop();
    }

    private void releaseFences() {
        for (int i = 0; i < frameFences.length; i++) if (frameFences[i] != 0) {
            glDeleteSync(frameFences[i]); frameFences[i] = 0;
        }
    }

    private static void write(Map<String, ?> result) throws Exception {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("benchmark-result.json");
        com.google.gson.JsonSerializer<Double> finite=(value,type,context)->Double.isFinite(value)?new com.google.gson.JsonPrimitive(value):com.google.gson.JsonNull.INSTANCE;
        Files.writeString(path, new GsonBuilder().registerTypeAdapter(Double.class,finite).registerTypeAdapter(double.class,finite).setPrettyPrinting().create().toJson(result));
    }

    private record UpscalingSettings(UpscalingMode mode, AntiAliasing antiAliasing, int sharpness) { }

    private static final class Phase {
        final String name;
        final boolean enabled, materials, indirect, moving;
        final int warm, seconds, limit, perTile, expectedLights;
        final UpscalingSettings upscaling;
        int worldWidth, worldHeight, framebufferStatus;
        int[] worldViewport;
        long worldTargetChecks, guiTargetChecks, nativeTargetChecks;
        final LongArrayList wall = new LongArrayList(16384), cpu = new LongArrayList(16384), gpu = new LongArrayList(16384);
        final LongArrayList cpuWork = new LongArrayList(16384);
        final Map<String,LongArrayList> gpuStages=new LinkedHashMap<>();
        void gpuSample(String stage,double millis){if(Double.isFinite(millis)&&millis>=0)gpuStages.computeIfAbsent(stage,ignored->new LongArrayList()).add((long)(millis*1_000_000));}
        final int[] qualityFrames = new int[5];
        final List<String> qualityDecisions = new ArrayList<>();
        int lastQualityLevel;
        Phase(String name, boolean enabled, int warm, UpscalingMode mode, AntiAliasing antiAliasing, int sharpness) {
            this(name, enabled, false, false, false, warm, 3, 32, 4, 10, new UpscalingSettings(mode, antiAliasing, sharpness));
        }
        Phase(String name, boolean enabled, boolean materials, boolean indirect, boolean moving, int warm, int seconds) {
            this(name, enabled, materials, indirect, moving, warm, seconds, 32, 4, 10);
        }
        Phase(String name, boolean enabled, boolean materials, boolean indirect, boolean moving, int warm, int seconds,
                int limit, int perTile, int expectedLights) {
            this(name, enabled, materials, indirect, moving, warm, seconds, limit, perTile, expectedLights, null);
        }
        Phase(String name, boolean enabled, boolean materials, boolean indirect, boolean moving, int warm, int seconds,
                int limit, int perTile, int expectedLights, UpscalingSettings upscaling) {
            this.name = name; this.enabled = enabled; this.materials = materials; this.indirect = indirect;
            this.moving = moving; this.warm = warm; this.seconds = seconds;
            this.limit = limit; this.perTile = perTile; this.expectedLights = expectedLights;
            this.upscaling = upscaling;
        }
    }
}
