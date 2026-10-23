package com.zoneraids.world;

import com.zoneraids.registry.ModRegistry;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Builds the hub: a closed bunker ("Last Frontier" stalker camp) with raid terminals, stash
 * lockers, a bar, bunks, a medic corner and an armoury with the trader's counter. Players spawn facing the terminals.
 */
final class HubBuilder {
    static final int Y = 64;
    static final double SPAWN_X = 0.5;
    static final double SPAWN_Y = Y + 1.1;
    static final double SPAWN_Z = 9.5;
    static final float SPAWN_YAW = 180.0F;

    private static final Palette FLOOR = Palette.of(Blocks.POLISHED_ANDESITE, 6, Blocks.GRAY_CONCRETE, 2, Blocks.STONE_BRICKS, 2,
            Blocks.CRACKED_STONE_BRICKS, 1);
    private static final Palette WALL = Palette.of(Blocks.STONE_BRICKS, 6, Blocks.CRACKED_STONE_BRICKS, 2, Blocks.MOSSY_STONE_BRICKS, 1);
    private static final Palette LOW_WALL = Palette.of(Blocks.DEEPSLATE_TILES, 5, Blocks.CRACKED_DEEPSLATE_TILES, 1, Blocks.POLISHED_DEEPSLATE, 1);

    private HubBuilder() {
    }

    static List<Consumer<Painter>> steps() {
        return List.of(HubBuilder::shell, HubBuilder::terminals, HubBuilder::stash, HubBuilder::bar, HubBuilder::corners,
                HubBuilder::trader, HubBuilder::notices, HubBuilder::lights);
    }

    /** The trader's counter alone, for hubs built before it existed. */
    static List<Consumer<Painter>> traderSteps() {
        return List.of(HubBuilder::trader);
    }

    private static Canvas canvas(Painter p) {
        return new Canvas(p, new Random(CordonTerrain.SEED + 99), 0, Y, 0, Rotation.NONE);
    }

    private static void shell(Painter p) {
        Canvas c = canvas(p);
        c.fill(-18, -1, -14, 18, 9, 14, Blocks.STONE);
        c.fill(-16, 0, -12, 16, 0, 12, FLOOR);
        c.air(-16, 1, -12, 16, 7, 12);
        for (int y = 1; y <= 7; y++) {
            for (int u = -17; u <= 17; u++) {
                for (int w = -13; w <= 13; w++) {
                    if (u == -17 || u == 17 || w == -13 || w == 13) {
                        c.set(u, y, w, y <= 2 ? LOW_WALL : WALL);
                    }
                }
            }
        }
        c.fill(-17, 8, -13, 17, 8, 13, LOW_WALL);
        for (int u = -8; u <= 8; u += 8) {
            c.fill(u, 1, -12, u, 7, -12, Blocks.POLISHED_DEEPSLATE);
            c.fill(u, 1, 12, u, 7, 12, Blocks.POLISHED_DEEPSLATE);
        }
        for (int w = -6; w <= 6; w += 6) {
            c.fill(-16, 1, w, -16, 7, w, Blocks.POLISHED_DEEPSLATE);
            c.fill(16, 1, w, 16, 7, w, Blocks.POLISHED_DEEPSLATE);
        }
        // A worn rug from the spawn to the terminals.
        c.fill(-1, 0, 4, 1, 0, 11, Blocks.GRAY_CONCRETE);
        c.fill(-1, 1, 4, 1, 1, 11, Blocks.GRAY_CARPET);
        c.fill(-1, 0, -8, 1, 0, -5, Blocks.GRAY_CONCRETE);
        c.fill(-1, 1, -8, 1, 1, -5, Blocks.GRAY_CARPET);
    }

    private static void terminals(Painter p) {
        Canvas c = canvas(p);
        c.fill(-6, 0, -12, 6, 0, -9, Blocks.POLISHED_DEEPSLATE);
        for (int u = -3; u <= 3; u += 2) {
            c.set(u, 1, -11, ModRegistry.RAID_TERMINAL.get());
            c.set(u, 2, -11, Canvas.lantern(Blocks.SOUL_LANTERN, false));
        }
        c.wallSign(-1, 4, -12, Direction.SOUTH, "ТЕРМИНАЛ", "РЕЙДОВ", "ПКМ или", "клавиша K");
        c.wallSign(1, 4, -12, Direction.SOUTH, "Выбери карту,", "встань в", "очередь и жди", "отсчёта");
    }

    private static void stash(Painter p) {
        Canvas c = canvas(p);
        for (int w = -9; w <= 9; w += 3) {
            if (w == -6 || w == 0 || w == 6) {
                continue;
            }
            c.set(15, 1, w, ModRegistry.STASH.get());
            c.set(15, 2, w, Blocks.IRON_BARS);
        }
        c.wallSign(16, 4, -3, Direction.WEST, "СХРОН", "личный, 54 слота", "недоступен", "в рейде");
        c.wallSign(16, 4, 3, Direction.WEST, "Что в схроне —", "то твоё.", "Что на тебе —", "можешь потерять");
    }

    private static void bar(Painter p) {
        Canvas c = canvas(p);
        c.fill(-12, 1, -6, -12, 1, 6, Blocks.DARK_OAK_PLANKS);
        c.fill(-12, 2, -6, -12, 2, 6, Canvas.slab(Blocks.DARK_OAK_SLAB, false));
        c.fill(-12, 1, -7, -16, 1, -7, Blocks.DARK_OAK_PLANKS);
        c.fill(-12, 2, -7, -15, 2, -7, Canvas.slab(Blocks.DARK_OAK_SLAB, false));
        for (int w = -5; w <= 5; w++) {
            if (w == 0) {
                continue;
            }
            c.set(-16, 2, w, Canvas.slab(Blocks.SPRUCE_SLAB, true));
            c.set(-16, 4, w, Canvas.slab(Blocks.SPRUCE_SLAB, true));
            if ((w & 1) == 0) {
                c.set(-16, 3, w, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 2).setValue(CandleBlock.LIT, true));
            } else {
                c.set(-16, 3, w, Blocks.POTTED_DEAD_BUSH);
            }
        }
        for (int w = -4; w <= 4; w += 2) {
            c.set(-10, 1, w, Blocks.DARK_OAK_FENCE);
            c.set(-10, 2, w, Blocks.RED_CARPET);
        }
        c.wallSign(-16, 5, 0, Direction.EAST, "Бар", "«Последний", "рубеж»");
        // Campfire in the middle with log benches.
        c.set(0, 0, 0, Blocks.STONE_BRICKS);
        c.set(0, 1, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        c.fill(-3, 1, -1, -3, 1, 1, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.Z));
        c.fill(3, 1, -1, 3, 1, 1, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.Z));
        c.fill(-1, 1, -3, 1, 1, -3, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.X));
    }

    private static void corners(Painter p) {
        Canvas c = canvas(p);
        // Bunks (north-west).
        for (int u = -15; u <= -12; u += 3) {
            for (int y : new int[]{1, 3}) {
                c.fill(u, y, -11, u + 1, y, -9, Canvas.slab(Blocks.SPRUCE_SLAB, false));
                c.set(u, y, -11, Blocks.WHITE_CARPET);
                c.fill(u + 1, y, -10, u + 1, y, -9, Blocks.GREEN_CARPET);
            }
            c.fill(u + 2, 1, -11, u + 2, 3, -11, Blocks.SPRUCE_FENCE);
        }
        // Medic corner (south-west).
        c.fill(-16, 0, 7, -11, 0, 12, Blocks.WHITE_CONCRETE);
        c.set(-15, 1, 11, Blocks.CAULDRON);
        c.fill(-14, 1, 11, -12, 1, 11, Blocks.WHITE_WOOL);
        c.fill(-14, 2, 11, -12, 2, 11, Blocks.WHITE_CARPET);
        c.wallSign(-13, 3, 12, Direction.NORTH, "Медпункт");
        // Armoury (south-east).
        c.fill(10, 0, 7, 16, 0, 12, Blocks.SMOOTH_STONE);
        c.set(15, 1, 11, Blocks.SMITHING_TABLE);
        c.set(14, 1, 11, Blocks.ANVIL);
        c.set(13, 1, 11, Blocks.GRINDSTONE);
        c.fill(11, 1, 12, 12, 3, 12, Blocks.IRON_BARS);
        c.wallSign(13, 3, 12, Direction.NORTH, "Оружейка");
        // Sealed entrance.
        c.door(-1, 1, 13, Blocks.IRON_DOOR, Direction.NORTH, false);
        c.door(0, 1, 13, Blocks.IRON_DOOR, Direction.NORTH, false);
    }

    /** Trader's counter across the armoury (south-east), facing the room. */
    private static void trader(Painter p) {
        Canvas c = canvas(p);
        c.fill(11, 1, 9, 13, 1, 9, Blocks.DARK_OAK_PLANKS);
        c.set(12, 1, 9, ModRegistry.TRADER.get());
        c.set(11, 2, 9, Canvas.slab(Blocks.DARK_OAK_SLAB, false));
        c.set(13, 2, 9, Canvas.lantern(Blocks.LANTERN, false));
        c.wallSign(11, 4, 12, Direction.NORTH, "ТОРГОВЕЦ", "ПКМ по", "прилавку", "или K");
        c.wallSign(12, 4, 12, Direction.NORTH, "Скупаю", "артефакты.", "Продаю стволы,", "броню, аптечки");
    }

    private static void notices(Painter p) {
        Canvas c = canvas(p);
        c.wallSign(-6, 3, 12, Direction.NORTH, "Как играть:", "K или /raid —", "терминал", "рейдов");
        c.wallSign(-5, 3, 12, Direction.NORTH, "В рейде найди", "свой выход", "и стой в зоне", "8 секунд");
        c.wallSign(-4, 3, 12, Direction.NORTH, "Убит или не", "успел выйти —", "снаряжение", "потеряно");
        c.wallSign(4, 3, 12, Direction.NORTH, "Группа:", "/raid party", "invite <ник>", "");
        c.wallSign(5, 3, 12, Direction.NORTH, "Выходы:", "/raid extracts", "", "");
        c.wallSign(6, 3, 12, Direction.NORTH, "Статистика:", "/raid stats", "", "");
    }

    private static void lights(Painter p) {
        Canvas c = canvas(p);
        for (int u = -12; u <= 12; u += 6) {
            for (int w = -8; w <= 8; w += 8) {
                c.set(u, 7, w, Blocks.CHAIN);
                c.set(u, 6, w, Canvas.lantern(Blocks.LANTERN, true));
            }
        }
    }
}
