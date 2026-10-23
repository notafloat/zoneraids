package com.zoneraids.data;

import com.zoneraids.ZoneRaids;
import com.zoneraids.perk.Perk;
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
    private static final String COMBAT_LOGGED = "CombatLogged";
    private static final String STATS = "Stats";
    private static final String MATCH = "Match";
    private static final String PERKS = "Perks";
    private static final String PERK_POINTS = "PerkPoints";
    private static final String MONEY = "Money";
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

    /** Left the game mid-fight in a raid: told on the next login, which takes them to the hub. */
    public static void markCombatLogged(Player player) {
        root(player).putBoolean(COMBAT_LOGGED, true);
    }

    public static boolean consumeCombatLogged(Player player) {
        CompoundTag root = root(player);
        boolean flag = root.getBoolean(COMBAT_LOGGED);
        root.remove(COMBAT_LOGGED);
        return flag;
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

    /** The multi-round session the player belongs to, kept until the session ends and its progress is reset. */
    @Nullable
    public static UUID getMatch(Player player) {
        CompoundTag root = root(player);
        return root.hasUUID(MATCH) ? root.getUUID(MATCH) : null;
    }

    public static void setMatch(Player player, @Nullable UUID match) {
        CompoundTag root = root(player);
        if (match == null) {
            root.remove(MATCH);
        } else {
            root.putUUID(MATCH, match);
        }
    }

    public static void clearStash(Player player) {
        root(player).remove(STASH);
    }

    public static int getPerk(Player player, Perk perk) {
        return Math.min(Perk.MAX_LEVEL, root(player).getCompound(PERKS).getInt(perk.id));
    }

    public static void setPerk(Player player, Perk perk, int level) {
        CompoundTag root = root(player);
        CompoundTag perks = root.getCompound(PERKS);
        if (level <= 0) {
            perks.remove(perk.id);
        } else {
            perks.putInt(perk.id, Math.min(level, Perk.MAX_LEVEL));
        }
        root.put(PERKS, perks);
    }

    public static int getPerkPoints(Player player) {
        return root(player).getInt(PERK_POINTS);
    }

    public static void setPerkPoints(Player player, int points) {
        root(player).putInt(PERK_POINTS, Math.max(0, points));
    }

    public static void addPerkPoints(Player player, int points) {
        setPerkPoints(player, getPerkPoints(player) + points);
    }

    /** Trader money. Not an item, so it cannot be dropped, looted or lost in a raid. */
    public static long getMoney(Player player) {
        return root(player).getLong(MONEY);
    }

    public static void setMoney(Player player, long money) {
        root(player).putLong(MONEY, Math.max(0, money));
    }
}
