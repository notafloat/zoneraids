package com.zoneraids.world;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block writer used by the map builders. Writes skip neighbour updates (no redstone, no physics
 * cascades) but keep shape updates, so fences, walls and panes connect as usual.
 */
final class Painter {
    /** Send to clients, no neighbour updates. */
    static final int FLAGS = Block.UPDATE_CLIENTS;
    /** Terrain: full blocks only, so shape updates can be skipped too. */
    static final int TERRAIN_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    final ServerLevel level;

    Painter(ServerLevel level) {
        this.level = level;
    }

    void set(int x, int y, int z, BlockState state) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (connects(state)) {
            state = Block.updateFromNeighbourShapes(state, level, pos);
        }
        level.setBlock(pos, state, FLAGS);
    }

    void setTerrain(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, TERRAIN_FLAGS);
    }

    BlockState get(int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z));
    }

    /** Air, plants, snow layers or fluids: things a foundation or pillar may replace. */
    boolean isOpen(int x, int y, int z) {
        BlockState state = get(x, y, z);
        return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty();
    }

    /** Fills downwards from {@code y} until solid ground (at most {@code maxDepth} blocks). */
    void pillarDown(int x, int y, int z, BlockState state, int maxDepth) {
        for (int i = 0; i < maxDepth && isOpen(x, y - i, z); i++) {
            set(x, y - i, z, state);
        }
    }

    void sign(int x, int y, int z, BlockState signState, String... lines) {
        set(x, y, z, signState);
        if (level.getBlockEntity(new BlockPos(x, y, z)) instanceof SignBlockEntity sign) {
            SignText text = sign.getFrontText().setColor(DyeColor.WHITE).setHasGlowingText(true);
            for (int i = 0; i < Math.min(4, lines.length); i++) {
                text = text.setMessage(i, Component.literal(lines[i]));
            }
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
        }
    }

    private static boolean connects(BlockState state) {
        Block block = state.getBlock();
        return block instanceof CrossCollisionBlock || block instanceof WallBlock || block instanceof StairBlock;
    }

    static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }
}
