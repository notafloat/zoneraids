package com.zoneraids.survival;

import com.zoneraids.ZoneRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Builds the survival lairs block by block: abandoned Zone places in the style of the Cordon,
 * from vanilla blocks only. Each build flattens its footprint first. Coordinates in the helpers
 * are relative to the lair's centre on the ground ({@code y = 0} is the first block above it).
 */
final class LairBuilder {
    /** Where the boss and its guards stand, and how close a player must come to wake them. */
    record Site(BlockPos boss, BlockPos guards, int wakeRadius) {
    }

    private final ServerLevel level;
    private final BlockPos origin;
    private final RandomSource random;

    private LairBuilder(ServerLevel level, BlockPos origin, long seed) {
        this.level = level;
        this.origin = origin;
        this.random = RandomSource.create(seed);
    }

    /** Ground level for a lair centred at x/z: the surface there, or null over water and lava. */
    static BlockPos ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getFluidState(pos.below()).isEmpty() || y <= level.getMinBuildHeight() + 20) {
            return null;
        }
        return pos;
    }

    static Site build(ServerLevel level, Lairs.Type type, BlockPos origin, long seed) {
        LairBuilder b = new LairBuilder(level, origin, seed);
        return switch (type) {
            case BANDIT_BASE -> b.banditBase();
            case AGROPROM -> b.agroprom();
            case LAB -> b.lab();
        };
    }

    // ------------------------------------------------------------------ lairs

    /** A walled bandit camp: patched wall, a plank shack with the stash, a campfire. */
    private Site banditBase() {
        flatten(13, Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.DIRT_PATH);
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                boolean edge = Math.abs(x) == 12 || Math.abs(z) == 12;
                boolean gate = z == -12 && Math.abs(x) <= 1;
                if (!edge || gate) {
                    continue;
                }
                for (int y = 0; y < 3; y++) {
                    if (random.nextFloat() < 0.12F) {
                        continue;
                    }
                    set(x, y, z, pick(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS));
                }
            }
        }
        // Watch posts in two corners.
        for (int[] c : new int[][]{{-10, -10}, {10, -10}}) {
            for (int y = 0; y < 4; y++) {
                set(c[0], y, c[1], Blocks.SPRUCE_LOG);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    set(c[0] + dx, 4, c[1] + dz, Blocks.SPRUCE_SLAB);
                }
            }
        }
        // The shack.
        for (int x = -3; x <= 3; x++) {
            for (int z = 2; z <= 7; z++) {
                boolean wall = Math.abs(x) == 3 || z == 2 || z == 7;
                for (int y = 0; y < 3; y++) {
                    boolean door = z == 2 && x == 0 && y < 2;
                    boolean window = Math.abs(x) == 3 && y == 1 && (z == 4 || z == 5);
                    if (!wall || door) {
                        set(x, y, z, Blocks.AIR);
                    } else if (window) {
                        set(x, y, z, Blocks.IRON_BARS);
                    } else {
                        set(x, y, z, (Math.abs(x) == 3 && (z == 2 || z == 7)) ? Blocks.SPRUCE_LOG : pick(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS));
                    }
                }
            }
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = 1; z <= 8; z++) {
                set(x, 3, z, random.nextFloat() < 0.08F ? Blocks.AIR : Blocks.SPRUCE_SLAB);
            }
        }
        chest(2, 0, 6, "crates/weapons");
        chest(-2, 0, 6, "crates/military");
        set(2, 0, 3, Blocks.BARREL);
        set(-2, 0, 3, Blocks.CRAFTING_TABLE);
        set(-6, 0, -4, Blocks.CAMPFIRE);
        set(-6, 0, -6, Blocks.SPRUCE_LOG);
        set(-4, 0, -4, Blocks.SPRUCE_LOG);
        for (int i = 0; i < 6; i++) {
            set(5 + random.nextInt(5), 0, -8 + random.nextInt(6), pick(Blocks.BARREL, Blocks.HAY_BLOCK, Blocks.COMPOSTER));
        }
        return new Site(at(0, 0, -2), at(0, 0, -6), 22);
    }

    /** A ruined collective farm: broken concrete fence, a roofless brick barn, a silo, bones everywhere. */
    private Site agroprom() {
        flatten(16, Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.ANDESITE, Blocks.STONE);
        for (int x = -15; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) {
                boolean edge = Math.abs(x) == 15 || Math.abs(z) == 15;
                if (!edge || (z == 15 && Math.abs(x) <= 2) || random.nextFloat() < 0.2F) {
                    continue;
                }
                int height = 1 + random.nextInt(4);
                for (int y = 0; y < height; y++) {
                    set(x, y, z, pick(Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.CRACKED_STONE_BRICKS, Blocks.ANDESITE));
                }
            }
        }
        // The barn, open to the sky.
        for (int x = -8; x <= 8; x++) {
            for (int z = -11; z <= -2; z++) {
                boolean wall = Math.abs(x) == 8 || z == -11 || z == -2;
                if (!wall || (z == -2 && Math.abs(x) <= 1)) {
                    continue;
                }
                int height = 2 + random.nextInt(4);
                for (int y = 0; y < height; y++) {
                    boolean window = y == 2 && (x % 3 == 0 || z % 3 == 0);
                    set(x, y, z, window ? Blocks.IRON_BARS : pick(Blocks.BRICKS, Blocks.BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MUD_BRICKS));
                }
            }
        }
        for (int i = 0; i < 7; i++) {
            set(-6 + random.nextInt(13), 0, -10 + random.nextInt(7), Blocks.HAY_BLOCK);
        }
        chest(6, 0, -9, "crates/military");
        // A cracked silo.
        for (int y = 0; y < 10; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    int d = x * x + z * z;
                    if (d >= 3 && d <= 5 && random.nextFloat() > 0.1F + y * 0.03F) {
                        set(10 + x, y, 9 + z, pick(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.LIGHT_GRAY_CONCRETE));
                    }
                }
            }
        }
        for (int i = 0; i < 12; i++) {
            set(-13 + random.nextInt(27), 0, 0 + random.nextInt(14), pick(Blocks.BONE_BLOCK, Blocks.BONE_BLOCK, Blocks.COBWEB));
        }
        return new Site(at(0, 0, 6), at(-6, 0, 10), 24);
    }

    /** Laboratory X: a concrete hut over a ladder shaft into a dark underground lab. */
    private Site lab() {
        flatten(5, Blocks.GRAVEL, Blocks.COARSE_DIRT);
        int floor = -14;
        // The lab room: x -8..8, z -12..0, floor at y = floor, ceiling at floor + 6.
        for (int x = -9; x <= 9; x++) {
            for (int z = -13; z <= 1; z++) {
                for (int y = floor; y <= floor + 6; y++) {
                    boolean shell = Math.abs(x) == 9 || z == -13 || z == 1 || y == floor || y == floor + 6;
                    if (!shell) {
                        set(x, y, z, Blocks.AIR);
                    } else if (y == floor) {
                        set(x, y, z, pick(Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.CRACKED_STONE_BRICKS));
                    } else {
                        set(x, y, z, pick(Blocks.LIGHT_GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.CRACKED_STONE_BRICKS));
                    }
                }
            }
        }
        // Cells along both long walls.
        for (int side : new int[]{-1, 1}) {
            for (int z = -12; z <= -2; z++) {
                if (z % 4 == 0) {
                    continue;
                }
                for (int y = floor + 1; y <= floor + 3; y++) {
                    set(side * 5, y, z, Blocks.IRON_BARS);
                }
            }
        }
        for (int x = -3; x <= 3; x += 3) {
            set(x, floor + 1, -6, Blocks.SMOOTH_STONE_SLAB);
            set(x, floor + 5, -6, Blocks.SOUL_LANTERN);
        }
        set(-1, floor + 1, -4, Blocks.BREWING_STAND);
        for (int i = 0; i < 10; i++) {
            set(-8 + random.nextInt(17), floor + 5, -12 + random.nextInt(12), Blocks.COBWEB);
            set(-8 + random.nextInt(17), floor + 6, -12 + random.nextInt(12), Blocks.REDSTONE_LAMP);
        }
        chest(-7, floor + 1, -11, "crates/military");
        chest(7, floor + 1, -11, "crates/medical");
        // The shaft at 0/0 with its ladder on the south side, from the hut floor down to the lab.
        for (int y = floor + 1; y <= 1; y++) {
            set(0, y, 0, Blocks.AIR);
            if (y < -1) {
                set(-1, y, 0, Blocks.GRAY_CONCRETE);
                set(1, y, 0, Blocks.GRAY_CONCRETE);
                set(0, y, -1, y <= floor + 3 ? Blocks.AIR : Blocks.GRAY_CONCRETE);
            }
            if (y > floor + 5 || y >= -1) {
                set(0, y, 1, Blocks.GRAY_CONCRETE);
            }
            setState(0, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        }
        // The hut: concrete box with a door on the north side.
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                boolean wall = Math.abs(x) == 3 || Math.abs(z) == 3;
                for (int y = 0; y <= 3; y++) {
                    boolean door = z == -3 && x == 0 && y < 2;
                    if (y == 3) {
                        set(x, y, z, Blocks.GRAY_CONCRETE);
                    } else if (wall && !door) {
                        set(x, y, z, pick(Blocks.GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.CRACKED_STONE_BRICKS));
                    } else if (!(x == 0 && z == 0) && !(x == 0 && z == 1)) {
                        set(x, y, z, Blocks.AIR);
                    }
                }
            }
        }
        set(0, 0, 1, Blocks.GRAY_CONCRETE);
        set(0, 1, 1, Blocks.GRAY_CONCRETE);
        return new Site(at(0, floor + 1, -9), at(0, floor + 1, -4), 18);
    }

    // ------------------------------------------------------------------ helpers

    /** Levels a square of the given half size: clears what stands above the ground, fills dips, lays the floor mix. */
    private void flatten(int half, Block... floor) {
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

    private void chest(int x, int y, int z, String table) {
        set(x, y, z, Blocks.CHEST);
        if (level.getBlockEntity(at(x, y, z)) instanceof RandomizableContainerBlockEntity container) {
            container.setLootTable(new ResourceLocation(ZoneRaids.MODID, table), random.nextLong());
        }
    }

    private Block pick(Block... blocks) {
        return blocks[random.nextInt(blocks.length)];
    }

    private BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    private void set(int x, int y, int z, Block block) {
        setState(x, y, z, block.defaultBlockState());
    }

    private void setState(int x, int y, int z, BlockState state) {
        level.setBlock(at(x, y, z), state, Block.UPDATE_CLIENTS);
    }
}
