package com.zoneraids.survival;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts down a temporary camp: a campfire, a ring of seats round it with a bedroll behind each, a
 * small A-frame tent to the north and a lantern on a post. Unlike the places nothing is flattened or
 * cleared: blocks only go where the ground has room (air, grass, flowers), and every one is listed
 * so the camp can be packed away again without a trace.
 */
final class CampBuilder extends SiteBuilder {
    /** Half size of the camp's footprint. */
    static final int HALF = 5;
    private static final double SEAT_RADIUS = 2.2;
    /** Riders sit this far below a marker armor stand's feet, so they look seated on the ground. */
    private static final double SEAT_DROP = 0.5;

    /** Where a member sits: feet position of the seat and the yaw facing the fire. */
    record Seat(double x, double y, double z, float yaw) {
    }

    record Result(List<CampData.Placed> blocks, List<Seat> seats) {
    }

    private final List<CampData.Placed> placed = new ArrayList<>();
    private final Set<Long> used = new HashSet<>();
    /** Ground heights taken before anything is placed, so a block never lands on another camp block. */
    private final int[][] ground = new int[HALF * 2 + 1][HALF * 2 + 1];
    private final Block cloth;
    private final Block carpet;

    private CampBuilder(ServerLevel level, BlockPos origin, long seed, String kind) {
        super(level, origin, seed);
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                ground[x + HALF][z + HALF] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX() + x, origin.getZ() + z);
            }
        }
        switch (kind) {
            case "bandit" -> {
                cloth = pick(Blocks.BLACK_WOOL, Blocks.GRAY_WOOL);
                carpet = Blocks.GRAY_CARPET;
            }
            case "military" -> {
                cloth = Blocks.GREEN_WOOL;
                carpet = Blocks.GREEN_CARPET;
            }
            default -> {
                cloth = pick(Blocks.BROWN_WOOL, Blocks.GREEN_WOOL);
                carpet = pick(Blocks.BROWN_CARPET, Blocks.GREEN_CARPET, Blocks.LIGHT_GRAY_CARPET);
            }
        }
    }

    /** True if a camp fits here: dry ground within a block of {@code origin} everywhere, the tent's footprint level. */
    static boolean fits(ServerLevel level, BlockPos origin) {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX() + x, origin.getZ() + z);
                boolean tent = Math.abs(x) <= 2 && z >= -5 && z <= -3;
                if (Math.abs(y - origin.getY()) > (tent ? 0 : 1)) {
                    return false;
                }
                BlockPos below = new BlockPos(origin.getX() + x, y - 1, origin.getZ() + z);
                if (!level.getFluidState(below).isEmpty() || !level.getBlockState(below).isSolidRender(level, below)) {
                    return false;
                }
            }
        }
        return true;
    }

    static Result build(ServerLevel level, BlockPos origin, String kind, int seats, long seed) {
        CampBuilder b = new CampBuilder(level, origin, seed, kind);
        b.put(0, 0, 0, Blocks.CAMPFIRE.defaultBlockState());
        b.tent();
        b.put(3, 0, -4, Blocks.SPRUCE_FENCE.defaultBlockState());
        b.put(3, 1, -4, Blocks.LANTERN.defaultBlockState());
        List<Seat> list = b.seats(seats);
        return new Result(List.copyOf(b.placed), list);
    }

    /** A tent opening south towards the fire: walls rising to a ridge, closed at the back, bedrolls inside. */
    private void tent() {
        for (int z = -5; z <= -3; z++) {
            put(-2, 0, z, cloth.defaultBlockState());
            put(2, 0, z, cloth.defaultBlockState());
            put(-1, 1, z, cloth.defaultBlockState());
            put(1, 1, z, cloth.defaultBlockState());
            put(0, 2, z, cloth.defaultBlockState());
        }
        for (int x = -1; x <= 1; x++) {
            put(x, 0, -5, cloth.defaultBlockState());
        }
        put(0, 1, -5, cloth.defaultBlockState());
        put(-1, 0, -4, carpet.defaultBlockState());
        put(1, 0, -4, carpet.defaultBlockState());
        put(0, 0, -3, carpet.defaultBlockState());
    }

    /**
     * Seats round the fire, spread over the south, east and west (the tent is north), each with a
     * two-block bedroll lying out behind it.
     */
    private List<Seat> seats(int count) {
        List<Seat> seats = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = Math.toRadians(-30 + 240.0 * (i + 0.5) / count);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double sx = cos * SEAT_RADIUS;
            double sz = sin * SEAT_RADIUS;
            int column = surface((int) Math.round(sx), (int) Math.round(sz));
            // Facing the fire: Minecraft's yaw for the direction (-cos, -sin).
            float yaw = (float) Math.toDegrees(Math.atan2(cos, -sin));
            seats.add(new Seat(origin.getX() + 0.5 + sx, column - SEAT_DROP, origin.getZ() + 0.5 + sz, yaw));
            for (double r = 3.4; r <= 4.4; r += 1.0) {
                put((int) Math.round(cos * r), 0, (int) Math.round(sin * r), carpet.defaultBlockState());
            }
        }
        return seats;
    }

    /** The first free y above the ground in a column of the camp (absolute), as it was before the camp. */
    private int surface(int x, int z) {
        return ground[x + HALF][z + HALF];
    }

    /** Places a block {@code y} above the ground of its column, only where nothing solid stands, and remembers it. */
    private void put(int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(origin.getX() + x, surface(x, z) + y, origin.getZ() + z);
        if (!used.add(pos.asLong()) || !level.getBlockState(pos).canBeReplaced()) {
            return;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        placed.add(new CampData.Placed(pos, state.getBlock()));
    }
}
