package com.zoneraids;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class RaidConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue LOBBY_COUNTDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue FULL_LOBBY_COUNTDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue EXTRACT_SECONDS;
    public static final ForgeConfigSpec.IntValue SPAWN_PROTECTION_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_PARTY_SIZE;
    public static final ForgeConfigSpec.IntValue PARTY_INVITE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue HUB_PVP;
    public static final ForgeConfigSpec.BooleanValue PROTECT_RAID_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue BLOCK_ENDER_CHEST_IN_RAID;
    public static final ForgeConfigSpec.BooleanValue FORCE_DROP_ON_RAID_DEATH;
    public static final ForgeConfigSpec.BooleanValue CLEAR_DROPPED_ITEMS_ON_END;
    public static final ForgeConfigSpec.BooleanValue REMOTE_STASH;
    public static final ForgeConfigSpec.BooleanValue RETURN_GEAR_AFTER_RESTART;
    public static final ForgeConfigSpec.BooleanValue SHOW_EXTRACT_DISTANCE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCKED_COMMANDS_IN_RAID;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("matchmaking");
        LOBBY_COUNTDOWN_SECONDS = b.comment("Countdown after the queue reaches the map's minimum player count.")
                .defineInRange("lobbyCountdownSeconds", 30, 3, 600);
        FULL_LOBBY_COUNTDOWN_SECONDS = b.comment("Countdown is shortened to this value once the queue is full.")
                .defineInRange("fullLobbyCountdownSeconds", 5, 1, 600);
        MAX_PARTY_SIZE = b.defineInRange("maxPartySize", 4, 1, 16);
        PARTY_INVITE_SECONDS = b.defineInRange("partyInviteSeconds", 60, 10, 600);
        b.pop();

        b.push("raid");
        EXTRACT_SECONDS = b.comment("How long a player must stay inside an extraction zone.")
                .defineInRange("extractSeconds", 8, 1, 120);
        SPAWN_PROTECTION_SECONDS = b.defineInRange("spawnProtectionSeconds", 5, 0, 60);
        PROTECT_RAID_BLOCKS = b.comment("Players in a raid cannot break/place blocks (except #zoneraids:raid_breakable). Creative players bypass.")
                .define("protectRaidBlocks", true);
        BLOCK_ENDER_CHEST_IN_RAID = b.comment("Ender chests would let players bank loot without extracting.")
                .define("blockEnderChestInRaid", true);
        FORCE_DROP_ON_RAID_DEATH = b.comment("Drop the inventory on raid death even if the keepInventory gamerule is on.")
                .define("forceDropOnRaidDeath", true);
        CLEAR_DROPPED_ITEMS_ON_END = b.comment("Remove item entities in the map dimension when its raid ends (only if the map is not in the hub dimension).")
                .define("clearDroppedItemsOnEnd", true);
        RETURN_GEAR_AFTER_RESTART = b.comment("Players whose raid was interrupted by a server restart keep their gear. If false they are treated as MIA.")
                .define("returnGearAfterRestart", true);
        SHOW_EXTRACT_DISTANCE = b.define("showExtractDistance", true);
        BLOCKED_COMMANDS_IN_RAID = b.comment("Root commands that non-operators cannot use while in a raid.")
                .defineListAllowEmpty(List.of("blockedCommandsInRaid"),
                        List.of("home", "homes", "spawn", "tpa", "tpahere", "tpaccept", "back", "warp", "rtp", "enderchest", "ec"),
                        o -> o instanceof String);
        b.pop();

        b.push("hub");
        HUB_PVP = b.comment("Allow PvP between players that are not in a raid.").define("hubPvp", false);
        REMOTE_STASH = b.comment("Allow opening the stash from the terminal menu (outside of raids).").define("remoteStash", true);
        b.pop();

        SPEC = b.build();
    }

    private RaidConfig() {
    }
}
