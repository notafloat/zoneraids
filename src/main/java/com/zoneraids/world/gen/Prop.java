package com.zoneraids.world.gen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Block helpers for the Zone features, in coordinates turned along the prop: {@code a} runs along it,
 * {@code b} across, {@code y} up from the first block above the ground at the origin. A prop stays
 * within 8 blocks of its origin, so it never writes outside the chunks a feature may touch.
 */
final class Prop {
    final WorldGenLevel level;
    final BlockPos origin;
    final RandomSource random;
    /** The prop runs along X (or along Z). */
    final boolean alongX;

    Prop(WorldGenLevel level, BlockPos origin, RandomSource random) {
        this.level = level;
        this.origin = origin;
        this.random = random;
        this.alongX = random.nextBoolean();
    }

    /** Firm dry ground under {@code pos}: not water, ice, leaves or snow, and no fluid at pos itself. */
    static boolean firmGround(WorldGenLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        return level.getFluidState(pos).isEmpty() && level.getFluidState(below).isEmpty()
                && ground.isFaceSturdy(level, below, Direction.UP)
                && !ground.is(BlockTags.LEAVES) && !ground.is(BlockTags.ICE) && !ground.is(Blocks.SNOW_BLOCK)
                && !ground.is(Blocks.POWDER_SNOW);
    }

    BlockPos at(int a, int y, int b) {
        return origin.offset(alongX ? a : b, y, alongX ? b : a);
    }

    /** Places the block where there is air, a plant or snow; anything solid is left as it is. */
    void put(int a, int y, int b, BlockState state) {
        BlockPos pos = at(a, y, b);
        BlockState old = level.getBlockState(pos);
        if (old.isAir() || old.canBeReplaced()) {
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        }
    }

    void put(int a, int y, int b, Block block) {
        put(a, y, b, block.defaultBlockState());
    }

    /** Replaces whatever is there (ground blocks under a prop). */
    void force(int a, int y, int b, BlockState state) {
        level.setBlock(at(a, y, b), state, Block.UPDATE_CLIENTS);
    }

    boolean free(int a, int y, int b) {
        BlockState old = level.getBlockState(at(a, y, b));
        return old.isAir() || old.canBeReplaced();
    }

    /** Height of the ground at a column, relative to the origin: the y of its first block above the ground. */
    int ground(int a, int b) {
        BlockPos pos = at(a, 0, b);
        return surface(level, pos.getX(), pos.getZ()) - origin.getY();
    }

    /**
     * The y of the first block above the ground at x/z: under plants, snow and leaves, on top of water.
     * Scanned down from the top block, so it works on unfinished neighbouring chunks and in /place feature alike.
     */
    static int surface(WorldGenLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
        for (int i = 0; i < 32 && pos.getY() > level.getMinBuildHeight(); i++) {
            pos.move(Direction.DOWN);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || !(state.isAir() || state.canBeReplaced() || state.is(BlockTags.LEAVES))) {
                break;
            }
        }
        return pos.getY() + 1;
    }

    /** The ground under the whole rectangle is within {@code slack} blocks of the origin and dry. */
    boolean flat(int a0, int a1, int b0, int b1, int slack) {
        for (int a = a0; a <= a1; a++) {
            for (int b = b0; b <= b1; b++) {
                int g = ground(a, b);
                if (Math.abs(g) > slack || !level.getFluidState(at(a, g - 1, b)).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Fills air and plants under a block of the prop with dirt, so a prop on a slope does not hang in the air. */
    void foundation(int a, int y, int b) {
        for (int d = 1; d <= 3; d++) {
            if (!free(a, y - d, b)) {
                return;
            }
            force(a, y - d, b, Blocks.DIRT.defaultBlockState());
        }
    }

    boolean chance(float p) {
        return random.nextFloat() < p;
    }

    int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    Block pick(Block... blocks) {
        return blocks[random.nextInt(blocks.length)];
    }

    /** Weathered Soviet concrete: mostly light grey, with stone, andesite and moss. */
    Block concrete() {
        float f = random.nextFloat();
        return f < 0.5F ? Blocks.LIGHT_GRAY_CONCRETE : f < 0.68F ? Blocks.STONE : f < 0.82F ? Blocks.ANDESITE
                : f < 0.92F ? Blocks.CRACKED_STONE_BRICKS : Blocks.MOSSY_COBBLESTONE;
    }

    BlockState log(Block log, boolean along) {
        Direction.Axis axis = along == alongX ? Direction.Axis.X : Direction.Axis.Z;
        return log.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    /** Iron bars joined along (or across) the prop. */
    BlockState bars(boolean along) {
        boolean x = along == alongX;
        return Blocks.IRON_BARS.defaultBlockState()
                .setValue(x ? CrossCollisionBlock.EAST : CrossCollisionBlock.NORTH, true)
                .setValue(x ? CrossCollisionBlock.WEST : CrossCollisionBlock.SOUTH, true);
    }
}
