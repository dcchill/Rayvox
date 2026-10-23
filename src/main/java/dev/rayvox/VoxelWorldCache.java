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

    public VoxelWorldCache(int radius, LightColors colors, MaterialTable materials) {
        grid = new VoxelGrid(radius);
        scheduler = new SectionScheduler(grid.slots.length);
        this.colors = colors;
        this.materials = materials;
    }

    public void tick(ClientLevel level, int cameraX, int cameraY, int cameraZ, int budget) {
        grid.recenter(Math.floorDiv(cameraX, 16), Math.floorDiv(cameraY, 16), Math.floorDiv(cameraZ, 16));
        // Polling identity also catches distance changes and unloads missed during transitions.
        for (var slot : grid.slots) {
            var chunk = slot.y < level.getMinSection() || slot.y >= level.getMaxSection() ? null
                    : level.getChunkSource().getChunk(slot.x, slot.z, ChunkStatus.FULL, false);
            if (slot.source != chunk) { slot.setSource(chunk); grid.revision++; }
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
            extract(level, chunk, slot);
            scheduler.completed(next, System.nanoTime() - start);
            slot.dirty = false;
            slot.ready = true;
            grid.revision++;
            extractedLastTick++;
            // One section is the minimum work unit, even if modded shape queries exceed this soft limit.
            if (System.nanoTime() >= deadline) break;
        }
    }

    private void extract(ClientLevel level, LevelChunk chunk, VoxelGrid.Slot slot) {
        var section = chunk.getSection(level.getSectionIndexFromSectionY(slot.y));
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
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    int emission = state.getLightEmission(level, cursor);
                    if (emission > 0) {
                        if (lights.size() == LightSourceManager.PER_SNAPSHOT) slot.lightOverflow = true;
                        else {
                            String id = blockId;
                            var definition = colors.resolve(state, emission);
                            lights.add(new LightSourceManager.Light(id, cursor.getX() + 0.5, cursor.getY() + 0.5,
                                    cursor.getZ() + 0.5, definition));
                        }
                    }
                    byte material;
                    if (state.isAir()) material = 0;
                    else if (materials.blocks.containsKey(blockId)) material = materials.blocks.get(blockId).byteValue();
                    else if (state.isSolidRender(level, cursor)) material = 1;
                    else if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) material = 3;
                    else if (!state.getFluidState().isEmpty()) material = 4;
                    else if (state.getShape(level, cursor).isEmpty()) material = 0;
                    else material = (byte)(state.getLightBlock(level, cursor) == 0 ? 4 : 2);
                    int index = VoxelGrid.voxelIndex(x, y, z);
                    slot.data[index] = material;
                    if (material == 2) {
                        long mask = 0;
                        for (var box : state.getShape(level, cursor).toAabbs())
                            mask |= ShapeMask.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
                        slot.shapes[index] = mask;
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
