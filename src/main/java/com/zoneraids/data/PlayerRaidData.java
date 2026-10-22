package com.zoneraids.data;

import com.zoneraids.ZoneRaids;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Per-player data kept in the player's own save file (Forge copies the persisted tag across death),
 * so nothing about offline players is held in memory.
 */
public final class PlayerRaidData {
    public enum Stat {
        RAIDS, SURVIVED, KIA, MIA, KILLS;

        final String key = name().toLowerCase();
    }

    private static final String SESSION = "Session";
    private static final String RETURN_TO_HUB = "ReturnToHub";
    private static final String STATS = "Stats";
    public static final String STASH = "Stash";

    private PlayerRaidData() {
    }

    public static CompoundTag root(Player player) {
        CompoundTag data = player.getPersistentData();
        CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            data.put(Player.PERSISTED_NBT_TAG, persisted);
        }
        CompoundTag mod = persisted.getCompound(ZoneRaids.MODID);
        if (!persisted.contains(ZoneRaids.MODID, Tag.TAG_COMPOUND)) {
            persisted.put(ZoneRaids.MODID, mod);
        }
        return mod;
    }

    @Nullable
    public static UUID getSession(Player player) {
        CompoundTag root = root(player);
        return root.hasUUID(SESSION) ? root.getUUID(SESSION) : null;
    }

    public static void setSession(Player player, @Nullable UUID session) {
        CompoundTag root = root(player);
        if (session == null) {
            root.remove(SESSION);
        } else {
            root.putUUID(SESSION, session);
        }
    }

    public static boolean consumeReturnToHub(Player player) {
        CompoundTag root = root(player);
        boolean flag = root.getBoolean(RETURN_TO_HUB);
        root.remove(RETURN_TO_HUB);
        return flag;
    }

    public static void markReturnToHub(Player player) {
        root(player).putBoolean(RETURN_TO_HUB, true);
    }

    public static int getStat(Player player, Stat stat) {
        return root(player).getCompound(STATS).getInt(stat.key);
    }

    public static void incrementStat(Player player, Stat stat) {
        CompoundTag root = root(player);
        CompoundTag stats = root.getCompound(STATS);
        stats.putInt(stat.key, stats.getInt(stat.key) + 1);
        root.put(STATS, stats);
    }
}
