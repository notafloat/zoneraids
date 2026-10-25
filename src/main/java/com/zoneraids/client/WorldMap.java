package com.zoneraids.client;

import com.mojang.logging.LogUtils;
import com.zoneraids.ZoneRaids;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The PDA world map's memory, client side: the terrain of every chunk the player has seen (one map colour
 * byte per block column, as on vanilla maps) and the player's own markers, per server and dimension. A
 * chunk is drawn when it arrives and the chunks around the player again every few seconds; everything is
 * kept under {@code zoneraids/worldmap} in the game folder, so the map stays explored between visits.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WorldMap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RESCAN_TICKS = 100;
    private static final int SAVE_TICKS = 6000;
    private static final int MAX_NAME = 32;

    /** Marker colours, cycled by the colour button. */
    public static final int[] COLORS = {ZoneUi.AMBER, ZoneUi.RED, ZoneUi.GREEN, 0xFF4FA3E0, 0xFFE0E0E0, 0xFFB060E0};
    /** Index in {@link #COLORS} of the markers flash drives put down. */
    public static final int STASH_COLOR = 2;

    /** A player's marker: a point with a name and one of {@link #COLORS}. */
    public static final class Marker {
        public final int x;
        public final int z;
        public int color;
        public String name;

        Marker(int x, int z, int color, String name) {
            this.x = x;
            this.z = z;
            this.color = color;
            this.name = name;
        }

        public int rgb() {
            return COLORS[Math.floorMod(color, COLORS.length)];
        }
    }

    private static final Long2ObjectMap<byte[]> chunks = new Long2ObjectOpenHashMap<>();
    private static final List<Marker> markers = new ArrayList<>();
    @Nullable
    private static Path folder;
    @Nullable
    private static ResourceKey<Level> dimension;
    private static boolean dirty;
    private static int ticks;

    private WorldMap() {
    }

    public static List<Marker> markers() {
        return markers;
    }

    public static Marker addMarker(int x, int z) {
        return addMarker(x, z, 0, "#" + (markers.size() + 1));
    }

    public static Marker addMarker(int x, int z, int color, String name) {
        Marker marker = new Marker(x, z, color, name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name);
        markers.add(marker);
        dirty = true;
        return marker;
    }

    public static void removeMarker(Marker marker) {
        markers.remove(marker);
        dirty = true;
    }

    public static void rename(Marker marker, String name) {
        marker.name = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        dirty = true;
    }

    public static void recolor(Marker marker) {
        marker.color = (marker.color + 1) % COLORS.length;
        dirty = true;
    }

    /** Colour of a block column (ABGR, for a texture), or 0 where the player has not been. */
    public static int color(int x, int z, @Nullable byte[] chunk) {
        if (chunk == null) {
            return 0;
        }
        int packed = chunk[(z & 15) * 16 + (x & 15)] & 0xFF;
        return packed == 0 ? 0 : MapColor.getColorFromPackedId(packed);
    }

    @Nullable
    public static byte[] chunk(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Switches to the current server's and dimension's map, saving the one before. */
    public static void use(Level level) {
        Path wanted = folderFor(level.dimension());
        if (wanted.equals(folder)) {
            return;
        }
        save();
        chunks.clear();
        markers.clear();
        folder = wanted;
        dimension = level.dimension();
        load();
    }

    private static Path folderFor(ResourceKey<Level> key) {
        Minecraft mc = Minecraft.getInstance();
        String server;
        if (mc.getSingleplayerServer() != null) {
            server = "local_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else if (mc.getCurrentServer() != null) {
            server = mc.getCurrentServer().ip;
        } else {
            server = "unknown";
        }
        String dim = key.location().getNamespace() + "_" + key.location().getPath();
        return FMLPaths.GAMEDIR.get().resolve("zoneraids").resolve("worldmap").resolve(safe(server)).resolve(safe(dim));
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) {
            use(chunk.getLevel());
            scan(chunk);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || mc.level == null || mc.player == null) {
            return;
        }
        ticks++;
        if (ticks % RESCAN_TICKS == 0) {
            // Blocks change (doors, broken walls, built houses): the chunks around the player are drawn again.
            use(mc.level);
            ChunkPos here = mc.player.chunkPosition();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (mc.level.hasChunk(here.x + dx, here.z + dz)) {
                        scan(mc.level.getChunk(here.x + dx, here.z + dz));
                    }
                }
            }
        }
        if (ticks % SAVE_TICKS == 0) {
            save();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        save();
        chunks.clear();
        markers.clear();
        folder = null;
        dimension = null;
    }

    /** One map colour per block column, shaded by the slope to the north (and water by its depth), as vanilla maps do. */
    private static void scan(LevelChunk chunk) {
        Level level = chunk.getLevel();
        if (!level.dimension().equals(dimension)) {
            return;
        }
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinBuildHeight();
        byte[] pixels = new byte[256];
        int[] north = new int[16];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        boolean any = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                pos.set(baseX + x, y, baseZ + z);
                if (ceiling) {
                    // Under a roof (the Nether): the first open floor below the roof.
                    y = Math.min(y, minY + 100);
                    pos.setY(y);
                    while (y > minY && !chunk.getBlockState(pos).isAir()) {
                        pos.setY(--y);
                    }
                }
                BlockState state = chunk.getBlockState(pos);
                while (y > minY && state.getMapColor(level, pos) == MapColor.NONE) {
                    pos.setY(--y);
                    state = chunk.getBlockState(pos);
                }
                MapColor color = state.getMapColor(level, pos);
                if (color == MapColor.NONE) {
                    north[x] = y;
                    continue;
                }
                MapColor.Brightness brightness;
                if (!state.getFluidState().isEmpty()) {
                    int depth = 0;
                    while (depth < 10 && y - depth > minY && !chunk.getBlockState(pos.setY(y - depth - 1)).getFluidState().isEmpty()) {
                        depth++;
                    }
                    brightness = depth < 2 ? MapColor.Brightness.HIGH : depth < 5 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
                } else {
                    int slope = z == 0 ? 0 : y - north[x];
                    brightness = slope > 0 ? MapColor.Brightness.HIGH : slope < 0 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                north[x] = y;
                pixels[z * 16 + x] = color.getPackedId(brightness);
                any = true;
            }
        }
        if (any) {
            chunks.put(chunk.getPos().toLong(), pixels);
            dirty = true;
        }
    }

    private static void load() {
        if (folder == null) {
            return;
        }
        Path terrain = folder.resolve("terrain.bin");
        if (Files.isRegularFile(terrain)) {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(terrain)))) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    long key = in.readLong();
                    byte[] pixels = new byte[256];
                    in.readFully(pixels);
                    chunks.put(key, pixels);
                }
            } catch (IOException e) {
                LOGGER.warn("Zone Raids: could not read the world map {}", terrain, e);
            }
        }
        Path list = folder.resolve("markers.txt");
        if (Files.isRegularFile(list)) {
            try {
                for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", 4);
                    if (parts.length == 4) {
                        markers.add(new Marker(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                LOGGER.warn("Zone Raids: could not read the map markers {}", list, e);
            }
        }
        dirty = false;
    }

    private static void save() {
        if (folder == null || !dirty) {
            return;
        }
        try {
            Files.createDirectories(folder);
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(folder.resolve("terrain.bin"))))) {
                out.writeInt(chunks.size());
                for (Long2ObjectMap.Entry<byte[]> entry : chunks.long2ObjectEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.write(entry.getValue());
                }
            }
            List<String> lines = new ArrayList<>();
            for (Marker marker : markers) {
                lines.add(marker.x + "\t" + marker.z + "\t" + marker.color + "\t" + marker.name.replace('\t', ' ').replace('\n', ' '));
            }
            Files.write(folder.resolve("markers.txt"), lines, StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException e) {
            LOGGER.warn("Zone Raids: could not save the world map in {}", folder, e);
        }
    }
}
