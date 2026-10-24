package com.zoneraids.survival;

import com.zoneraids.ZoneRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What the survival builders share: a seeded random and block helpers. Coordinates in the helpers
 * are relative to the site's centre on the ground ({@code y = 0} is the first block above it).
 */
abstract class SiteBuilder {
    protected final ServerLevel level;
    protected final BlockPos origin;
    protected final RandomSource random;

    protected SiteBuilder(ServerLevel level, BlockPos origin, long seed) {
        this.level = level;
        this.origin = origin;
        this.random = RandomSource.create(seed);
    }

    /** Ground level for a site centred at x/z: the surface there, or null over water and lava. */
    static BlockPos ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getFluidState(pos.below()).isEmpty() || y <= level.getMinBuildHeight() + 20) {
            return null;
        }
        return pos;
    }

    /** Levels a square of the given half size: clears what stands above the ground, fills dips, lays the floor mix. */
    protected void flatten(int half, Block... floor) {
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                for (int y = 0; y <= 14; y++) {
                    set(x, y, z, Blocks.AIR);
                }
                for (int y = -2; y >= -8; y--) {
                    BlockPos pos = at(x, y, z);
                    if (!level.getBlockState(pos).isAir() && level.getFluidState(pos).isEmpty()) {
                        break;
                    }
                    set(x, y, z, Blocks.DIRT);
                }
                set(x, -1, z, pick(floor));
            }
        }
    }

    protected void chest(int x, int y, int z, String table) {
        set(x, y, z, Blocks.CHEST);
        if (level.getBlockEntity(at(x, y, z)) instanceof RandomizableContainerBlockEntity container) {
            container.setLootTable(new ResourceLocation(ZoneRaids.MODID, table), random.nextLong());
        }
    }

    protected Block pick(Block... blocks) {
        return blocks[random.nextInt(blocks.length)];
    }

    protected BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    protected void set(int x, int y, int z, Block block) {
        setState(x, y, z, block.defaultBlockState());
    }

    protected void setState(int x, int y, int z, BlockState state) {
        level.setBlock(at(x, y, z), state, Block.UPDATE_CLIENTS);
    }
}
