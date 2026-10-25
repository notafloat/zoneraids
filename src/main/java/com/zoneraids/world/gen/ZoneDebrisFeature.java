package com.zoneraids.world.gen;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * What is left of the Soviet countryside: a rusted car, a concrete bus stop, a broken wall, a sewer
 * pipe, a line of power poles, a heap of rusty barrels or a stretch of fence. Vanilla blocks only.
 */
public class ZoneDebrisFeature extends Feature<NoneFeatureConfiguration> {
    private static final Block[] CAR_PAINT = {Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.GREEN_TERRACOTTA,
            Blocks.CYAN_TERRACOTTA, Blocks.BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA};

    public ZoneDebrisFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!Prop.firmGround(context.level(), context.origin())) {
            return false;
        }
        Prop p = new Prop(context.level(), context.origin(), context.random());
        return switch (p.random.nextInt(9)) {
            case 0, 1 -> car(p);
            case 2 -> busStop(p);
            case 3, 4 -> wall(p);
            case 5 -> pipe(p);
            case 6 -> poles(p);
            case 7 -> barrels(p);
            default -> fence(p);
        };
    }

    /** A 2 x 4 car body in faded paint and rust, glass of the cabin mostly broken, sometimes sunk into the ground. */
    private static boolean car(Prop p) {
        if (!p.flat(-2, 1, 0, 1, 1)) {
            return false;
        }
        Block paint = p.pick(CAR_PAINT);
        int y = p.chance(0.35F) ? -1 : 0;
        for (int a = -2; a <= 1; a++) {
            for (int b = 0; b <= 1; b++) {
                Block body = p.chance(0.3F) ? p.pick(Blocks.BROWN_TERRACOTTA, Blocks.ORANGE_TERRACOTTA) : paint;
                p.force(a, y, b, body.defaultBlockState());
                p.foundation(a, y, b);
                if (a == -1 || a == 0) {
                    if (p.chance(0.55F)) {
                        p.put(a, y + 1, b, Blocks.TINTED_GLASS);
                    }
                } else if (p.chance(0.2F)) {
                    p.put(a, y + 1, b, Blocks.MOSS_CARPET);
                }
            }
        }
        if (p.chance(0.5F)) {
            // A wheel that came off.
            int a = p.chance(0.5F) ? -3 : 2;
            int g = p.ground(a, 1);
            if (Math.abs(g) <= 1) {
                p.put(a, g, 1, Blocks.BLACK_CONCRETE);
            }
        }
        return true;
    }

    /** Three concrete walls under a slab roof, a bench inside, holes in the walls. */
    private static boolean busStop(Prop p) {
        if (!p.flat(-2, 2, -1, 1, 1)) {
            return false;
        }
        for (int a = -2; a <= 2; a++) {
            for (int b = -1; b <= 1; b++) {
                p.force(a, -1, b, (b >= 0 ? Blocks.POLISHED_ANDESITE : Blocks.GRAVEL).defaultBlockState());
                p.foundation(a, -1, b);
                for (int y = 0; y <= 2; y++) {
                    boolean wall = b == 1 || ((a == -2 || a == 2) && b == 0);
                    if (wall && !(y > 0 && p.chance(0.15F))) {
                        p.force(a, y, b, p.concrete().defaultBlockState());
                    } else if (!wall) {
                        p.force(a, y, b, Blocks.AIR.defaultBlockState());
                    }
                }
                if (!p.chance(0.2F)) {
                    p.force(a, 3, b, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                }
            }
        }
        for (int a = -1; a <= 1; a++) {
            p.put(a, 0, 0, Blocks.SPRUCE_SLAB);
        }
        return true;
    }

    /** A broken concrete wall, now and then with a corner, rebar sticking out of the top and rubble around. */
    private static boolean wall(Prop p) {
        int length = p.between(4, 8);
        int start = -length / 2;
        int height = p.between(1, 3);
        for (int a = start; a < start + length; a++) {
            int g = p.ground(a, 0);
            if (Math.abs(g) > 2) {
                continue;
            }
            height = Math.max(0, Math.min(3, height + p.between(-1, 1)));
            for (int y = 0; y < height; y++) {
                p.put(a, g + y, 0, p.concrete());
            }
            if (height > 0 && p.chance(0.25F)) {
                p.put(a, g + height, 0, p.bars(false));
            }
            for (int b = -1; b <= 1; b += 2) {
                if (p.chance(0.3F)) {
                    int gb = p.ground(a, b);
                    if (Math.abs(gb - g) <= 1) {
                        p.put(a, gb, b, p.pick(Blocks.COBBLESTONE_SLAB, Blocks.ANDESITE_SLAB, Blocks.COBBLESTONE));
                    }
                }
            }
        }
        if (p.chance(0.4F)) {
            int g0 = p.ground(start, 1);
            for (int b = 1; b <= p.between(2, 4); b++) {
                int g = p.ground(start, b);
                if (Math.abs(g - g0) > 1) {
                    break;
                }
                for (int y = 0; y < p.between(1, 3); y++) {
                    p.put(start, g + y, b, p.concrete());
                }
            }
        }
        return true;
    }

    /** A 3 x 3 concrete sewer pipe lying in the grass, half sunk, cobwebs inside. */
    private static boolean pipe(Prop p) {
        if (!p.flat(-2, 1, -1, 1, 1)) {
            return false;
        }
        int sunk = p.chance(0.5F) ? -1 : 0;
        for (int a = -2; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                for (int y = 0; y <= 2; y++) {
                    if (b == 0 && y == 1) {
                        if (p.chance(0.3F)) {
                            p.force(a, sunk + y, b, Blocks.COBWEB.defaultBlockState());
                        } else {
                            p.force(a, sunk + y, b, Blocks.AIR.defaultBlockState());
                        }
                    } else if (!p.chance(0.08F)) {
                        Block block = p.chance(0.75F) ? Blocks.LIGHT_GRAY_CONCRETE : p.pick(Blocks.MOSSY_COBBLESTONE, Blocks.STONE);
                        p.force(a, sunk + y, b, block.defaultBlockState());
                    }
                }
                p.foundation(a, sunk, b);
            }
        }
        return true;
    }

    /** Two or three power poles along a line, some fallen; the standing ones have a cross-arm and a torn wire. */
    private static boolean poles(Prop p) {
        int count = p.between(2, 3);
        for (int i = 0; i < count; i++) {
            int a = -7 + i * 7;
            int g = p.ground(a, 0);
            if (Math.abs(g) > 4 || !p.free(a, g, 0)) {
                continue;
            }
            if (p.chance(0.25F)) {
                for (int d = 0; d < 6; d++) {
                    int gd = p.ground(a, d - 3);
                    if (Math.abs(gd - g) > 1) {
                        break;
                    }
                    p.put(a, gd, d - 3, p.log(Blocks.STRIPPED_SPRUCE_LOG, false));
                }
                continue;
            }
            int height = p.between(6, 8);
            Block pole = p.chance(0.6F) ? Blocks.ANDESITE_WALL : Blocks.STRIPPED_SPRUCE_LOG;
            for (int y = 0; y < height; y++) {
                p.put(a, g + y, 0, pole);
            }
            p.put(a, g + height - 1, -1, p.bars(false));
            p.put(a, g + height - 1, 1, p.bars(false));
            if (p.chance(0.4F)) {
                int side = p.chance(0.5F) ? -1 : 1;
                for (int y = height - 2; y >= height - 4; y--) {
                    p.put(a, g + y, side, Blocks.CHAIN);
                }
            }
        }
        return true;
    }

    /** A heap of rusty barrels (cauldrons, some full of rainwater), one now and then on top of another. */
    private static boolean barrels(Prop p) {
        int count = p.between(2, 5);
        for (int i = 0; i < count; i++) {
            int a = p.between(-2, 2);
            int b = p.between(-2, 2);
            int g = p.ground(a, b);
            if (Math.abs(g) > 2 || !p.free(a, g, b)) {
                continue;
            }
            p.force(a, g - 1, b, Blocks.COARSE_DIRT.defaultBlockState());
            p.put(a, g, b, barrel(p));
            if (p.chance(0.2F)) {
                p.put(a, g + 1, b, barrel(p));
            }
        }
        return true;
    }

    private static BlockState barrel(Prop p) {
        if (p.chance(0.25F)) {
            return Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, p.between(1, 3));
        }
        return (p.chance(0.15F) ? Blocks.BARREL : Blocks.CAULDRON).defaultBlockState();
    }

    /** A stretch of rusty fence between concrete posts, with gaps where it was torn down. */
    private static boolean fence(Prop p) {
        int length = p.between(6, 12);
        int start = -length / 2;
        for (int a = start; a < start + length; a++) {
            int g = p.ground(a, 0);
            if (Math.abs(g) > 3) {
                continue;
            }
            boolean post = (a - start) % 4 == 0;
            if (post) {
                p.put(a, g, 0, Blocks.ANDESITE_WALL);
                p.put(a, g + 1, 0, Blocks.ANDESITE_WALL);
            } else if (!p.chance(0.25F)) {
                p.put(a, g, 0, p.bars(true));
                if (!p.chance(0.3F)) {
                    p.put(a, g + 1, 0, p.bars(true));
                }
            }
        }
        return true;
    }
}
