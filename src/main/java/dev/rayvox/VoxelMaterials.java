package dev.rayvox;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Material classification shared by world extraction and real block-state checks. */
final class VoxelMaterials {
    static byte classify(BlockState state, BlockGetter world, BlockPos position, Integer filter) {
        if (state.isAir()) return 0;
        if (filter != null) return filter.byteValue();
        if (state.isSolidRender(world, position)) return 1;
        // Vanilla skylight passes around slabs/stairs; zero light-block does not make their geometry transparent.
        // Test the solid shape before fluid so waterlogged stairs retain their opaque geometry.
        boolean empty = state.getShape(world, position).isEmpty();
        boolean occludes = state.canOcclude() || state.useShapeForLightOcclusion();
        if (!empty && !occludes) {
            // Skylight also passes around doors, bars, chains and other opaque collidable geometry.
            // Preserve transparent blocks and glass panes, which share their base class with iron bars.
            boolean transparent = state.getBlock() instanceof net.minecraft.world.level.block.HalfTransparentBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock
                    || state.is(net.minecraft.world.level.block.Blocks.GLASS_PANE)
                    || state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE;
            occludes = !transparent && !state.getCollisionShape(world, position).isEmpty();
        }
        if (!empty && occludes) return 2;
        var fluid = state.getFluidState();
        if (fluid.is(net.minecraft.tags.FluidTags.WATER) || fluid.getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) return 3;
        if (!fluid.isEmpty()) return 4;
        if (empty) return 0;
        return (byte)(state.getLightBlock(world, position) > 0 ? 2 : 4);
    }
}
