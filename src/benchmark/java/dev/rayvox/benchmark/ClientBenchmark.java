package dev.rayvox.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.rayvox.Rayvox;
import dev.rayvox.RayvoxEngine;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import org.lwjgl.opengl.GL;
import org.lwjgl.glfw.GLFW;
import static org.lwjgl.opengl.GL33C.*;

/** Real client, world, mixins and renderer; loaded only by runBenchmarkClient. */
@Mod(value = "rayvox_benchmark", dist = Dist.CLIENT)
public final class ClientBenchmark {
    private static ClientBenchmark instance;
    private static final String SCENE = System.getProperty("rayvox.benchmark.scene", "room");
    private static final boolean DIRECTIONAL_GLASS = SCENE.equals("directional-glass");
    private static final boolean DIRECTIONAL = SCENE.equals("directional") || SCENE.equals("directional-basic") || DIRECTIONAL_GLASS;
    private static final boolean DIRECTIONAL_MATERIALS = !SCENE.equals("directional-basic");
    private static final String WORLD = DIRECTIONAL_GLASS ? "rayvox-benchmark-directional-glass"
            : DIRECTIONAL ? "rayvox-benchmark-directional" : "rayvox-benchmark-room-v3";
    private final long boot = System.nanoTime();
    private final List<Phase> phases = DIRECTIONAL ? List.of(
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
    private int phase = -1, framePhase = -1, activeQuery = -1, nextQuery, ticks, phaseTick;
    private long phaseStart, lastFrame, renderStart;
    private boolean opening, preparing, finished, timers;
    private CompletableFuture<Void> setup;
    private final List<Map<String, Object>> reports = new ArrayList<>();

    public ClientBenchmark(IEventBus bus) {
        if (!Boolean.getBoolean("rayvox.benchmark")) throw new IllegalStateException("Benchmark run property missing");
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        if (finished) return;
        var mc = Minecraft.getInstance();
        try {
            ticks++;
            if (System.nanoTime() - boot > 240_000_000_000L) throw new IllegalStateException("Client benchmark timed out: " + mc.screen);
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
                    var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                    if (player == null) throw new IllegalStateException("Integrated server player missing");
                    player.setGameMode(GameType.SPECTATOR);
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
            double x = .5 + (current.moving ? Math.sin((ticks - phaseTick) * .035) * (DIRECTIONAL ? 24 : 6) : 0);
            mc.player.setPos(x, DIRECTIONAL ? 68 : 66, DIRECTIONAL ? 28 : 13.5);
            mc.player.setYRot(180); mc.player.setXRot(DIRECTIONAL ? 25 : 12);
        } catch (Throwable error) { fail(error); }
    }

    private static void configure(Minecraft mc) {
        if (GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_VISIBLE) != GLFW.GLFW_FALSE)
            throw new IllegalStateException("Benchmark window must be hidden");
        mc.options.pauseOnLostFocus = false;
        mc.options.hideGui = true;
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        mc.options.renderDistance().set(6);
        mc.options.simulationDistance().set(5);
        mc.options.bobView().set(false);
        mc.options.fov().set(70);
        mc.getWindow().updateVsync(false);
        mc.getWindow().setFramerateLimit(260);
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
        phase = index; phaseTick = ticks; phaseStart = System.nanoTime();
        var config = phases.get(index);
        Rayvox.ENABLED.set(config.enabled); Rayvox.MATERIALS.set(config.materials); Rayvox.INDIRECT.set(config.indirect);
        Rayvox.HISTORY.set(true); Rayvox.SHADOWS.set(true); Rayvox.SUNLIGHT.set(DIRECTIONAL); Rayvox.DYNAMIC.set(false);
        Rayvox.CACHE_RADIUS.set(2); Rayvox.ACTIVE_LIGHTS.set(config.limit); Rayvox.LIGHTS_PER_SECTION.set(config.perTile);
        if (DIRECTIONAL) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> server.overworld().setDayTime(config.name.startsWith("moon-") ? 18000 : 6000), server);
        } else if (config.expectedLights == 64) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            setup = CompletableFuture.runAsync(() -> {
                prepareDense(server.overworld());
                if (config.name.startsWith("partial-")) preparePartial(server.overworld());
            }, server);
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
        if (finished || phase < 0) return;
        poll();
        if (System.nanoTime() - phaseStart < phases.get(phase).warm * 1_000_000_000L) return;
        framePhase = phase; renderStart = System.nanoTime();
        if (timers && !pending[nextQuery]) {
            activeQuery = nextQuery; nextQuery = (nextQuery + 1) % pending.length;
            queryPhase[activeQuery] = phase;
            glQueryCounter(queries[activeQuery * 2], GL_TIMESTAMP);
        }
    }

    private void renderedFrame() {
        if (finished || framePhase < 0) return;
        phases.get(framePhase).cpu.add(System.nanoTime() - renderStart);
        if (activeQuery >= 0) {
            glQueryCounter(queries[activeQuery * 2 + 1], GL_TIMESTAMP);
            pending[activeQuery] = true; activeQuery = -1;
        }
    }

    private void finishFrame() {
        long now = System.nanoTime();
        try {
            if (!finished && phase >= 0) {
                if (framePhase == phase && lastFrame != 0) phases.get(phase).wall.add(now - lastFrame);
                var current = phases.get(phase);
                if (now - phaseStart >= (current.warm + current.seconds) * 1_000_000_000L) {
                    var mc = Minecraft.getInstance();
                    if (current.enabled && (RayvoxEngine.lights().active.size() != current.expectedLights || !RayvoxEngine.status().equals("Terrain shader ready")))
                        throw new IllegalStateException("Rayvox did not activate: " + RayvoxEngine.status() + "; " + RayvoxEngine.lightStats());
                    int error = glGetError();
                    if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error in " + current.name + ": " + error);
                    Path screenshot = mc.gameDirectory.toPath().resolve(current.name + ".png");
                    try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(screenshot); }
                    var report = new LinkedHashMap<String, Object>();
                    report.put("phase", current.name); report.put("engine", RayvoxEngine.status());
                    report.put("activeLightLimit", current.limit); report.put("lightsPerTile", current.perTile);
                    report.put("cache", RayvoxEngine.cacheStats()); report.put("lights", RayvoxEngine.lightStats());
                    report.put("history", RayvoxEngine.historyStats()); report.put("screenshot", screenshot.toAbsolutePath().toString());
                    reports.add(report);
                    if (phase + 1 < phases.size()) beginPhase(phase + 1);
                    else complete();
                }
            }
        } catch (Throwable error) { fail(error); }
        lastFrame = now;
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
        if (timers) glDeleteQueries(queries);
        for (int i = 0; i < phases.size(); i++) {
            var p = phases.get(i); var report = reports.get(i);
            if (p.wall.isEmpty()) throw new IllegalStateException("No measured frames for " + p.name);
            var wall = stats(p.wall);
            report.put("frameMs", wall); report.put("renderThreadMs", stats(p.cpu)); report.put("gpuRenderMs", stats(p.gpu));
            report.put("meanFps", 1000.0 / ((Number)wall.get("mean")).doubleValue());
        }
        var mc = Minecraft.getInstance();
        var result = new LinkedHashMap<String, Object>();
        result.put("success", true); result.put("gpu", RayvoxEngine.gpu());
        result.put("width", mc.getMainRenderTarget().width); result.put("height", mc.getMainRenderTarget().height);
        result.put("renderDistance", mc.options.renderDistance().get()); result.put("phases", reports);
        result.put("scene", DIRECTIONAL_GLASS ? "directional-glass-v1"
                : DIRECTIONAL ? (DIRECTIONAL_MATERIALS ? "directional-v1" : "directional-basic-v1") : "fixed-room-v3"); result.put("seed", 42);
        result.put("hidden", GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE);
        result.put("createdAt", java.time.Instant.now().toString());
        result.put("build", JsonParser.parseString(Files.readString(mc.gameDirectory.toPath().resolve("benchmark-meta.json"))));
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

    private void fail(Throwable error) {
        if (finished) return;
        finished = true; LogUtils.getLogger().error("Rayvox client benchmark failed", error);
        try { write(Map.of("success", false, "error", error.toString(), "completedPhases", reports)); }
        catch (Exception failure) { LogUtils.getLogger().error("Cannot write benchmark failure", failure); }
        Minecraft.getInstance().stop();
    }

    private static void write(Map<String, ?> result) throws Exception {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("benchmark-result.json");
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private static final class Phase {
        final String name;
        final boolean enabled, materials, indirect, moving;
        final int warm, seconds, limit, perTile, expectedLights;
        final LongArrayList wall = new LongArrayList(16384), cpu = new LongArrayList(16384), gpu = new LongArrayList(16384);
        Phase(String name, boolean enabled, boolean materials, boolean indirect, boolean moving, int warm, int seconds) {
            this(name, enabled, materials, indirect, moving, warm, seconds, 32, 4, 10);
        }
        Phase(String name, boolean enabled, boolean materials, boolean indirect, boolean moving, int warm, int seconds,
                int limit, int perTile, int expectedLights) {
            this.name = name; this.enabled = enabled; this.materials = materials; this.indirect = indirect;
            this.moving = moving; this.warm = warm; this.seconds = seconds;
            this.limit = limit; this.perTile = perTile; this.expectedLights = expectedLights;
        }
    }
}
