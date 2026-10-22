package com.zoneraids.world;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.world.CordonLayout.Site;
import com.zoneraids.world.CordonTerrain.Column;
import com.zoneraids.world.CordonTerrain.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Builds the "Cordon" raid map: an abandoned stretch of the Zone with a village, a farm, an army
 * checkpoint, a gas station, a dormitory, a factory, a railway with a bridge and a tunnel, a radio
 * hill with an underground lab, a swamp, an anomaly field and a perimeter wall.
 * <p>
 * The map only uses vanilla blocks plus the mod's loot crates. Nothing on it ticks except a few
 * campfires, there are no entities and no vanilla containers (loot cannot be hidden between raids).
 */
final class CordonBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CLEAR_TOP = 150;

    private static final Palette STONE = Palette.of(Blocks.STONE, 8, Blocks.ANDESITE, 1, Blocks.TUFF, 1);
    private static final Palette CONCRETE = Palette.of(Blocks.LIGHT_GRAY_CONCRETE, 10, Blocks.GRAY_CONCRETE, 2,
            Blocks.CRACKED_STONE_BRICKS, 2, Blocks.STONE_BRICKS, 1, Blocks.ANDESITE, 1, Blocks.MOSSY_STONE_BRICKS, 1);
    private static final Palette BRICK = Palette.of(Blocks.STONE_BRICKS, 6, Blocks.CRACKED_STONE_BRICKS, 3,
            Blocks.MOSSY_STONE_BRICKS, 2, Blocks.COBBLESTONE, 1);
    private static final Palette RED_BRICK = Palette.of(Blocks.BRICKS, 10, Blocks.MUD_BRICKS, 1, Blocks.COBBLESTONE, 1,
            Blocks.MOSSY_COBBLESTONE, 1);
    private static final Palette RUBBLE = Palette.of(Blocks.COBBLESTONE, 4, Blocks.GRAVEL, 1, Blocks.ANDESITE, 2,
            Blocks.CRACKED_STONE_BRICKS, 2, Blocks.MOSSY_COBBLESTONE, 1);
    private static final Palette DEEPSLATE = Palette.of(Blocks.DEEPSLATE_TILES, 6, Blocks.CRACKED_DEEPSLATE_TILES, 2,
            Blocks.POLISHED_DEEPSLATE, 2, Blocks.DEEPSLATE_BRICKS, 2);
    private static final Palette YARD = Palette.of(Blocks.GRAVEL, 3, Blocks.COARSE_DIRT, 3, Blocks.ANDESITE, 1,
            Blocks.GRAY_CONCRETE, 2, Blocks.PACKED_MUD, 1);
    private static final Palette CORRUGATED = Palette.of(Blocks.CYAN_TERRACOTTA, 4, Blocks.LIGHT_GRAY_TERRACOTTA, 3,
            Blocks.GRAY_TERRACOTTA, 1, Blocks.BROWN_TERRACOTTA, 1);

    private final CordonLayout layout;
    private final CordonTerrain terrain;
    private final List<Block> anomalyBlocks = new ArrayList<>();

    CordonBuilder() {
        layout = new CordonLayout();
        terrain = layout.terrain;
        for (String id : RaidConfig.ANOMALY_BLOCKS.get()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null && ForgeRegistries.BLOCKS.containsKey(key)) {
                anomalyBlocks.add(ForgeRegistries.BLOCKS.getValue(key));
            } else {
                LOGGER.warn("Zone Raids: anomaly block {} is not installed, skipping", id);
            }
        }
    }

    CordonLayout layout() {
        return layout;
    }

    List<Consumer<Painter>> steps() {
        List<Consumer<Painter>> steps = new ArrayList<>();
        int minChunk = Math.floorDiv(-CordonTerrain.OUTER, 16);
        int maxChunk = Math.floorDiv(CordonTerrain.OUTER, 16);
        for (int cx = minChunk; cx <= maxChunk; cx++) {
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                int fx = cx;
                int fz = cz;
                steps.add(p -> terrainChunk(p, fx, fz));
            }
        }
        for (int side = 0; side < 4; side++) {
            for (int from = -CordonTerrain.WALL; from <= CordonTerrain.WALL; from += 32) {
                int fs = side;
                int ff = from;
                steps.add(p -> wall(p, fs, ff, Math.min(CordonTerrain.WALL, ff + 31)));
            }
        }
        for (Site site : layout.sites) {
            if (site.kind() != CordonLayout.Kind.WALL) {
                steps.add(p -> site(p, site));
            }
        }
        for (int cx = minChunk; cx <= maxChunk; cx++) {
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                int fx = cx;
                int fz = cz;
                steps.add(p -> scatterChunk(p, fx, fz));
            }
        }
        steps.add(this::markers);
        return steps;
    }

    private Random random(int a, int b) {
        return new Random(CordonTerrain.SEED ^ (a * 341873128712L) ^ (b * 132897987541L));
    }

    private Canvas canvas(Painter p, Site site) {
        return new Canvas(p, random(site.x(), site.z()), site.x(), site.y(), site.z(), rotation(site.turns()));
    }

    private static Rotation rotation(int turns) {
        return switch (turns & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    // ------------------------------------------------------------------ terrain

    private void terrainChunk(Painter p, int cx, int cz) {
        LevelChunk chunk = p.level.getChunk(cx, cz);
        Column col = new Column();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = (cx << 4) + dx;
                int z = (cz << 4) + dz;
                if (Math.max(Math.abs(x), Math.abs(z)) > CordonTerrain.OUTER) {
                    continue;
                }
                terrain.sample(x, z, col);
                column(p, chunk, x, z, col);
            }
        }
    }

    private void column(Painter p, LevelChunk chunk, int x, int z, Column col) {
        int top = col.top;
        double h1 = terrain.hash(x, z);
        double h2 = terrain.hash(x + 7919, z - 104729);
        BlockState surface = surfaceBlock(x, z, col.surface, top < CordonTerrain.WATER, h1);
        BlockState sub = switch (col.surface) {
            case SWAMP, BANK, RIVER_BED -> Blocks.MUD.defaultBlockState();
            case ASPHALT, ASPHALT_EDGE, RAIL_BED -> Blocks.GRAVEL.defaultBlockState();
            default -> Blocks.DIRT.defaultBlockState();
        };
        for (int y = CordonTerrain.FLOOR; y <= top; y++) {
            BlockState state = y == top ? surface : y >= top - 3 ? sub : STONE.pick(Noise.hash(y, x, z));
            p.setTerrain(new BlockPos(x, y, z), state);
        }
        int clearFrom = top + 1;
        if (top < CordonTerrain.WATER) {
            for (int y = top + 1; y <= CordonTerrain.WATER; y++) {
                p.setTerrain(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState());
            }
            clearFrom = CordonTerrain.WATER + 1;
            if (col.surface == Surface.SWAMP && h2 < 0.05) {
                p.setTerrain(new BlockPos(x, clearFrom, z), Blocks.LILY_PAD.defaultBlockState());
                clearFrom++;
            }
        } else {
            clearFrom += plant(p, x, top + 1, z, col.surface, surface, h2);
        }
        for (int y = clearFrom; y <= CLEAR_TOP; y++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            if (section.hasOnlyAir()) {
                y = (y & ~15) + 15;
                continue;
            }
            p.setTerrain(new BlockPos(x, y, z), Painter.air());
        }
    }

    private BlockState surfaceBlock(int x, int z, Surface surface, boolean underwater, double roll) {
        double patch = terrain.patch(x, z, 14);
        Block block = switch (surface) {
            case ASPHALT -> roll < 0.06 ? Blocks.GRAVEL : roll < 0.18 ? Blocks.ANDESITE : roll < 0.22 ? Blocks.COBBLESTONE
                    : roll < 0.27 ? Blocks.COARSE_DIRT : Blocks.GRAY_CONCRETE;
            case ASPHALT_EDGE -> roll < 0.4 ? Blocks.GRAVEL : roll < 0.7 ? Blocks.COARSE_DIRT : roll < 0.9 ? Blocks.ANDESITE : Blocks.GRAY_CONCRETE;
            case DIRT_ROAD -> roll < 0.55 ? Blocks.DIRT_PATH : roll < 0.85 ? Blocks.COARSE_DIRT : Blocks.GRAVEL;
            case RAIL_BED -> roll < 0.8 ? Blocks.GRAVEL : Blocks.ANDESITE;
            case RIVER_BED -> roll < 0.4 ? Blocks.GRAVEL : roll < 0.7 ? Blocks.MUD : roll < 0.85 ? Blocks.CLAY : Blocks.SAND;
            case BANK -> underwater ? Blocks.MUD : roll < 0.35 ? Blocks.COARSE_DIRT : roll < 0.55 ? Blocks.MUD : Blocks.GRASS_BLOCK;
            case SWAMP -> underwater ? (roll < 0.7 ? Blocks.MUD : Blocks.CLAY)
                    : roll < 0.4 ? Blocks.MUD : roll < 0.7 ? Blocks.GRASS_BLOCK : roll < 0.85 ? Blocks.MOSS_BLOCK : Blocks.PODZOL;
            case ANOMALY -> roll < 0.25 ? Blocks.SOUL_SOIL : roll < 0.5 ? Blocks.COARSE_DIRT : roll < 0.65 ? Blocks.TUFF
                    : roll < 0.75 ? Blocks.BLACKSTONE : roll < 0.85 ? Blocks.PODZOL : Blocks.GRAVEL;
            default -> underwater ? Blocks.MUD : patch > 0.32 ? Blocks.COARSE_DIRT : patch < -0.42 ? Blocks.PODZOL
                    : roll < 0.03 ? Blocks.ROOTED_DIRT : roll < 0.05 ? Blocks.MOSS_BLOCK : Blocks.GRASS_BLOCK;
        };
        return block.defaultBlockState();
    }

    /** Places ground cover; returns how many blocks it used above the surface. */
    private int plant(Painter p, int x, int y, int z, Surface surface, BlockState ground, double roll) {
        boolean soil = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT)
                || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MUD) || ground.is(Blocks.ROOTED_DIRT);
        if (!soil || !(surface == Surface.NORMAL || surface == Surface.BANK || surface == Surface.SWAMP || surface == Surface.ANOMALY)) {
            return 0;
        }
        boolean grassy = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.PODZOL);
        if (surface == Surface.ANOMALY) {
            if (roll < 0.05 && !ground.is(Blocks.MOSS_BLOCK)) {
                p.setTerrain(new BlockPos(x, y, z), Blocks.DEAD_BUSH.defaultBlockState());
                return 1;
            }
            return 0;
        }
        double tall = surface == Surface.SWAMP ? 0.2 : 0.17;
        if (grassy && roll < 0.14) {
            p.setTerrain(new BlockPos(x, y, z), Blocks.GRASS.defaultBlockState());
            return 1;
        }
        if (grassy && roll < tall) {
            Block plant = roll < tall - 0.015 ? Blocks.TALL_GRASS : Blocks.LARGE_FERN;
            p.setTerrain(new BlockPos(x, y, z), plant.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
            p.setTerrain(new BlockPos(x, y + 1, z), plant.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
            return 2;
        }
        if (grassy && roll < tall + 0.015) {
            p.setTerrain(new BlockPos(x, y, z), Blocks.FERN.defaultBlockState());
            return 1;
        }
        if (roll < tall + 0.022 && (ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.PODZOL))) {
            p.setTerrain(new BlockPos(x, y, z), Blocks.DEAD_BUSH.defaultBlockState());
            return 1;
        }
        if (roll < tall + 0.024) {
            p.setTerrain(new BlockPos(x, y, z), Blocks.BROWN_MUSHROOM.defaultBlockState());
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ perimeter wall

    /** side: 0 north, 1 south, 2 west, 3 east. */
    private void wall(Painter p, int side, int from, int to) {
        Random r = random(side * 1000 + from, 77);
        int line = side == 0 || side == 2 ? -CordonTerrain.WALL : CordonTerrain.WALL;
        for (int t = from; t <= to; t++) {
            int x = side < 2 ? t : line;
            int z = side < 2 ? line : t;
            int base = Math.max(terrain.top(x, z), CordonTerrain.WATER - 1);
            boolean gate = side < 2 ? Math.abs(x - CordonTerrain.mainRoadX(z)) <= 4.5
                    : Math.abs(z - CordonTerrain.RAIL_Z) <= 3 || (side == 2 && Math.abs(z - 96) <= 2);
            boolean pillar = Math.floorMod(t, 8) == 0;
            for (int y = base + 1; y <= base + 6; y++) {
                BlockState state;
                if (gate) {
                    state = y <= base + 5 ? Blocks.IRON_BARS.defaultBlockState() : Painter.air();
                } else if (pillar) {
                    state = y <= base + 5 ? BRICK.pick(r) : Blocks.STONE_BRICK_SLAB.defaultBlockState();
                } else if (y <= base + 4) {
                    state = CONCRETE.pick(r);
                } else {
                    state = r.nextDouble() < 0.85 ? Blocks.IRON_BARS.defaultBlockState() : Painter.air();
                }
                p.set(x, y, z, state);
            }
            // The base sits on the ground even where the terrain dips.
            p.pillarDown(x, base, z, BRICK.pick(r), 8);
        }
    }

    // ------------------------------------------------------------------ sites

    private void site(Painter p, Site site) {
        switch (site.kind()) {
            case HOUSE -> house(canvas(p, site), site.size());
            case VILLAGE_CENTER -> villageCenter(canvas(p, site));
            case GRAVES -> graves(canvas(p, site));
            case FARM -> farm(canvas(p, site));
            case CHECKPOINT -> checkpoint(canvas(p, site));
            case SOUTH_GATE -> southGate(canvas(p, site));
            case NORTH_GATE -> northGate(canvas(p, site));
            case GAS_STATION -> gasStation(canvas(p, site));
            case APARTMENTS -> apartments(canvas(p, site));
            case FACTORY -> factory(canvas(p, site));
            case RADIO_HILL -> radioHill(canvas(p, site));
            case HELIPAD -> helipad(canvas(p, site));
            case SWAMP_HUT -> swampHut(canvas(p, site));
            case RAILWAY -> railway(p);
            case ROAD_BRIDGE -> roadBridge(p);
            case ANOMALY_FIELD -> anomalyField(p);
            case CAR -> car(canvas(p, site));
            case POWER_LINE -> powerLine(p);
            default -> {
            }
        }
    }

    private static void crate(Canvas c, int u, int y, int w, Block crate) {
        c.set(u, y, w, crate);
    }

    private static Block common() {
        return ModRegistry.COMMON_CRATE.get();
    }

    private static Block military() {
        return ModRegistry.MILITARY_CRATE.get();
    }

    private static Block medical() {
        return ModRegistry.MEDICAL_CRATE.get();
    }

    private static Block randomCrate(Random r, double medicalChance, double militaryChance) {
        double roll = r.nextDouble();
        return roll < medicalChance ? medical() : roll < medicalChance + militaryChance ? military() : common();
    }

    /** Window: broken, glass or bars. */
    private static void window(Canvas c, int u, int y, int w) {
        double roll = c.random.nextDouble();
        c.set(u, y, w, roll < 0.5 ? Painter.air() : roll < 0.8 ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState());
    }

    /** Clears vegetation above a footprint. */
    private static void clear(Canvas c, int u1, int w1, int u2, int w2, int height) {
        c.air(u1, 1, w1, u2, height, w2);
    }

    // --- village

    private void house(Canvas c, int a) {
        Random r = c.random;
        int variant = r.nextInt(3);
        Block plank = variant == 0 ? Blocks.SPRUCE_PLANKS : variant == 1 ? Blocks.DARK_OAK_PLANKS : Blocks.OAK_PLANKS;
        Block log = variant == 1 ? Blocks.DARK_OAK_LOG : Blocks.SPRUCE_LOG;
        Palette wall = variant == 2 ? RED_BRICK : Palette.of(plank, 10, Blocks.STRIPPED_SPRUCE_LOG, 1, Blocks.MOSSY_COBBLESTONE, 1);
        Block roof = r.nextBoolean() ? Blocks.DARK_OAK_STAIRS : (r.nextBoolean() ? Blocks.SPRUCE_STAIRS : Blocks.DEEPSLATE_TILE_STAIRS);
        boolean ruined = r.nextDouble() < 0.5;

        clear(c, -a - 2, -5, a + 2, 5, 9);
        c.foundation(-a, -3, a, 3, Blocks.COBBLESTONE.defaultBlockState());
        c.fill(-a, 0, -3, a, 0, 3, Palette.of(plank, 8, Blocks.COARSE_DIRT, 1));
        c.walls(-a, 1, -3, a, 3, 3, wall, ruined ? 0.12 : 0.03);
        for (int cu : new int[]{-a, a}) {
            for (int cw : new int[]{-3, 3}) {
                c.fill(cu, 1, cw, cu, 3, cw, Canvas.log(log, Direction.Axis.Y));
            }
        }
        window(c, -a + 1, 2, -3);
        window(c, a - 1, 2, -3);
        window(c, -a + 1, 2, 3);
        window(c, a - 1, 2, 3);
        window(c, -a, 2, 0);
        window(c, a, 2, 0);
        c.air(0, 1, -3, 0, 2, -3);
        if (r.nextBoolean()) {
            c.door(0, 1, -3, Blocks.SPRUCE_DOOR, Direction.NORTH, r.nextBoolean());
        }
        // Gable roof with the ridge along u; ruined houses lose part of it.
        int cut = ruined ? -a + 1 + r.nextInt(2 * a) : Integer.MAX_VALUE;
        for (int k = 0; ; k++) {
            int front = -4 + k;
            int back = 4 - k;
            int y = 4 + k;
            if (front > back) {
                break;
            }
            for (int u = -a - 1; u <= a + 1; u++) {
                if ((u >= cut && k > 0) || r.nextDouble() < 0.06) {
                    continue;
                }
                if (front == back) {
                    c.set(u, y, front, plank);
                } else {
                    c.set(u, y, front, Canvas.stairs(roof, Direction.SOUTH, false));
                    c.set(u, y, back, Canvas.stairs(roof, Direction.NORTH, false));
                }
            }
            if (k < 4) {
                for (int w = -3 + k; w <= 3 - k; w++) {
                    c.set(-a, y, w, wall);
                    c.set(a, y, w, wall);
                }
            }
        }
        if (a >= 4) {
            c.fill(0, 1, -2, 0, 3, 2, wall);
            c.air(0, 1, 0, 0, 2, 0);
            crate(c, 3, 1, -2, randomCrate(r, 0.3, 0.1));
        }
        crate(c, -a + 1, 1, 2, randomCrate(r, 0.3, 0.05));
        c.set(a - 1, 1, -2, Blocks.SPRUCE_FENCE);
        c.set(a - 1, 2, -2, Blocks.SPRUCE_PRESSURE_PLATE);
        c.set(a - 1, 1, 2, Blocks.CAULDRON);
        c.set(-a + 1, 3, -2, Blocks.COBWEB);
        c.set(-1, 1, 1, Blocks.BROWN_CARPET);
        c.set(-1, 1, 0, Blocks.BROWN_CARPET);
        // Garden fence in front of some houses.
        if (r.nextBoolean()) {
            for (int u = -a; u <= a; u++) {
                if (u != 0 && r.nextDouble() < 0.8) {
                    c.set(u, 1, -5, Blocks.SPRUCE_FENCE);
                }
            }
        }
    }

    private void villageCenter(Canvas c) {
        clear(c, -6, -6, 6, 6, 5);
        c.set(0, 0, 0, Blocks.COARSE_DIRT);
        c.set(0, 1, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        c.fill(-2, 1, -1, -2, 1, 1, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.Z));
        c.fill(2, 1, -1, 2, 1, 1, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.Z));
        c.fill(-1, 1, 2, 1, 1, 2, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.X));
        // Well.
        c.fill(-6, 0, -6, -4, 0, -4, Blocks.COBBLESTONE);
        c.set(-5, 0, -5, Blocks.WATER);
        c.set(-5, -1, -5, Blocks.COBBLESTONE);
        for (int u = -6; u <= -4; u++) {
            for (int w = -6; w <= -4; w++) {
                if (u != -5 || w != -5) {
                    c.set(u, 1, w, Blocks.COBBLESTONE_WALL);
                }
            }
        }
        c.fill(-6, 2, -6, -6, 3, -6, Blocks.SPRUCE_FENCE);
        c.fill(-4, 2, -4, -4, 3, -4, Blocks.SPRUCE_FENCE);
        c.fill(-6, 2, -4, -6, 3, -4, Blocks.SPRUCE_FENCE);
        c.fill(-4, 2, -6, -4, 3, -6, Blocks.SPRUCE_FENCE);
        c.fill(-6, 4, -6, -4, 4, -4, Canvas.slab(Blocks.SPRUCE_SLAB, false));
        // Stalker camp: a lean-to with supplies.
        c.fill(4, 1, 3, 4, 2, 5, Blocks.SPRUCE_FENCE);
        c.fill(3, 3, 3, 5, 3, 5, Canvas.slab(Blocks.SPRUCE_SLAB, false));
        crate(c, 3, 1, 4, medical());
        crate(c, 5, 1, 4, common());
        c.fill(-1, 1, -2, 1, 1, -2, Blocks.GREEN_CARPET);
        c.set(6, 1, -6, Blocks.SPRUCE_FENCE);
        c.set(6, 2, -6, Blocks.SPRUCE_FENCE);
        c.wallSign(6, 2, -5, Direction.SOUTH, "Деревня", "новичков");
    }

    private void graves(Canvas c) {
        clear(c, -5, -4, 5, 4, 4);
        for (int i = 0; i < 6; i++) {
            int u = -4 + (i % 3) * 4;
            int w = i < 3 ? -2 : 2;
            c.set(u, 0, w, Blocks.COARSE_DIRT);
            c.set(u, 0, w + 1, Blocks.PODZOL);
            c.set(u, 1, w - 1, Blocks.SPRUCE_FENCE);
            c.set(u, 2, w - 1, Blocks.SPRUCE_FENCE);
            c.set(u, 3, w - 1, Blocks.SPRUCE_FENCE);
            c.set(u - 1, 2, w - 1, Blocks.SPRUCE_FENCE);
            c.set(u + 1, 2, w - 1, Blocks.SPRUCE_FENCE);
        }
        c.set(5, 1, 4, Blocks.SKELETON_SKULL);
    }

    private void farm(Canvas c) {
        Random r = c.random;
        clear(c, -21, -11, 21, 11, 16);
        // Barn with a collapsed roof: only the trusses are left.
        c.foundation(-18, -9, -4, 1, Blocks.COBBLESTONE.defaultBlockState());
        c.fill(-18, 0, -9, -4, 0, 1, YARD);
        c.walls(-18, 1, -9, -4, 5, 1, BRICK, 0.08);
        c.air(-18, 1, -5, -18, 4, -3);
        c.air(-4, 1, -5, -4, 4, -3);
        c.air(-11, 1, 1, -10, 3, 1);
        for (int u = -17; u <= -5; u += 3) {
            c.fill(u, 6, -9, u, 6, 1, Canvas.log(Blocks.DARK_OAK_LOG, Direction.Axis.Z));
        }
        c.fill(-18, 6, -4, -4, 6, -4, Canvas.log(Blocks.DARK_OAK_LOG, Direction.Axis.X));
        for (int u = -17; u <= -5; u++) {
            if (r.nextDouble() < 0.35) {
                c.set(u, 6, -9 + r.nextInt(11), Blocks.DARK_OAK_SLAB);
            }
        }
        c.fill(-16, 1, -8, -15, 2, -7, Blocks.HAY_BLOCK);
        c.set(-14, 1, -8, Blocks.HAY_BLOCK);
        c.set(-8, 1, -7, Blocks.IRON_BLOCK);
        c.set(-8, 2, -7, Blocks.ANVIL);
        c.set(-7, 1, -7, Blocks.GRINDSTONE);
        crate(c, -17, 1, 0, common());
        crate(c, -6, 1, -8, common());
        crate(c, -12, 1, -8, medical());
        // Silo.
        int sx = 6, sw = -5;
        for (int y = 0; y <= 14; y++) {
            for (int u = -3; u <= 3; u++) {
                for (int w = -3; w <= 3; w++) {
                    double d = Math.sqrt(u * u + w * w);
                    if (d <= 3.3 && (y == 0 || d > 2.3)) {
                        if (y > 10 && r.nextDouble() < 0.3) {
                            continue;
                        }
                        c.set(sx + u, y, sw + w, y % 5 == 0 ? Blocks.GRAY_CONCRETE.defaultBlockState() : CONCRETE.pick(r));
                    }
                }
            }
        }
        c.air(sx, 1, sw + 3, sx, 2, sw + 3);
        // Pens.
        for (int u = 2; u <= 19; u++) {
            for (int w : new int[]{3, 10}) {
                if (r.nextDouble() < 0.8) {
                    c.set(u, 1, w, Blocks.OAK_FENCE);
                }
            }
        }
        for (int w = 3; w <= 10; w++) {
            for (int u : new int[]{2, 19}) {
                if (r.nextDouble() < 0.8 && w != 6) {
                    c.set(u, 1, w, Blocks.OAK_FENCE);
                }
            }
        }
        c.fill(5, 0, 5, 16, 0, 8, Palette.of(Blocks.COARSE_DIRT, 3, Blocks.MUD, 2, Blocks.GRASS_BLOCK, 2));
        c.set(9, 1, 6, Blocks.HAY_BLOCK);
        c.set(14, 1, 8, Blocks.CAULDRON);
        // Brick storehouse.
        c.foundation(13, -10, 18, -6, Blocks.COBBLESTONE.defaultBlockState());
        c.fill(13, 0, -10, 18, 0, -6, Blocks.COBBLESTONE);
        c.walls(13, 1, -10, 18, 3, -6, RED_BRICK, 0.05);
        c.fill(13, 4, -10, 18, 4, -6, Canvas.slab(Blocks.COBBLESTONE_SLAB, false));
        c.air(15, 1, -6, 15, 2, -6);
        crate(c, 17, 1, -9, common());
        // Tractor.
        tractor(c, -1, 7);
    }

    private static void tractor(Canvas c, int u, int w) {
        c.fill(u, 1, w, u + 1, 1, w + 3, Blocks.GREEN_CONCRETE);
        c.set(u - 1, 1, w, Blocks.BLACK_CONCRETE);
        c.set(u + 2, 1, w, Blocks.BLACK_CONCRETE);
        c.set(u - 1, 1, w + 3, Blocks.BLACK_CONCRETE);
        c.set(u + 2, 1, w + 3, Blocks.BLACK_CONCRETE);
        c.fill(u, 2, w + 2, u + 1, 2, w + 3, Blocks.GLASS_PANE);
        c.fill(u, 3, w + 2, u + 1, 3, w + 3, Blocks.GREEN_CONCRETE);
        c.set(u, 2, w, Blocks.LIGHTNING_ROD);
    }

    // --- military

    private void checkpoint(Canvas c) {
        Random r = c.random;
        clear(c, -18, -9, 18, 9, 11);
        // Boom barrier: half down, half up.
        for (int u : new int[]{-5, 5}) {
            c.fill(u, 1, 0, u, 2, 0, Blocks.STONE_BRICK_WALL);
        }
        for (int u = -4; u <= 0; u++) {
            c.set(u, 2, 0, (u & 1) == 0 ? Blocks.RED_CONCRETE : Blocks.WHITE_CONCRETE);
        }
        for (int y = 2; y <= 6; y++) {
            c.set(4, y, 0, (y & 1) == 0 ? Blocks.RED_CONCRETE : Blocks.WHITE_CONCRETE);
        }
        guardBooth(c, -11, -3, Direction.EAST, military(), medical());
        guardBooth(c, 7, -3, Direction.WEST, military(), common());
        c.wallSign(-7, 3, -4, Direction.NORTH, "КПП", "Проход", "запрещён");
        // Concrete blocks in a zigzag.
        for (int[] blk : new int[][]{{-3, 5}, {1, 8}, {-3, -6}, {1, -9}}) {
            c.fill(blk[0], 1, blk[1], blk[0] + 2, 1, blk[1], Blocks.SMOOTH_STONE);
            c.fill(blk[0], 2, blk[1], blk[0] + 2, 2, blk[1], Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
        }
        // Sandbags.
        for (int u = -17; u <= -12; u++) {
            sandbag(c, u, -6, 2);
        }
        for (int w = -8; w <= -5; w++) {
            sandbag(c, 13, w, 2);
        }
        for (int u = 13; u <= 17; u++) {
            sandbag(c, u, -8, 2);
        }
        // Watchtower with a ladder.
        for (int[] leg : new int[][]{{13, 3}, {16, 3}, {13, 6}, {16, 6}}) {
            c.fill(leg[0], 1, leg[1], leg[0], 10, leg[1], Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.Y));
        }
        c.fill(13, 7, 3, 16, 7, 6, Blocks.SPRUCE_PLANKS);
        for (int u = 13; u <= 16; u++) {
            for (int w = 3; w <= 6; w++) {
                boolean edge = u == 13 || u == 16 || w == 3 || w == 6;
                boolean leg = (u == 13 || u == 16) && (w == 3 || w == 6);
                if (edge && !leg) {
                    c.set(u, 8, w, Blocks.SPRUCE_FENCE);
                }
            }
        }
        c.fill(12, 11, 2, 17, 11, 7, Canvas.slab(Blocks.SPRUCE_SLAB, false));
        // Ladder inside the tower, against a back board, up through the platform.
        c.fill(14, 1, 3, 14, 6, 3, Blocks.SPRUCE_PLANKS);
        c.fill(14, 1, 4, 14, 7, 4, Canvas.ladder(Direction.SOUTH));
        crate(c, 15, 8, 5, military());
        c.set(14, 10, 4, Canvas.lantern(Blocks.LANTERN, true));
        // Army tent.
        for (int w = 3; w <= 8; w++) {
            c.set(-17, 1, w, Blocks.GREEN_WOOL);
            c.set(-13, 1, w, Blocks.GREEN_WOOL);
            c.set(-16, 2, w, Blocks.GREEN_WOOL);
            c.set(-14, 2, w, Blocks.GREEN_WOOL);
            c.set(-15, 3, w, Blocks.GREEN_WOOL);
        }
        c.fill(-16, 1, 4, -14, 1, 7, Blocks.GREEN_CARPET);
        crate(c, -15, 1, 7, medical());
        crate(c, -14, 1, 4, common());
        // Barbed wire across the fields.
        for (int u = -18; u <= -12; u++) {
            c.set(u, 1, 0, r.nextDouble() < 0.85 ? Blocks.IRON_BARS.defaultBlockState() : Blocks.COBWEB.defaultBlockState());
        }
        for (int u = 12; u <= 18; u++) {
            c.set(u, 1, 0, r.nextDouble() < 0.85 ? Blocks.IRON_BARS.defaultBlockState() : Blocks.COBWEB.defaultBlockState());
        }
        lampPost(c, -6, -2);
        lampPost(c, 6, 2);
    }

    private static void sandbag(Canvas c, int u, int w, int height) {
        c.fill(u, 1, w, u, height - 1, w, Blocks.PACKED_MUD);
        c.set(u, height, w, Canvas.slab(Blocks.MUD_BRICK_SLAB, false));
    }

    private static void lampPost(Canvas c, int u, int w) {
        c.fill(u, 1, w, u, 3, w, Blocks.SPRUCE_FENCE);
        c.set(u, 4, w, Canvas.lantern(Blocks.LANTERN, false));
    }

    /** 5x5 concrete booth with the door on the {@code door} side. Corner at (u, w). */
    private static void guardBooth(Canvas c, int u, int w, Direction door, Block crateA, Block crateB) {
        c.foundation(u, w, u + 4, w + 4, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(u, 0, w, u + 4, 0, w + 4, Blocks.GRAY_CONCRETE);
        c.walls(u, 1, w, u + 4, 3, w + 4, CONCRETE, 0.0);
        c.air(u + 1, 1, w + 1, u + 3, 3, w + 3);
        c.fill(u, 4, w, u + 4, 4, w + 4, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
        for (int i = 1; i <= 3; i++) {
            c.set(u + i, 2, w, Blocks.IRON_BARS);
            c.set(u + i, 2, w + 4, Blocks.GLASS_PANE);
        }
        int du = door == Direction.EAST ? u + 4 : u;
        c.air(du, 1, w + 2, du, 2, w + 2);
        c.door(du, 1, w + 2, Blocks.IRON_DOOR, door, true);
        crate(c, door == Direction.EAST ? u + 1 : u + 3, 1, w + 1, crateA);
        crate(c, door == Direction.EAST ? u + 1 : u + 3, 1, w + 3, crateB);
        c.set(u + 2, 3, w + 2, Canvas.lantern(Blocks.LANTERN, true));
    }

    private void southGate(Canvas c) {
        clear(c, -8, -4, 8, 4, 8);
        int w = CordonTerrain.WALL - 152;
        for (int u : new int[]{-5, 5}) {
            c.fill(u, 1, w, u, 7, w, BRICK);
            c.set(u, 8, w, Canvas.lantern(Blocks.LANTERN, false));
        }
        c.wallSign(-5, 3, w - 1, Direction.NORTH, "ВЫХОД", "КПП «Юг»");
        c.foundation(7, -1, 9, 1, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(7, 0, -1, 9, 0, 1, Blocks.GRAY_CONCRETE);
        c.walls(7, 1, -1, 9, 2, 1, CONCRETE, 0.0);
        c.air(8, 1, 0, 8, 2, 0);
        c.air(7, 1, 0, 7, 2, 0);
        c.fill(7, 3, -1, 9, 3, 1, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
        crate(c, 8, 1, 0, common());
    }

    private void northGate(Canvas c) {
        Random r = c.random;
        clear(c, -9, -4, 9, 4, 8);
        int w = -CordonTerrain.WALL + 152;
        for (int u : new int[]{-5, 5}) {
            c.fill(u, 1, w, u, 7, w, BRICK);
            c.set(u, 8, w, Canvas.lantern(Blocks.LANTERN, false));
        }
        c.wallSign(-5, 3, w + 1, Direction.SOUTH, "ВЫХОД", "Северная", "трасса");
        for (int u = -3; u <= 3; u += 2) {
            c.set(u, 1, -2, Blocks.SMOOTH_STONE);
            c.set(u, 2, -2, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
        }
        guardBooth(c, -10, -1, Direction.EAST, common(), military());
        for (int i = 0; i < 6; i++) {
            c.set(6 + r.nextInt(3), 1, -2 + r.nextInt(5), RUBBLE);
        }
    }

    // --- civilian

    private void gasStation(Canvas c) {
        Random r = c.random;
        clear(c, -12, -9, 12, 9, 8);
        c.fill(-11, 0, -4, 3, 0, 8, Palette.of(Blocks.GRAY_CONCRETE, 6, Blocks.ANDESITE, 2, Blocks.GRAVEL, 1));
        for (int[] pillar : new int[][]{{-8, -1}, {2, -1}, {-8, 5}, {2, 5}}) {
            c.fill(pillar[0], 1, pillar[1], pillar[0], 5, pillar[1], Blocks.POLISHED_ANDESITE);
        }
        for (int u = -10; u <= 4; u++) {
            for (int w = -3; w <= 7; w++) {
                boolean edge = u == -10 || u == 4 || w == -3 || w == 7;
                if (r.nextDouble() < 0.1) {
                    continue;
                }
                c.set(u, 6, w, edge ? Blocks.WHITE_CONCRETE.defaultBlockState() : Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
            }
        }
        c.set(-3, 5, 2, Canvas.lantern(Blocks.LANTERN, true));
        for (int u : new int[]{-5, -1}) {
            c.set(u, 1, 2, Blocks.GRAY_CONCRETE);
            c.set(u, 2, 2, Blocks.RED_CONCRETE);
            c.wallSign(u, 2, 3, Direction.SOUTH, "АИ-92");
        }
        // Shop.
        c.foundation(5, -7, 12, 1, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(5, 0, -7, 12, 0, 1, Blocks.SMOOTH_STONE);
        c.walls(5, 1, -7, 12, 4, 1, Palette.of(Blocks.WHITE_CONCRETE, 6, Blocks.LIGHT_GRAY_CONCRETE, 3, Blocks.CRACKED_STONE_BRICKS, 1), 0.04);
        c.air(6, 1, -6, 11, 4, 0);
        for (int u = 6; u <= 11; u++) {
            window(c, u, 2, 1);
            window(c, u, 3, 1);
        }
        c.air(8, 1, 1, 8, 2, 1);
        c.fill(5, 5, -7, 12, 5, 1, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
        c.fill(6, 1, -3, 10, 1, -3, Blocks.STONE_BRICKS);
        c.fill(6, 2, -3, 10, 2, -3, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
        c.fill(6, 2, -6, 11, 2, -6, Canvas.slab(Blocks.SPRUCE_SLAB, true));
        crate(c, 11, 1, -6, common());
        crate(c, 6, 1, -6, common());
        crate(c, 11, 1, -1, medical());
        c.set(6, 4, -1, Blocks.COBWEB);
        c.wallSign(9, 4, 2, Direction.SOUTH, "ПРОДУКТЫ");
        // Price sign on a pole.
        c.fill(-11, 1, 8, -11, 5, 8, Blocks.IRON_BARS);
        c.set(-11, 6, 8, Blocks.WHITE_CONCRETE);
        c.wallSign(-11, 6, 9, Direction.SOUTH, "АЗС", "24 часа");
        car(new Canvas(c.painter, r, c.worldX(-4, -7), c.oy, c.worldZ(-4, -7), c.rotation.getRotated(Rotation.CLOCKWISE_90)));
    }

    private void apartments(Canvas c) {
        Random r = c.random;
        Palette wall = Palette.of(Blocks.LIGHT_GRAY_CONCRETE, 8, Blocks.WHITE_CONCRETE, 2, Blocks.CRACKED_STONE_BRICKS, 1, Blocks.ANDESITE, 1);
        clear(c, -15, -7, 15, 7, 16);
        c.foundation(-13, -5, 13, 5, Blocks.STONE_BRICKS.defaultBlockState());
        for (int f = 0; f < 3; f++) {
            int yb = f * 4;
            c.fill(-13, yb, -5, 13, yb, 5, Palette.of(Blocks.SMOOTH_STONE, 6, Blocks.GRAY_CONCRETE, 2, Blocks.ANDESITE, 1));
            c.walls(-13, yb + 1, -5, 13, yb + 3, 5, wall, 0.04);
            c.air(-12, yb + 1, -4, 12, yb + 3, 4);
            for (int u = -12; u <= 12; u += 2) {
                window(c, u, yb + 2, -5);
                window(c, u, yb + 2, 5);
            }
            for (int pu : new int[]{-7, 0, 6}) {
                c.fill(pu, yb + 1, -4, pu, yb + 3, 4, wall);
                c.air(pu, yb + 1, 1, pu, yb + 2, 1);
            }
            // Stairwell along u, from this floor to the next one.
            for (int i = 0; i < 4; i++) {
                c.fill(8 + i, yb + 1 + i, -4, 8 + i, yb + 1 + i, -3, Canvas.stairs(Blocks.STONE_BRICK_STAIRS, Direction.EAST, false));
            }
            c.air(8, yb + 4, -4, 11, yb + 4, -3);
            c.fill(8, yb + 1, -2, 11, yb + 3, -2, Blocks.IRON_BARS);
            crate(c, -11, yb + 1, -3, randomCrate(r, 0.35, 0.0));
            crate(c, 3, yb + 1, 3, randomCrate(r, 0.2, 0.1));
            c.set(-4, yb + 1, 3, Blocks.SPRUCE_FENCE);
            c.set(-4, yb + 2, 3, Blocks.SPRUCE_PRESSURE_PLATE);
            c.set(-12, yb + 3, 4, Blocks.COBWEB);
            c.set(4, yb + 1, -4, Blocks.BOOKSHELF);
        }
        c.fill(-13, 12, -5, 13, 12, 5, Blocks.SMOOTH_STONE);
        c.air(8, 12, -4, 11, 12, -3);
        c.walls(-13, 13, -5, 13, 13, 5, wall, 0.1);
        crate(c, -10, 13, -3, military());
        // Entrances.
        c.air(-13, 1, 0, -13, 2, 0);
        c.air(-3, 1, 5, -3, 2, 5);
        c.door(-3, 1, 5, Blocks.SPRUCE_DOOR, Direction.SOUTH, true);
        // Collapsed east end of the top floor with rubble below.
        for (int u = 9; u <= 13; u++) {
            for (int w = -5; w <= 5; w++) {
                for (int y = 9; y <= 13; y++) {
                    if (r.nextDouble() < 0.55 + (u - 9) * 0.08) {
                        c.set(u, y, w, Painter.air());
                    }
                }
            }
        }
        for (int i = 0; i < 25; i++) {
            int u = 13 + 1 + r.nextInt(2);
            int w = -5 + r.nextInt(11);
            c.set(u, 1, w, RUBBLE);
            if (r.nextBoolean()) {
                c.set(u, 2, w, Canvas.slab(Blocks.STONE_BRICK_SLAB, false));
            }
        }
        c.wallSign(-14, 2, 2, Direction.WEST, "Общежитие", "№ 3");
    }

    // --- factory

    private void factory(Canvas c) {
        Random r = c.random;
        clear(c, -28, -25, 28, 25, 14);
        for (int u = -26; u <= 26; u++) {
            for (int w = -23; w <= 23; w++) {
                if (r.nextDouble() < 0.55) {
                    c.set(u, 0, w, YARD);
                }
            }
        }
        // Compound fence with gates.
        for (int u = -27; u <= 27; u++) {
            for (int w : new int[]{-24, 24}) {
                boolean gate = w == 24 ? u >= -11 && u <= -5 : u >= 13 && u <= 19;
                if (!gate) {
                    fenceColumn(c, u, w);
                }
            }
        }
        for (int w = -24; w <= 24; w++) {
            fenceColumn(c, -27, w);
            fenceColumn(c, 27, w);
        }
        // Main hall.
        int u1 = -22, u2 = 10, w1 = -20, w2 = -4;
        c.foundation(u1, w1, u2, w2, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(u1, 0, w1, u2, 0, w2, Palette.of(Blocks.SMOOTH_STONE, 5, Blocks.GRAY_CONCRETE, 3, Blocks.ANDESITE, 1));
        for (int y = 1; y <= 10; y++) {
            for (int u = u1; u <= u2; u++) {
                for (int w = w1; w <= w2; w++) {
                    if (u != u1 && u != u2 && w != w1 && w != w2) {
                        continue;
                    }
                    boolean pillar = Math.floorMod(u - u1, 4) == 0 && (w == w1 || w == w2) || Math.floorMod(w - w1, 4) == 0 && (u == u1 || u == u2);
                    if (pillar) {
                        c.set(u, y, w, BRICK);
                    } else if (y >= 6 && y <= 8) {
                        window(c, u, y, w);
                    } else {
                        c.set(u, y, w, CONCRETE);
                    }
                }
            }
        }
        c.air(-16, 1, w2, -13, 5, w2);
        c.air(2, 1, w2, 5, 5, w2);
        c.air(u1, 1, -13, u1, 3, -11);
        for (int u = u1; u <= u2; u++) {
            for (int w = w1; w <= w2; w++) {
                boolean collapsed = u >= -6 && u <= 0 && w >= -16 && w <= -10;
                if (!collapsed && r.nextDouble() > 0.12) {
                    c.set(u, 11, w, Blocks.SMOOTH_STONE);
                }
            }
        }
        for (int i = 0; i < 30; i++) {
            int u = -6 + r.nextInt(7);
            int w = -16 + r.nextInt(7);
            c.set(u, 1, w, RUBBLE);
            if (r.nextDouble() < 0.4) {
                c.set(u, 2, w, RUBBLE);
            }
        }
        // Catwalk with a ladder.
        c.fill(u1 + 1, 5, w1 + 1, u2 - 1, 5, w1 + 2, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, true));
        c.fill(u1 + 1, 6, w1 + 3, u2 - 1, 6, w1 + 3, Blocks.IRON_BARS);
        c.air(u1 + 1, 6, w1 + 3, u1 + 1, 6, w1 + 3);
        c.fill(u1 + 1, 1, w1 + 3, u1 + 1, 5, w1 + 3, Canvas.ladder(Direction.EAST));
        crate(c, 0, 6, w1 + 1, military());
        crate(c, -10, 6, w1 + 1, common());
        // Machines.
        for (int u = -18; u <= 8; u += 4) {
            if (u >= -7 && u <= 1) {
                continue;
            }
            c.fill(u, 1, -12, u + 1, 1, -12, Blocks.IRON_BLOCK);
            c.set(u, 2, -12, Blocks.ANVIL);
            c.set(u + 1, 2, -12, r.nextBoolean() ? Blocks.GRINDSTONE : Blocks.STONECUTTER);
        }
        crate(c, u1 + 2, 1, w2 - 2, common());
        crate(c, u2 - 2, 1, w2 - 2, common());
        crate(c, -2, 1, w1 + 3, common());
        crate(c, 6, 1, w1 + 3, medical());
        crate(c, u1 + 2, 1, w1 + 3, military());
        for (int u = -18; u <= 6; u += 8) {
            c.fill(u, 9, -12, u, 10, -12, Blocks.CHAIN);
            c.set(u, 8, -12, Canvas.lantern(Blocks.LANTERN, true));
        }
        // Chimney with a sniper platform.
        int cu = 18, cw = -14;
        for (int y = 0; y <= 44; y++) {
            for (int u = -5; u <= 5; u++) {
                for (int w = -5; w <= 5; w++) {
                    double d = Math.sqrt(u * u + w * w);
                    if (d <= 3.4 && (d > 2.4 || y == 0)) {
                        c.set(cu + u, y, cw + w, y > 40 && r.nextDouble() < 0.3 ? Painter.air()
                                : y % 9 == 8 ? Blocks.RED_TERRACOTTA.defaultBlockState() : RED_BRICK.pick(r));
                    } else if (y == 26 && d > 3.4 && d <= 5.2 && !(u == 0 && w == -4)) {
                        c.set(cu + u, y, cw + w, Canvas.slab(Blocks.STONE_BRICK_SLAB, true));
                    } else if (y == 27 && d > 4.6 && d <= 5.6) {
                        c.set(cu + u, y, cw + w, Blocks.IRON_BARS);
                    }
                }
            }
        }
        c.foundation(cu - 3, cw - 3, cu + 3, cw + 3, Blocks.BRICKS.defaultBlockState());
        c.fill(cu, 1, cw - 4, cu, 26, cw - 4, Canvas.ladder(Direction.NORTH));
        crate(c, cu + 4, 27, cw, military());
        // Warehouse with shelves.
        int a1 = -18, a2 = 2, b1 = 6, b2 = 18;
        c.foundation(a1, b1, a2, b2, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(a1, 0, b1, a2, 0, b2, Blocks.SMOOTH_STONE);
        c.walls(a1, 1, b1, a2, 6, b2, CORRUGATED, 0.05);
        c.air(a1 + 1, 1, b1 + 1, a2 - 1, 6, b2 - 1);
        c.air(-12, 1, b1, -9, 4, b1);
        c.air(a2, 1, 11, a2, 3, 12);
        for (int u = a1; u <= a2; u++) {
            for (int w = b1; w <= b2; w++) {
                if (r.nextDouble() > 0.1) {
                    c.set(u, 7, w, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
                }
            }
        }
        for (int w : new int[]{10, 14}) {
            for (int u = -16; u <= -1; u++) {
                if ((u & 3) == 0) {
                    c.fill(u, 1, w, u, 4, w, Blocks.SPRUCE_FENCE);
                }
                c.set(u, 2, w, Canvas.slab(Blocks.SPRUCE_SLAB, true));
                c.set(u, 4, w, Canvas.slab(Blocks.SPRUCE_SLAB, true));
            }
        }
        crate(c, -14, 3, 10, common());
        crate(c, -6, 1, 15, common());
        crate(c, -3, 5, 14, military());
        // Shipping containers.
        container(c, 14, 4, Blocks.RED_TERRACOTTA, military());
        container(c, 14, 8, Blocks.BLUE_TERRACOTTA, null);
        container(c, 14, 15, Blocks.ORANGE_TERRACOTTA, common());
        containerShell(c, 14, 4, 3, Blocks.GREEN_TERRACOTTA);
        car(new Canvas(c.painter, r, c.worldX(-2, 1), c.oy, c.worldZ(-2, 1), c.rotation));
        c.wallSign(-8, 3, 25, Direction.SOUTH, "Завод", "«Металлист»");
    }

    private static void fenceColumn(Canvas c, int u, int w) {
        if (c.random.nextDouble() < 0.12) {
            return;
        }
        c.pillarDown(u, 0, w, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(u, 1, w, u, 2, w, CONCRETE);
        c.set(u, 3, w, Blocks.IRON_BARS);
    }

    private static void container(Canvas c, int u, int w, Block color, Block crate) {
        containerShell(c, u, w, 0, color);
        if (crate != null) {
            crate(c, u + 5, 1, w + 1, crate);
        }
    }

    private static void containerShell(Canvas c, int u, int w, int y, Block color) {
        c.fill(u, y + 1, w, u + 6, y + 3, w + 2, color);
        c.air(u, y + 2, w + 1, u + 5, y + 2, w + 1);
        c.air(u, y + 1, w + 1, u + 5, y + 1, w + 1);
        c.set(u + 6, y + 2, w + 1, Blocks.IRON_BLOCK);
    }

    // --- radio hill and lab

    private void radioHill(Canvas c) {
        Random r = c.random;
        clear(c, -10, -10, 10, 10, 45);
        // Lattice tower, tapering.
        int tu = 4, tw = -4;
        for (int y = 1; y <= 40; y++) {
            int half = y <= 14 ? 2 : y <= 28 ? 1 : 0;
            if (half == 0) {
                c.set(tu, y, tw, Blocks.IRON_BARS);
                continue;
            }
            for (int du : new int[]{-half, half}) {
                for (int dw : new int[]{-half, half}) {
                    c.set(tu + du, y, tw + dw, Blocks.IRON_BARS);
                }
            }
            if (y % 5 == 0) {
                for (int i = -half; i <= half; i++) {
                    c.set(tu + i, y, tw - half, Blocks.IRON_BARS);
                    c.set(tu + i, y, tw + half, Blocks.IRON_BARS);
                    c.set(tu - half, y, tw + i, Blocks.IRON_BARS);
                    c.set(tu + half, y, tw + i, Blocks.IRON_BARS);
                }
            }
        }
        c.set(tu, 41, tw, Canvas.lantern(Blocks.LANTERN, false));
        c.fill(tu - 2, 14, tw - 2, tu + 2, 14, tw + 2, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, true));
        c.fill(tu, 1, tw, tu, 14, tw, Blocks.POLISHED_ANDESITE);
        c.fill(tu, 1, tw + 1, tu, 14, tw + 1, Canvas.ladder(Direction.SOUTH));
        crate(c, tu + 1, 15, tw, military());
        // Concrete hut over the lab shaft.
        int h1 = -8, h2 = -3, g1 = -3, g2 = 2;
        c.foundation(h1, g1, h2, g2, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(h1, 0, g1, h2, 0, g2, Blocks.GRAY_CONCRETE);
        c.walls(h1, 1, g1, h2, 3, g2, CONCRETE, 0.03);
        c.air(h1 + 1, 1, g1 + 1, h2 - 1, 3, g2 - 1);
        c.fill(h1, 4, g1, h2, 4, g2, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
        c.air(h2, 1, 0, h2, 2, 0);
        c.door(h2, 1, 0, Blocks.IRON_DOOR, Direction.EAST, true);
        crate(c, h1 + 1, 1, g2 - 1, common());
        c.set(h1 + 3, 3, g1 + 2, Canvas.lantern(Blocks.LANTERN, true));
        c.wallSign(h2 + 1, 3, 1, Direction.EAST, "Лаборатория", "№ 5", "Вход только", "с допуском");
        // Lab under the hill: 12 blocks down a ladder shaft.
        int yb = -12;
        int lu1 = -14, lu2 = 4, lw1 = -2, lw2 = 8;
        c.fill(lu1, yb, lw1, lu2, yb + 5, lw2, DEEPSLATE);
        c.fill(lu1 + 1, yb, lw1 + 1, lu2 - 1, yb, lw2 - 1, Palette.of(Blocks.GRAY_CONCRETE, 5, Blocks.POLISHED_ANDESITE, 1));
        c.air(lu1 + 1, yb + 1, lw1 + 1, lu2 - 1, yb + 4, lw2 - 1);
        int su = -6, sw = -1;
        c.fill(su - 1, yb + 5, sw - 1, su + 1, 0, sw + 1, Blocks.POLISHED_DEEPSLATE);
        c.air(su, yb + 1, sw, su, 0, sw);
        c.fill(su, yb + 1, sw, su, 0, sw, Canvas.ladder(Direction.SOUTH));
        c.set(su, 1, sw, Canvas.trapdoor(Blocks.SPRUCE_TRAPDOOR, Direction.SOUTH, false, true));
        for (int u = lu1 + 3; u <= lu2 - 2; u += 5) {
            c.set(u, yb + 4, 3, Canvas.lantern(Blocks.LANTERN, true));
        }
        for (int u = -12; u <= -8; u++) {
            c.set(u, yb + 1, 7, Blocks.WHITE_CONCRETE);
            c.set(u, yb + 2, 7, Blocks.BLACK_STAINED_GLASS_PANE);
        }
        c.set(-7, yb + 1, 7, Blocks.CAULDRON);
        c.set(-4, yb + 1, 7, Blocks.BOOKSHELF);
        c.set(-3, yb + 1, 7, Blocks.BOOKSHELF);
        c.fill(0, yb + 1, 4, 0, yb + 4, 7, Blocks.IRON_BARS);
        c.fill(1, yb + 1, 4, 3, yb + 4, 4, Blocks.IRON_BARS);
        c.air(0, yb + 1, 5, 0, yb + 2, 5);
        c.set(2, yb + 1, 6, Blocks.SKELETON_SKULL);
        c.set(3, yb + 1, 7, Blocks.BONE_BLOCK);
        crate(c, -12, yb + 1, 0, military());
        crate(c, 2, yb + 1, 0, military());
        crate(c, -10, yb + 1, 6, medical());
        crate(c, -1, yb + 1, 6, medical());
        crate(c, 3, yb + 1, 2, common());
        c.wallSign(-9, yb + 3, 6, Direction.NORTH, "Не входить", "Биологическая", "опасность");
        // Fence around the tower.
        for (int i = -4; i <= 4; i++) {
            if (r.nextDouble() < 0.85 && i != 0) {
                c.set(tu + i, 1, tw - 4, Blocks.IRON_BARS);
                c.set(tu + i, 1, tw + 4, Blocks.IRON_BARS);
                c.set(tu - 4, 1, tw + i, Blocks.IRON_BARS);
                c.set(tu + 4, 1, tw + i, Blocks.IRON_BARS);
            }
        }
    }

    private void helipad(Canvas c) {
        clear(c, -7, -7, 7, 7, 6);
        c.fill(-6, 0, -6, 6, 0, 6, Blocks.GRAY_CONCRETE);
        for (int i = -6; i <= 6; i++) {
            c.set(i, 0, -6, Blocks.YELLOW_CONCRETE);
            c.set(i, 0, 6, Blocks.YELLOW_CONCRETE);
            c.set(-6, 0, i, Blocks.YELLOW_CONCRETE);
            c.set(6, 0, i, Blocks.YELLOW_CONCRETE);
        }
        c.fill(-2, 0, -3, -2, 0, 3, Blocks.WHITE_CONCRETE);
        c.fill(2, 0, -3, 2, 0, 3, Blocks.WHITE_CONCRETE);
        c.fill(-1, 0, 0, 1, 0, 0, Blocks.WHITE_CONCRETE);
        for (int u : new int[]{-6, 6}) {
            for (int w : new int[]{-6, 6}) {
                c.set(u, 1, w, Canvas.lantern(Blocks.SOUL_LANTERN, false));
            }
        }
        c.fill(8, 1, -8, 8, 4, -8, Blocks.IRON_BARS);
        c.set(8, 5, -8, Blocks.ORANGE_WOOL);
        c.set(7, 1, 5, military());
        c.set(7, 1, 4, Blocks.PACKED_MUD);
        c.set(7, 2, 4, Canvas.slab(Blocks.MUD_BRICK_SLAB, false));
    }

    private void swampHut(Canvas c) {
        Random r = c.random;
        clear(c, -4, -3, 4, 3, 7);
        for (int[] leg : new int[][]{{-3, -2}, {3, -2}, {-3, 2}, {3, 2}}) {
            c.pillarDown(leg[0], 0, leg[1], Canvas.log(Blocks.DARK_OAK_LOG, Direction.Axis.Y));
        }
        c.fill(-3, 1, -2, 3, 1, 2, Blocks.SPRUCE_PLANKS);
        c.walls(-3, 2, -2, 3, 4, 2, Palette.of(Blocks.DARK_OAK_PLANKS, 6, Blocks.SPRUCE_PLANKS, 2, Blocks.MOSSY_COBBLESTONE, 1), 0.1);
        c.air(-2, 2, -1, 2, 4, 1);
        c.fill(-4, 5, -3, 4, 5, 3, Canvas.slab(Blocks.SPRUCE_SLAB, false));
        c.air(0, 2, 2, 0, 3, 2);
        c.door(0, 2, 2, Blocks.SPRUCE_DOOR, Direction.SOUTH, true);
        crate(c, -2, 2, -1, common());
        crate(c, 2, 2, -1, medical());
        c.set(2, 2, 1, Blocks.CAULDRON);
        // Pier into the swamp lake.
        int deck = CordonTerrain.WATER + 1 - c.oy;
        c.set(0, deck, 3, Blocks.SPRUCE_PLANKS);
        for (int w = 3; w <= 21; w++) {
            for (int u = -1; u <= 1; u++) {
                if (r.nextDouble() < 0.08 && u != 0) {
                    continue;
                }
                c.set(u, deck, w, Blocks.SPRUCE_PLANKS);
                c.air(u, deck + 1, w, u, deck + 3, w);
            }
            if (w % 4 == 1) {
                c.pillarDown(-1, deck - 1, w, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.Y));
                c.pillarDown(1, deck - 1, w, Canvas.log(Blocks.SPRUCE_LOG, Direction.Axis.Y));
            }
        }
        c.fill(-1, deck + 1, 21, -1, deck + 2, 21, Blocks.SPRUCE_FENCE);
        c.set(-1, deck + 3, 21, Canvas.lantern(Blocks.LANTERN, false));
        c.wallSign(1, 3, 3, Direction.SOUTH, "Причал");
    }

    // --- railway

    private void railway(Painter p) {
        Canvas c = new Canvas(p, random(1, 2), 0, CordonTerrain.RAIL_Y, CordonTerrain.RAIL_Z, Rotation.NONE);
        Random r = c.random;
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
        int bx = layout.railBridgeX;
        for (int u = -152; u <= 153; u++) {
            c.air(u, 1, -2, u, 4, 2);
            boolean bridge = Math.abs(u - bx) <= 11;
            if (bridge) {
                c.fill(u, 0, -2, u, 0, 2, BRICK);
                c.set(u, 1, -2, Blocks.IRON_BARS);
                c.set(u, 1, 2, Blocks.IRON_BARS);
                if (Math.floorMod(u - bx, 4) == 0) {
                    c.fill(u, 2, -2, u, 4, -2, Blocks.IRON_BARS);
                    c.fill(u, 2, 2, u, 4, 2, Blocks.IRON_BARS);
                    for (int w = -1; w <= 1; w++) {
                        c.pillarDown(u, -1, w, Blocks.STONE_BRICKS.defaultBlockState());
                    }
                } else {
                    c.set(u, 4, -2, Blocks.IRON_BARS);
                    c.set(u, 4, 2, Blocks.IRON_BARS);
                }
            } else if ((u & 1) == 0) {
                c.fill(u, 0, -1, u, 0, 1, Canvas.log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Z));
            }
            if (r.nextDouble() > 0.02 || bridge) {
                c.set(u, 1, 0, rail);
            }
        }
        // Siding with derelict wagons.
        for (int u = -94; u <= -46; u++) {
            c.set(u, 0, 4, (u & 1) == 0 ? Canvas.log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Z) : Blocks.GRAVEL.defaultBlockState());
            c.air(u, 1, 3, u, 5, 5);
            c.set(u, 1, 4, rail);
        }
        wagon(c, -90, Blocks.BROWN_TERRACOTTA, common(), false);
        wagon(c, -77, Blocks.RED_TERRACOTTA, military(), true);
        wagon(c, -63, Blocks.GREEN_TERRACOTTA, common(), false);
        // Platform by the main road.
        int px = (int) Math.round(CordonTerrain.mainRoadX(CordonTerrain.RAIL_Z)) - 22;
        c.fill(px, 0, -5, px + 10, 1, -3, Blocks.SMOOTH_STONE);
        c.air(px, 2, -5, px + 10, 4, -3);
        c.fill(px + 3, 2, -5, px + 5, 2, -5, Canvas.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
        c.fill(px, 2, -5, px, 4, -5, Blocks.IRON_BARS);
        c.set(px, 5, -5, Blocks.WHITE_CONCRETE);
        c.wallSign(px, 5, -4, Direction.SOUTH, "Платформа", "«Кордон»");
        // Level crossing signs.
        int rx = (int) Math.round(CordonTerrain.mainRoadX(CordonTerrain.RAIL_Z));
        for (int[] s : new int[][]{{rx - 6, -4}, {rx + 6, 4}}) {
            c.fill(s[0], 1, s[1], s[0], 2, s[1], Blocks.IRON_BARS);
            c.set(s[0], 3, s[1], Blocks.RED_CONCRETE);
            c.wallSign(s[0], 3, s[1] + (s[1] < 0 ? -1 : 1), s[1] < 0 ? Direction.NORTH : Direction.SOUTH, "СТОП", "Ж/д переезд");
        }
        // East: tunnel portal with a collapse inside.
        c.fill(147, 0, -7, 160, 10, 7, STONE);
        c.fill(147, 11, -7, 160, 11, 7, Palette.of(Blocks.GRASS_BLOCK, 3, Blocks.COARSE_DIRT, 1));
        c.fill(147, 1, -5, 147, 9, 5, BRICK);
        for (int u = 147; u <= 157; u++) {
            c.air(u, 1, -3, u, 5, 3);
            c.air(u, 6, -2, u, 6, 2);
            c.air(u, 7, -1, u, 7, 1);
            c.set(u, 1, 0, rail);
        }
        for (int u = 154; u <= 157; u++) {
            for (int w = -3; w <= 3; w++) {
                for (int y = 1; y <= 7; y++) {
                    if (r.nextDouble() < 0.8 - (157 - u) * 0.1) {
                        c.set(u, y, w, RUBBLE);
                    }
                }
            }
        }
        c.set(148, 4, -3, Canvas.lantern(Blocks.LANTERN, true));
        c.set(148, 4, 3, Canvas.lantern(Blocks.LANTERN, true));
        c.set(148, 5, -3, Blocks.CHAIN);
        c.set(148, 5, 3, Blocks.CHAIN);
        c.wallSign(146, 8, 0, Direction.WEST, "Тоннель", "Выход из Зоны");
        crate(c, 150, 1, -3, military());
        // West: dead end with a buffer stop and a booth.
        c.fill(-154, 1, -1, -153, 1, 1, Blocks.STONE_BRICKS);
        c.fill(-154, 2, -1, -153, 2, 1, Blocks.RED_CONCRETE);
        c.foundation(-152, 3, -148, 6, Blocks.STONE_BRICKS.defaultBlockState());
        c.fill(-152, 0, 3, -148, 0, 6, Blocks.GRAY_CONCRETE);
        c.walls(-152, 1, 3, -148, 3, 6, BRICK, 0.05);
        c.air(-151, 1, 4, -149, 3, 5);
        c.air(-150, 1, 3, -150, 2, 3);
        c.fill(-152, 4, 3, -148, 4, 6, Canvas.slab(Blocks.STONE_BRICK_SLAB, false));
        crate(c, -151, 1, 5, common());
        c.wallSign(-148, 3, 2, Direction.NORTH, "Ж/д тупик");
    }

    private static void wagon(Canvas c, int u0, Block color, Block crate, boolean roofless) {
        c.fill(u0, 1, 3, u0 + 9, 1, 5, Blocks.BLACK_TERRACOTTA);
        c.fill(u0, 2, 3, u0 + 9, 4, 5, color);
        c.air(u0 + 1, 2, 4, u0 + 8, 4, 4);
        c.air(u0 + 4, 2, 3, u0 + 5, 3, 3);
        c.air(u0 + 4, 2, 5, u0 + 5, 3, 5);
        if (!roofless) {
            c.fill(u0, 5, 3, u0 + 9, 5, 5, Blocks.GRAY_TERRACOTTA);
        }
        crate(c, u0 + 2, 2, 4, crate);
    }

    private void roadBridge(Painter p) {
        int bx = layout.bridgeX;
        int bz = layout.bridgeZ;
        int deckY = Math.max(terrain.top(bx - 12, bz), terrain.top(bx + 12, bz));
        Canvas c = new Canvas(p, random(bx, bz), bx, deckY, bz, Rotation.NONE);
        Random r = c.random;
        Palette deck = Palette.of(Blocks.STONE_BRICKS, 4, Blocks.CRACKED_STONE_BRICKS, 2, Blocks.ANDESITE, 1, Blocks.GRAY_CONCRETE, 2);
        for (int u = -16; u <= 16; u++) {
            int ground = terrain.top(bx + u, bz);
            int dy = Math.abs(u) <= 11 ? 0 : -(Math.abs(u) - 11);
            if (Math.abs(u) > 11 && ground - deckY >= dy) {
                continue;
            }
            for (int w = -3; w <= 3; w++) {
                boolean hole = u >= 2 && u <= 3 && w <= -1 && w >= -2;
                if (!hole) {
                    c.set(u, dy, w, deck);
                    c.air(u, dy + 1, w, u, dy + 4, w);
                }
                if (Math.abs(u) >= 8) {
                    c.pillarDown(u, dy - 1, w, Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
            if (Math.abs(u) <= 11) {
                for (int w : new int[]{-3, 3}) {
                    if (r.nextDouble() < 0.82) {
                        c.set(u, 1, w, Blocks.STONE_BRICK_WALL);
                    }
                }
            }
        }
        for (int u : new int[]{-5, 0, 5}) {
            for (int w = -2; w <= 2; w++) {
                c.pillarDown(u, -1, w, Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        lampPost(c, -8, 3);
        lampPost(c, 8, -3);
        car(new Canvas(p, r, bx - 5, deckY, bz + 1, Rotation.CLOCKWISE_90));
    }

    // --- anomaly field

    private void anomalyField(Painter p) {
        Random r = random(CordonTerrain.ANOMALY_X, CordonTerrain.ANOMALY_Z);
        int[][] clusters = {{-10, -6}, {9, -8}, {-6, 11}, {12, 7}, {-14, 4}, {3, -15}, {6, 14}};
        for (int i = 0; i < clusters.length; i++) {
            int x = CordonTerrain.ANOMALY_X + clusters[i][0];
            int z = CordonTerrain.ANOMALY_Z + clusters[i][1];
            int y = terrain.top(x, z);
            Canvas c = new Canvas(p, r, x, y, z, Rotation.NONE);
            clear(c, -3, -3, 3, 3, 4);
            switch (i % 4) {
                case 0 -> {
                    // Burner: soul fire on soul soil, ringed by scorched stone.
                    for (int u = -2; u <= 2; u++) {
                        for (int w = -2; w <= 2; w++) {
                            if (u * u + w * w <= 5) {
                                c.set(u, 0, w, u * u + w * w <= 1 ? Blocks.SOUL_SOIL : Blocks.BLACKSTONE);
                            }
                        }
                    }
                    c.set(0, 1, 0, Blocks.SOUL_FIRE);
                    c.set(1, 1, 0, Blocks.SOUL_FIRE);
                }
                case 1 -> {
                    // Hot spot: magma that burns whoever walks on it.
                    for (int u = -3; u <= 3; u++) {
                        for (int w = -3; w <= 3; w++) {
                            if (u * u + w * w <= 6 && r.nextDouble() < 0.75) {
                                c.set(u, 0, w, Blocks.MAGMA_BLOCK);
                            }
                        }
                    }
                }
                case 2 -> {
                    // Jelly: webs that trap.
                    for (int u = -2; u <= 2; u++) {
                        for (int w = -2; w <= 2; w++) {
                            if (r.nextDouble() < 0.6) {
                                c.set(u, 1, w, Blocks.COBWEB);
                                if (r.nextDouble() < 0.3) {
                                    c.set(u, 2, w, Blocks.COBWEB);
                                }
                            }
                        }
                    }
                }
                default -> {
                    // Crystal growth: glowing "artifacts" around a lightning rod.
                    c.set(0, 0, 0, Blocks.CALCITE);
                    c.set(0, 1, 0, Blocks.LIGHTNING_ROD);
                    for (int[] d : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {2, 0}}) {
                        c.set(d[0], 0, d[1], Blocks.AMETHYST_BLOCK);
                        c.set(d[0], 1, d[1], Blocks.AMETHYST_CLUSTER);
                    }
                }
            }
            if (!anomalyBlocks.isEmpty()) {
                c.set(0, 1, -2, anomalyBlocks.get(i % anomalyBlocks.size()));
            }
        }
        // Dead stalker's stash in the middle of the field.
        int x = CordonTerrain.ANOMALY_X;
        int z = CordonTerrain.ANOMALY_Z;
        Canvas c = new Canvas(p, r, x, terrain.top(x, z), z, Rotation.NONE);
        clear(c, -3, -3, 3, 3, 4);
        c.set(0, 1, 0, Blocks.SKELETON_SKULL);
        c.set(1, 1, 0, Canvas.log(Blocks.BONE_BLOCK, Direction.Axis.X));
        c.set(-1, 1, 1, military());
        c.set(1, 1, -1, military());
        c.set(-1, 1, -1, medical());
        c.fill(-2, 1, 2, 2, 1, 2, Blocks.BROWN_CARPET);
        // Warning sign at the edge, towards the main road.
        int sx = CordonTerrain.ANOMALY_X - 24;
        Canvas s = new Canvas(p, r, sx, terrain.top(sx, z), z, Rotation.NONE);
        s.fill(0, 1, 0, 0, 2, 0, Blocks.SPRUCE_FENCE);
        s.set(0, 3, 0, Blocks.SPRUCE_PLANKS);
        s.wallSign(-1, 3, 0, Direction.WEST, "Аномалии!", "Не подходить", "без болтов");
    }

    // --- small props

    private void car(Canvas c) {
        Random r = c.random;
        Block[] colors = {Blocks.RED_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.GREEN_TERRACOTTA,
                Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.BROWN_TERRACOTTA};
        Palette body = Palette.of(colors[r.nextInt(colors.length)], 4, Blocks.ORANGE_TERRACOTTA, 1);
        c.air(-1, 1, -2, 1, 3, 2);
        c.fill(-1, 1, -2, 1, 1, 2, body);
        for (int u : new int[]{-1, 1}) {
            for (int w : new int[]{-2, 2}) {
                c.set(u, 1, w, Blocks.BLACK_CONCRETE);
            }
        }
        for (int w = -1; w <= 1; w++) {
            c.set(-1, 2, w, r.nextBoolean() ? Blocks.GRAY_STAINED_GLASS_PANE.defaultBlockState() : Painter.air());
            c.set(1, 2, w, r.nextBoolean() ? Blocks.GRAY_STAINED_GLASS_PANE.defaultBlockState() : Painter.air());
        }
        c.fill(-1, 2, 2, 1, 2, 2, body);
        if (r.nextDouble() < 0.7) {
            c.fill(-1, 3, -1, 1, 3, 1, body);
        }
    }

    private void powerLine(Painter p) {
        Random r = random(5, 5);
        for (int z = -150; z <= 150; z += 22) {
            int x = (int) Math.round(CordonTerrain.mainRoadX(z) + 7);
            if (terrain.isReserved(x, z, 2)) {
                continue;
            }
            int top = terrain.top(x, z);
            boolean broken = r.nextDouble() < 0.15;
            int height = broken ? 4 : 8;
            for (int y = top + 1; y <= top + height; y++) {
                p.set(x, y, z, Canvas.log(Blocks.DARK_OAK_LOG, Direction.Axis.Y));
            }
            if (!broken) {
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx != 0) {
                        p.set(x + dx, top + 8, z, Canvas.log(Blocks.DARK_OAK_LOG, Direction.Axis.X));
                    }
                }
                p.set(x - 2, top + 9, z, Blocks.LIGHTNING_ROD.defaultBlockState());
                p.set(x + 2, top + 9, z, Blocks.LIGHTNING_ROD.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------------ scatter

    private void scatterChunk(Painter p, int cx, int cz) {
        Column col = new Column();
        int cell = 7;
        int x0 = cx << 4;
        int z0 = cz << 4;
        for (int gx = Math.floorDiv(x0, cell); gx <= Math.floorDiv(x0 + 15, cell); gx++) {
            for (int gz = Math.floorDiv(z0, cell); gz <= Math.floorDiv(z0 + 15, cell); gz++) {
                int x = gx * cell + (int) (Noise.hash(CordonTerrain.SEED + 1, gx, gz) * cell);
                int z = gz * cell + (int) (Noise.hash(CordonTerrain.SEED + 2, gx, gz) * cell);
                if (x < x0 || x > x0 + 15 || z < z0 || z > z0 + 15 || Math.max(Math.abs(x), Math.abs(z)) > CordonTerrain.OUTER - 3) {
                    continue;
                }
                scatter(p, x, z, col, new Random(CordonTerrain.SEED ^ (gx * 73856093L) ^ (gz * 19349663L)));
            }
        }
    }

    private void scatter(Painter p, int x, int z, Column col, Random r) {
        terrain.sample(x, z, col);
        if (col.top < CordonTerrain.WATER || col.roadDistance < 2.5 || Math.abs(x - CordonTerrain.riverX(z)) < 10
                || Math.abs(z - CordonTerrain.RAIL_Z) < 6 || terrain.isReserved(x, z, 3) || nearPoint(x, z, 6)) {
            return;
        }
        int edge = Math.max(Math.abs(x), Math.abs(z));
        if (Math.abs(edge - CordonTerrain.WALL) < 4) {
            return;
        }
        int y = col.top;
        double roll = r.nextDouble();
        boolean outside = edge > CordonTerrain.WALL;
        switch (col.surface) {
            case SWAMP -> {
                if (roll < 0.35) {
                    tree(p, r, x, y, z, true);
                } else if (roll < 0.5) {
                    bush(p, r, x, y, z);
                }
            }
            case ANOMALY -> {
                if (roll < 0.25) {
                    tree(p, r, x, y, z, true);
                } else if (roll < 0.35) {
                    p.set(x, y + 1, z, Canvas.log(Blocks.BONE_BLOCK, r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z));
                } else if (roll < 0.42) {
                    rock(p, r, x, y, z);
                }
            }
            case NORMAL, BANK -> {
                double trees = outside ? 0.6 : 0.3;
                if (roll < trees) {
                    tree(p, r, x, y, z, r.nextDouble() < (outside ? 0.2 : 0.35));
                } else if (roll < trees + 0.15) {
                    bush(p, r, x, y, z);
                } else if (roll < trees + 0.21) {
                    rock(p, r, x, y, z);
                } else if (!outside && roll < trees + 0.23) {
                    junk(p, r, x, y, z);
                }
            }
            default -> {
            }
        }
    }

    private boolean nearPoint(int x, int z, int radius) {
        for (CordonLayout.Spawn s : layout.spawns) {
            if (Math.abs(s.x() - x) <= radius && Math.abs(s.z() - z) <= radius) {
                return true;
            }
        }
        for (CordonLayout.Extract e : layout.extracts) {
            if (Math.abs(e.x() - x) <= radius + e.radius() && Math.abs(e.z() - z) <= radius + e.radius()) {
                return true;
            }
        }
        return false;
    }

    private static void tree(Painter p, Random r, int x, int y, int z, boolean dead) {
        Block log = switch (r.nextInt(4)) {
            case 0 -> Blocks.BIRCH_LOG;
            case 1 -> Blocks.DARK_OAK_LOG;
            default -> Blocks.SPRUCE_LOG;
        };
        int height = 5 + r.nextInt(5);
        for (int i = 1; i <= height; i++) {
            p.set(x, y + i, z, Canvas.log(log, Direction.Axis.Y));
        }
        int branches = 1 + r.nextInt(3);
        for (int b = 0; b < branches; b++) {
            Direction d = Direction.from2DDataValue(r.nextInt(4));
            int by = y + height - 1 - r.nextInt(3);
            int len = 1 + r.nextInt(2);
            for (int i = 1; i <= len; i++) {
                p.set(x + d.getStepX() * i, by + (i == len ? 1 : 0), z + d.getStepZ() * i, Canvas.log(log, d.getAxis()));
            }
        }
        if (dead) {
            return;
        }
        BlockState leaves = Canvas.leaves(Blocks.OAK_LEAVES);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int dist = dx * dx + dz * dz + dy * dy * 2;
                    if (dist <= 5 && r.nextDouble() < 0.6 && !(dx == 0 && dz == 0 && dy <= 0)) {
                        int lx = x + dx, ly = y + height + dy, lz = z + dz;
                        if (p.get(lx, ly, lz).isAir()) {
                            p.set(lx, ly, lz, leaves);
                        }
                    }
                }
            }
        }
    }

    private static void bush(Painter p, Random r, int x, int y, int z) {
        BlockState leaves = Canvas.leaves(r.nextBoolean() ? Blocks.OAK_LEAVES : Blocks.AZALEA_LEAVES);
        p.set(x, y + 1, z, leaves);
        for (int i = 0; i < 3; i++) {
            int dx = r.nextInt(3) - 1;
            int dz = r.nextInt(3) - 1;
            if (p.get(x + dx, y + 1, z + dz).canBeReplaced()) {
                p.set(x + dx, y + 1, z + dz, leaves);
            }
        }
        if (r.nextBoolean()) {
            p.set(x, y + 2, z, leaves);
        }
    }

    private static void rock(Painter p, Random r, int x, int y, int z) {
        Palette rock = Palette.of(Blocks.COBBLESTONE, 2, Blocks.MOSSY_COBBLESTONE, 3, Blocks.ANDESITE, 2, Blocks.TUFF, 1);
        p.set(x, y + 1, z, rock.pick(r));
        if (r.nextBoolean()) {
            p.set(x + 1, y + 1, z, rock.pick(r));
        }
        if (r.nextDouble() < 0.3) {
            p.set(x, y + 2, z, rock.pick(r));
        }
    }

    private static void junk(Painter p, Random r, int x, int y, int z) {
        switch (r.nextInt(3)) {
            case 0 -> {
                p.set(x, y + 1, z, Blocks.SKELETON_SKULL.defaultBlockState());
                p.set(x + 1, y + 1, z, Canvas.log(Blocks.BONE_BLOCK, Direction.Axis.X));
            }
            case 1 -> {
                p.set(x, y + 1, z, Blocks.SMOOTH_STONE.defaultBlockState());
                p.set(x, y + 2, z, Canvas.slab(Blocks.SMOOTH_STONE_SLAB, false));
            }
            default -> {
                p.set(x, y + 1, z, Blocks.IRON_BARS.defaultBlockState());
                p.set(x + 1, y + 1, z, Blocks.IRON_BARS.defaultBlockState());
                p.set(x + 2, y + 1, z, Blocks.IRON_BARS.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------------ markers

    private void markers(Painter p) {
        for (CordonLayout.Spawn s : layout.spawns) {
            int top = terrain.top(s.x(), s.z());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int y = top + 1; y <= top + 3; y++) {
                        p.set(s.x() + dx, y, s.z() + dz, Painter.air());
                    }
                }
            }
        }
        for (CordonLayout.Extract e : layout.extracts) {
            int x = e.markerX();
            int z = e.markerZ();
            int y = e.y();
            if (e.markerX() != e.x() || e.markerZ() != e.z()) {
                y = e.y() == terrain.top(e.x(), e.z()) + 1 ? terrain.top(x, z) + 1 : e.y();
            }
            p.set(x, y, z, Blocks.POLISHED_ANDESITE.defaultBlockState());
            p.set(x, y + 1, z, Blocks.POLISHED_ANDESITE.defaultBlockState());
            p.set(x, y + 2, z, Canvas.lantern(Blocks.SOUL_LANTERN, false));
            p.pillarDown(x, y - 1, z, Blocks.STONE_BRICKS.defaultBlockState(), 6);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int sx = x + d.getStepX();
                int sz = z + d.getStepZ();
                if (p.isOpen(sx, y + 1, sz)) {
                    p.sign(sx, y + 1, sz, Blocks.SPRUCE_WALL_SIGN.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.WallSignBlock.FACING, d), "ВЫХОД", e.name());
                }
            }
        }
    }
}
