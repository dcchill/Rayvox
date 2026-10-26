package dev.rayvox;

import java.util.Arrays;
import java.util.ArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Client-thread world extraction only; no GL calls or chunk loading. */
public final class VoxelWorldCache {
    public final VoxelGrid grid;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final SectionScheduler scheduler;
    public int extractedLastTick;
    private final LightColors colors;
    private final MaterialTable materials;
    private final ShapeMask.Cache shapeMask = new ShapeMask.Cache();
    private final java.util.Map<net.minecraft.world.level.block.Block, String> blockIds = new java.util.IdentityHashMap<>();

    public VoxelWorldCache(int radius, LightColors colors, MaterialTable materials) {
        grid = new VoxelGrid(radius);
        scheduler = new SectionScheduler(grid.slots.length);
        this.colors = colors;
        this.materials = materials;
    }

    public void tick(ClientLevel level, int cameraX, int cameraY, int cameraZ, int budget) {
        grid.recenter(Math.floorDiv(cameraX, 16), Math.floorDiv(cameraY, 16), Math.floorDiv(cameraZ, 16));
        // Polling identity also catches distance changes and unloads missed during transitions.
        for (int z = grid.centerZ - grid.radius; z <= grid.centerZ + grid.radius; z++) {
            for (int x = grid.centerX - grid.radius; x <= grid.centerX + grid.radius; x++) {
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                for (int y = grid.centerY - grid.radius; y <= grid.centerY + grid.radius; y++) {
                    var slot = grid.get(x, y, z);
                    var source = y < level.getMinSection() || y >= level.getMaxSection() ? null : chunk;
                    if (slot.source != source) { slot.setSource(source); grid.revision++; }
                }
            }
        }
        extractedLastTick = 0;
        scheduler.tick(grid);
        budget = scheduler.budget(budget);
        long deadline = System.nanoTime() + 2_000_000L;
        while (extractedLastTick < budget) {
            int next = scheduler.next(grid);
            if (next < 0) break;
            var slot = grid.slots[next];
            var chunk = (LevelChunk)slot.source;
            long start = System.nanoTime();
            extract(level, chunk.getSection(level.getSectionIndexFromSectionY(slot.y)), slot);
            scheduler.completed(next, System.nanoTime() - start);
            slot.dirty = false;
            slot.ready = true;
            grid.revision++;
            extractedLastTick++;
            // One section is the minimum work unit, even if modded shape queries exceed this soft limit.
            if (System.nanoTime() >= deadline) break;
        }
    }

    void extract(net.minecraft.world.level.BlockGetter level, net.minecraft.world.level.chunk.LevelChunkSection section, VoxelGrid.Slot slot) {
        var lights = new ArrayList<LightSourceManager.Light>();
        slot.lightOverflow = false;
        if (section.hasOnlyAir()) {
            Arrays.fill(slot.data, (byte)0);
            slot.lights = java.util.List.of();
            return;
        }
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    var state = section.getBlockState(x, y, z);
                    cursor.set(slot.x * 16 + x, slot.y * 16 + y, slot.z * 16 + z);
                    int index = x + 16 * (y + 16 * z);
                    int emission = state.getLightEmission(level, cursor);
                    // Mixed sections are often mostly air. Avoid registry, material, shape and albedo queries for it.
                    if (state.isAir() && emission == 0) {
                        slot.data[index] = 0;
                        continue;
                    }
                    String blockId = blockIds.computeIfAbsent(state.getBlock(), block -> BuiltInRegistries.BLOCK.getKey(block).toString());
                    if (emission > 0) {
                        var definition = colors.resolve(state, emission);
                        if (definition.contributes()) {
                            if (lights.size() == LightSourceManager.PER_SNAPSHOT) slot.lightOverflow = true;
                            else lights.add(new LightSourceManager.Light(blockId, cursor.getX() + 0.5, cursor.getY() + 0.5,
                                        cursor.getZ() + 0.5, definition));
                        }
                    }
                    byte material = VoxelMaterials.classify(state, level, cursor, materials.blocks.get(blockId));
                    slot.data[index] = material;
                    if (material == 2 || material == 3 || Byte.toUnsignedInt(material) >= 16) {
                        var shape = material == 3 ? state.getFluidState().getShape(level, cursor) : state.getShape(level, cursor);
                        slot.shapes[index] = shapeMask.of(shape, material != 3 && !state.getBlock().hasDynamicShape());
                        if (shapeMask.boxEncoded()) slot.data[index] |= (byte)BoxShape.FLAG;
                    }
                    // shortcut: map colors approximate texture reflectance; replace with atlas sampling for higher-fidelity GI.
                    int color = state.getMapColor(level, cursor).col;
                    slot.albedo[index * 3] = (byte)(color >> 16);
                    slot.albedo[index * 3 + 1] = (byte)(color >> 8);
                    slot.albedo[index * 3 + 2] = (byte)color;
                }
            }
        }
        slot.lights = java.util.List.copyOf(lights);
    }

    public void columnChanged(int x, int z, boolean unload) {
        for (var slot : grid.slots) {
            if (Math.abs(slot.x - x) <= 1 && Math.abs(slot.z - z) <= 1) {
                if (unload && slot.x == x && slot.z == z) { slot.setSource(null); grid.revision++; }
                else grid.markDirty(slot.x, slot.y, slot.z);
            }
        }
    }
}
