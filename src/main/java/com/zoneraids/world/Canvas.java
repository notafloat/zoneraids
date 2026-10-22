package com.zoneraids.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Random;

/**
 * A rotated local frame on top of a {@link Painter}. Structures are written in local coordinates
 * (u = local east, w = local south, y relative to the origin); local {@link Direction}s in block
 * states are rotated together with the positions.
 */
final class Canvas {
    final Painter painter;
    final Random random;
    final int ox;
    final int oy;
    final int oz;
    final Rotation rotation;

    Canvas(Painter painter, Random random, int ox, int oy, int oz, Rotation rotation) {
        this.painter = painter;
        this.random = random;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.rotation = rotation;
    }

    int worldX(int u, int w) {
        return switch (rotation) {
            case NONE -> ox + u;
            case CLOCKWISE_90 -> ox - w;
            case CLOCKWISE_180 -> ox - u;
            case COUNTERCLOCKWISE_90 -> ox + w;
        };
    }

    int worldZ(int u, int w) {
        return switch (rotation) {
            case NONE -> oz + w;
            case CLOCKWISE_90 -> oz + u;
            case CLOCKWISE_180 -> oz - w;
            case COUNTERCLOCKWISE_90 -> oz - u;
        };
    }

    Direction dir(Direction local) {
        return rotation.rotate(local);
    }

    // ------------------------------------------------------------------ writing

    void set(int u, int y, int w, BlockState state) {
        painter.set(worldX(u, w), oy + y, worldZ(u, w), state.rotate(rotation));
    }

    void set(int u, int y, int w, Block block) {
        set(u, y, w, block.defaultBlockState());
    }

    void set(int u, int y, int w, Palette palette) {
        set(u, y, w, palette.pick(random));
    }

    void fill(int u1, int y1, int w1, int u2, int y2, int w2, BlockState state) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
            for (int u = Math.min(u1, u2); u <= Math.max(u1, u2); u++) {
                for (int w = Math.min(w1, w2); w <= Math.max(w1, w2); w++) {
                    set(u, y, w, state);
                }
            }
        }
    }

    void fill(int u1, int y1, int w1, int u2, int y2, int w2, Block block) {
        fill(u1, y1, w1, u2, y2, w2, block.defaultBlockState());
    }

    void fill(int u1, int y1, int w1, int u2, int y2, int w2, Palette palette) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
            for (int u = Math.min(u1, u2); u <= Math.max(u1, u2); u++) {
                for (int w = Math.min(w1, w2); w <= Math.max(w1, w2); w++) {
                    set(u, y, w, palette.pick(random));
                }
            }
        }
    }

    void air(int u1, int y1, int w1, int u2, int y2, int w2) {
        fill(u1, y1, w1, u2, y2, w2, Painter.air());
    }

    /** Hollow walls of a box (no floor or roof). {@code holes} is the chance a wall block is missing. */
    void walls(int u1, int y1, int w1, int u2, int y2, int w2, Palette palette, double holes) {
        for (int y = y1; y <= y2; y++) {
            for (int u = u1; u <= u2; u++) {
                for (int w = w1; w <= w2; w++) {
                    if (u != u1 && u != u2 && w != w1 && w != w2) {
                        continue;
                    }
                    boolean corner = (u == u1 || u == u2) && (w == w1 || w == w2);
                    if (!corner && y > y1 && random.nextDouble() < holes) {
                        set(u, y, w, Painter.air());
                    } else {
                        set(u, y, w, palette.pick(random));
                    }
                }
            }
        }
    }

    /** Fills the space under a footprint down to the ground so buildings on slopes do not float. */
    void foundation(int u1, int w1, int u2, int w2, BlockState state) {
        for (int u = Math.min(u1, u2); u <= Math.max(u1, u2); u++) {
            for (int w = Math.min(w1, w2); w <= Math.max(w1, w2); w++) {
                painter.pillarDown(worldX(u, w), oy - 1, worldZ(u, w), state, 16);
            }
        }
    }

    void pillarDown(int u, int y, int w, BlockState state) {
        painter.pillarDown(worldX(u, w), oy + y, worldZ(u, w), state.rotate(rotation), 24);
    }

    void door(int u, int y, int w, Block door, Direction facing, boolean open) {
        BlockState lower = door.defaultBlockState().setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.OPEN, open);
        set(u, y, w, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(u, y + 1, w, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    void tallPlant(int u, int y, int w, Block plant) {
        set(u, y, w, plant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        set(u, y + 1, w, plant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** Wall sign facing {@code facing} (local), up to four lines. */
    void wallSign(int u, int y, int w, Direction facing, String... lines) {
        painter.sign(worldX(u, w), oy + y, worldZ(u, w),
                Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, dir(facing)), lines);
    }

    // ------------------------------------------------------------------ state helpers

    static BlockState stairs(Block block, Direction facing, boolean top) {
        return block.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
    }

    static BlockState slab(Block block, boolean top) {
        return block.defaultBlockState().setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }

    static BlockState log(Block block, Direction.Axis axis) {
        return block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    static BlockState leaves(Block block) {
        return block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    static BlockState lantern(Block lantern, boolean hanging) {
        return lantern.defaultBlockState().setValue(LanternBlock.HANGING, hanging);
    }

    static BlockState ladder(Direction facing) {
        return Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing);
    }

    static BlockState trapdoor(Block block, Direction facing, boolean top, boolean open) {
        return block.defaultBlockState().setValue(TrapDoorBlock.FACING, facing)
                .setValue(TrapDoorBlock.HALF, top ? Half.TOP : Half.BOTTOM).setValue(TrapDoorBlock.OPEN, open);
    }
}
