package com.zoneraids.survival;

import com.zoneraids.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds the survival places block by block, in the style of the lairs: a stalker camp with a
 * trader, a military base and two kinds of ruins. Each build flattens its footprint first (see
 * {@link SiteBuilder}) and joins up its fences, walls and bars at the end.
 */
final class PlaceBuilder extends SiteBuilder {
    /** Where the crew stands, where the trader stands (friendly places only) and how close a player must come to wake the crew. */
    record Site(BlockPos crew, BlockPos trader, int wakeRadius) {
    }

    private PlaceBuilder(ServerLevel level, BlockPos origin, long seed) {
        super(level, origin, seed);
    }

    static Site build(ServerLevel level, Places.Type type, BlockPos origin, long seed) {
        PlaceBuilder b = new PlaceBuilder(level, origin, seed);
        Site site = switch (type) {
            case STALKER_CAMP -> b.stalkerCamp();
            case MILITARY_BASE -> b.militaryBase();
            case RUINED_VILLAGE -> b.ruinedVillage();
            case RUINED_BASE -> b.ruinedBase();
        };
        b.connect(type.half);
        return site;
    }

    // ------------------------------------------------------------------ places

    /** A fenced stalker camp: a stone bunker with the trader behind his counter, tents round a campfire, a watchtower. */
    private Site stalkerCamp() {
        flatten(14, Blocks.COARSE_DIRT, Blocks.DIRT_PATH, Blocks.GRAVEL, Blocks.PODZOL);
        for (int x = -13; x <= 13; x++) {
            for (int z = -13; z <= 13; z++) {
                boolean edge = Math.abs(x) == 13 || Math.abs(z) == 13;
                boolean gate = (z == -13 && Math.abs(x) <= 1) || (x == 13 && Math.abs(z) <= 1);
                if (!edge || gate) {
                    continue;
                }
                boolean post = (x + z) % 4 == 0;
                set(x, 0, z, post ? Blocks.SPRUCE_LOG : pick(Blocks.MUD_BRICK_WALL, Blocks.MUD_BRICK_WALL, Blocks.COBBLESTONE_WALL));
                set(x, 1, z, post ? Blocks.SPRUCE_LOG : Blocks.SPRUCE_FENCE);
                if (post) {
                    set(x, 2, z, Blocks.LANTERN);
                }
            }
        }
        // The trader's bunker: walls up to y = 2 and a solid roof at y = 3, door on the north side.
        for (int x = -5; x <= 5; x++) {
            for (int z = 3; z <= 10; z++) {
                boolean wall = Math.abs(x) == 5 || z == 3 || z == 10;
                for (int y = 0; y <= 3; y++) {
                    boolean door = z == 3 && x == 0 && y < 2;
                    boolean window = z == 3 && Math.abs(x) == 3 && y == 1;
                    if (y == 3) {
                        set(x, y, z, (x == 0 && (z == 5 || z == 8)) ? Blocks.GLOWSTONE : pick(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS));
                    } else if (!wall || door) {
                        set(x, y, z, Blocks.AIR);
                    } else if (window) {
                        set(x, y, z, Blocks.IRON_BARS);
                    } else {
                        set(x, y, z, pick(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS));
                    }
                }
            }
        }
        for (int x = -4; x <= 4; x++) {
            set(x, 0, 7, x == 0 ? ModRegistry.TRADER.get() : Blocks.SPRUCE_PLANKS);
        }
        set(-2, 1, 7, Blocks.LANTERN);
        set(3, 1, 7, Blocks.LANTERN);
        for (int x = -4; x <= 4; x += 2) {
            set(x, 0, 9, x == 0 ? Blocks.AIR : Blocks.BARREL);
        }
        set(-4, 0, 4, Blocks.CRAFTING_TABLE);
        set(4, 0, 4, Blocks.BARREL);
        // Tents and the campfire.
        tent(-8, -6, Blocks.GREEN_WOOL);
        tent(8, -6, Blocks.BROWN_WOOL);
        tent(-8, 3, Blocks.GREEN_WOOL);
        set(0, 0, -4, Blocks.CAMPFIRE);
        set(-2, 0, -4, Blocks.SPRUCE_LOG);
        set(2, 0, -4, Blocks.SPRUCE_LOG);
        set(0, 0, -6, Blocks.SPRUCE_LOG);
        // A watchtower over the east gate.
        tower(10, -10, Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE);
        for (int i = 0; i < 5; i++) {
            set(7 + random.nextInt(5), 0, 4 + random.nextInt(7), pick(Blocks.BARREL, Blocks.HAY_BLOCK, Blocks.SPRUCE_PLANKS));
        }
        return new Site(at(0, 0, -1), at(0, 0, 9), 0);
    }

    /** A military base: a concrete wall with bars on top, two towers, barracks and a store, sandbags at the gate. */
    private Site militaryBase() {
        flatten(16, Blocks.GRAVEL, Blocks.ANDESITE, Blocks.STONE, Blocks.COARSE_DIRT);
        for (int x = -15; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) {
                boolean edge = Math.abs(x) == 15 || Math.abs(z) == 15;
                if (!edge || (z == -15 && Math.abs(x) <= 2)) {
                    continue;
                }
                for (int y = 0; y < 3; y++) {
                    set(x, y, z, pick(Blocks.GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.CRACKED_STONE_BRICKS));
                }
                set(x, 3, z, Blocks.IRON_BARS);
            }
        }
        for (int x : new int[]{-3, 3}) {
            for (int y = 0; y < 5; y++) {
                set(x, y, -15, Blocks.POLISHED_ANDESITE);
            }
        }
        tower(-12, -12, Blocks.DARK_OAK_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE);
        tower(12, -12, Blocks.DARK_OAK_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE);
        // Sandbags inside the gate.
        for (int x = -6; x <= 6; x++) {
            if (Math.abs(x) > 1) {
                set(x, 0, -11, Blocks.MUD_BRICK_WALL);
            }
        }
        // Barracks.
        house(-11, -2, 2, 12, Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.GLASS_PANE);
        for (int z = 4; z <= 10; z += 2) {
            set(-10, 0, z, Blocks.WHITE_WOOL);
            set(-9, 0, z, Blocks.WHITE_WOOL);
        }
        chest(-4, 0, 11, "crates/military");
        set(-4, 0, 9, Blocks.BARREL);
        // The store.
        house(3, 12, 2, 10, Blocks.GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.IRON_BARS);
        chest(10, 0, 9, "crates/weapons");
        chest(4, 0, 9, "crates/medical");
        chest(10, 0, 4, "crates/military");
        for (int i = 0; i < 4; i++) {
            set(5 + random.nextInt(4), 0, 4 + random.nextInt(4), Blocks.BARREL);
        }
        // A truck parked by the wall.
        truck(-9, -7, Blocks.GREEN_TERRACOTTA);
        return new Site(at(0, 0, -4), at(0, 0, -4), 30);
    }

    /** A dead village: overgrown houses with fallen roofs and walls, broken fences, a well in the middle. */
    private Site ruinedVillage() {
        flatten(22, Blocks.GRASS_BLOCK, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.DIRT_PATH);
        int[][] houses = {{-13, -11}, {11, -13}, {-14, 9}, {12, 10}, {0, 15}};
        String[] loot = {"crates/common", "crates/medical", "crates/common", null, "crates/common"};
        for (int i = 0; i < houses.length; i++) {
            ruin(houses[i][0], houses[i][1], loot[i]);
        }
        // The well.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    set(0, -1, 0, Blocks.WATER);
                    set(0, -2, 0, Blocks.WATER);
                } else {
                    set(x, 0, z, Blocks.MOSSY_COBBLESTONE);
                }
            }
        }
        set(-1, 1, -1, Blocks.OAK_FENCE);
        set(1, 1, 1, Blocks.OAK_FENCE);
        // Broken fences along the street.
        for (int i = 0; i < 8; i++) {
            int x = -18 + random.nextInt(37);
            int z = -18 + random.nextInt(37);
            boolean alongX = random.nextBoolean();
            int length = 2 + random.nextInt(4);
            for (int n = 0; n < length; n++) {
                int fx = alongX ? x + n : x;
                int fz = alongX ? z : z + n;
                if (Math.abs(fx) <= 21 && Math.abs(fz) <= 21 && level.getBlockState(at(fx, 0, fz)).isAir()) {
                    set(fx, 0, fz, Blocks.OAK_FENCE);
                }
            }
        }
        overgrow(21, 60);
        return new Site(at(0, 0, 4), at(0, 0, 4), 26);
    }

    /** An abandoned base: a crumbling wall, a hangar with half its roof gone, rusted wrecks, bones and webs. */
    private Site ruinedBase() {
        flatten(18, Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.ANDESITE, Blocks.MOSS_BLOCK);
        for (int x = -17; x <= 17; x++) {
            for (int z = -17; z <= 17; z++) {
                boolean edge = Math.abs(x) == 17 || Math.abs(z) == 17;
                if (!edge || (z == -17 && Math.abs(x) <= 2) || random.nextFloat() < 0.25F) {
                    continue;
                }
                int height = 1 + random.nextInt(3);
                for (int y = 0; y < height; y++) {
                    set(x, y, z, pick(Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS));
                }
            }
        }
        // The hangar: tall walls with gaps, a door gap on the south side, roof remnants.
        for (int x = -11; x <= 7; x++) {
            for (int z = -2; z <= 12; z++) {
                boolean wall = x == -11 || x == 7 || z == -2 || z == 12;
                if (wall && !(z == -2 && Math.abs(x + 2) <= 2)) {
                    int height = 2 + random.nextInt(4);
                    for (int y = 0; y < height; y++) {
                        set(x, y, z, pick(Blocks.LIGHT_GRAY_CONCRETE, Blocks.CRACKED_STONE_BRICKS, Blocks.IRON_BLOCK, Blocks.GRAY_CONCRETE));
                    }
                } else if (!wall && random.nextFloat() < 0.35F) {
                    set(x, 5, z, Blocks.SMOOTH_STONE_SLAB);
                }
            }
        }
        // Rubble from the roof.
        for (int i = 0; i < 14; i++) {
            set(-10 + random.nextInt(17), 0, -1 + random.nextInt(13), pick(Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.SMOOTH_STONE_SLAB, Blocks.COBWEB));
        }
        chest(-9, 0, 10, "crates/military");
        chest(5, 0, 10, "crates/weapons");
        chest(-9, 0, 0, "crates/common");
        // The stump of a watchtower and two wrecks.
        for (int y = 0; y < 3; y++) {
            set(13, y, -13, Blocks.STRIPPED_DARK_OAK_LOG);
            set(15, y, -13, Blocks.STRIPPED_DARK_OAK_LOG);
        }
        set(13, 3, -13, Blocks.STRIPPED_DARK_OAK_LOG);
        truck(10, 2, Blocks.TERRACOTTA);
        truck(-14, -10, Blocks.BROWN_TERRACOTTA);
        for (int i = 0; i < 12; i++) {
            set(-15 + random.nextInt(31), 0, -15 + random.nextInt(31), pick(Blocks.BONE_BLOCK, Blocks.COBWEB, Blocks.COBWEB, Blocks.GRAVEL));
        }
        overgrow(16, 30);
        return new Site(at(-2, 0, 5), at(-2, 0, 5), 24);
    }

    // ------------------------------------------------------------------ parts

    /** An A-frame tent of wool running north to south. */
    private void tent(int cx, int cz, Block wool) {
        for (int dz = -2; dz <= 2; dz++) {
            set(cx - 1, 0, cz + dz, wool);
            set(cx + 1, 0, cz + dz, wool);
            set(cx, 1, cz + dz, wool);
        }
        set(cx, 0, cz + 1, Blocks.WHITE_CARPET);
    }

    /** Four log legs, a plank floor at y = 5 and a fence railing. */
    private void tower(int cx, int cz, Block log, Block floor, Block railing) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                for (int y = 0; y < 5; y++) {
                    set(cx + dx, y, cz + dz, log);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(cx + dx, 5, cz + dz, floor);
                if (dx != 0 || dz != 0) {
                    set(cx + dx, 6, cz + dz, railing);
                }
            }
        }
    }

    /** A flat-roofed box from x1..x2, z1..z2 with a door in the middle of the north wall and windows at y = 1. */
    private void house(int x1, int x2, int z1, int z2, Block wallBlock, Block roof, Block window) {
        int doorX = (x1 + x2) / 2;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean wall = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                for (int y = 0; y <= 3; y++) {
                    boolean door = z == z1 && x == doorX && y < 2;
                    boolean hole = y == 1 && !corner && (x - x1) % 3 == 1 && (z - z1) % 3 == 1;
                    if (y == 3) {
                        set(x, y, z, roof);
                    } else if (!wall || door) {
                        set(x, y, z, Blocks.AIR);
                    } else {
                        set(x, y, z, hole ? window : wallBlock);
                    }
                }
            }
        }
        setState(doorX, 2, z1 + 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    /** A wrecked house of the village: a stone footing, plank walls that thin out towards the top, a fallen roof. */
    private void ruin(int cx, int cz, String loot) {
        float decay = 0.15F + random.nextFloat() * 0.3F;
        int doorX = cx + random.nextInt(3) - 1;
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                boolean wall = Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3;
                boolean corner = Math.abs(x - cx) == 3 && Math.abs(z - cz) == 3;
                if (!wall) {
                    set(x, -1, z, pick(Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.COARSE_DIRT));
                    if (random.nextFloat() < 0.3F) {
                        set(x, 3, z, pick(Blocks.SPRUCE_SLAB, Blocks.OAK_SLAB));
                    }
                    continue;
                }
                if (z == cz - 3 && x == doorX) {
                    continue;
                }
                for (int y = 0; y < 3; y++) {
                    if (y > 0 && random.nextFloat() < decay * y) {
                        break;
                    }
                    Block block;
                    if (y == 0) {
                        block = pick(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE);
                    } else if (corner) {
                        block = Blocks.STRIPPED_OAK_LOG;
                    } else if (y == 1 && !corner && (x + z) % 3 == 0) {
                        block = random.nextBoolean() ? Blocks.GLASS_PANE : Blocks.AIR;
                    } else {
                        block = pick(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.MOSSY_COBBLESTONE);
                    }
                    set(x, y, z, block);
                }
            }
        }
        // The chimney, often half fallen.
        int chimney = 2 + random.nextInt(3);
        for (int y = 0; y < chimney; y++) {
            set(cx + 2, y, cz + 2, Blocks.BRICKS);
        }
        for (int i = 0; i < 3; i++) {
            set(cx - 2 + random.nextInt(4), 0, cz - 2 + random.nextInt(4), pick(Blocks.COBWEB, Blocks.OAK_SLAB, Blocks.COBBLESTONE));
        }
        if (loot != null) {
            chest(cx - 2, 0, cz + 2, loot);
        }
    }

    /** A wreck of a lorry: a body on coal wheels, the cab in front. */
    private void truck(int cx, int cz, Block body) {
        for (int dz = 0; dz < 5; dz++) {
            for (int dx = 0; dx < 3; dx++) {
                boolean wheel = (dz == 0 || dz == 4) && dx != 1;
                set(cx + dx, 0, cz + dz, wheel ? Blocks.COAL_BLOCK : body);
                if (dz < 3 || dx == 1) {
                    set(cx + dx, 1, cz + dz, dz == 4 ? Blocks.GLASS_PANE : body);
                }
            }
        }
        set(cx + 1, 2, cz + 4, body);
    }

    /** Grass, ferns and moss carpets on open ground. */
    private void overgrow(int half, int count) {
        for (int i = 0; i < count; i++) {
            int x = random.nextInt(half * 2 + 1) - half;
            int z = random.nextInt(half * 2 + 1) - half;
            BlockState below = level.getBlockState(at(x, -1, z));
            if (level.getBlockState(at(x, 0, z)).isAir() && (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.MOSS_BLOCK) || below.is(Blocks.COARSE_DIRT))) {
                set(x, 0, z, below.is(Blocks.GRASS_BLOCK) ? pick(Blocks.GRASS, Blocks.FERN, Blocks.GRASS) : Blocks.MOSS_CARPET);
            }
        }
    }

    /** Fences, walls, bars and panes are placed unjoined; this joins them to what stands next to them. */
    private void connect(int half) {
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                for (int y = 0; y <= 7; y++) {
                    BlockPos pos = at(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock) {
                        BlockState joined = Block.updateFromNeighbourShapes(state, level, pos);
                        if (joined != state) {
                            level.setBlock(pos, joined, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
        }
    }
}
