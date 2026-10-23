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
    public static final ForgeConfigSpec.BooleanValue AUTO_SETUP;
    public static final ForgeConfigSpec.IntValue BUILD_MILLIS_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue FORCE_HUB_ON_LOGIN;
    public static final ForgeConfigSpec.BooleanValue PROTECT_HUB_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue GIVE_MAP_IN_HUB;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ANOMALY_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue ANOMALIES;
    public static final ForgeConfigSpec.DoubleValue ANOMALY_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue ARTIFACT_CHANCE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ARTIFACT_ITEMS;

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

        b.push("world");
        AUTO_SETUP = b.comment("On the first start, build the hub (dimension zoneraids:hub) and the Cordon map (zoneraids:cordon),",
                        "register the map with its spawns, extracts and loot crates, and set the hub. Each is built only once per world;",
                        "use /raidadmin build to rebuild.")
                .define("autoSetup", true);
        BUILD_MILLIS_PER_TICK = b.comment("Time budget per server tick for building maps, in milliseconds.")
                .defineInRange("buildMillisPerTick", 25, 1, 1000);
        FORCE_HUB_ON_LOGIN = b.comment("Send players who are not in a raid to the hub when they log in or respawn (creative/spectator players are left alone).")
                .define("forceHubOnLogin", true);
        PROTECT_HUB_BLOCKS = b.comment("Players not in creative mode cannot break or place blocks in the hub dimension.")
                .define("protectHubBlocks", true);
        GIVE_MAP_IN_HUB = b.comment("Give players in the hub a Cordon map if they have none.")
                .define("giveMapInHub", true);
        ANOMALY_BLOCKS = b.comment("Optional block ids (e.g. from an anomaly mod) placed in the Cordon's anomaly field. Ids that are not installed are skipped.")
                .defineListAllowEmpty(List.of("anomalyBlocks"), List.<String>of(), o -> o instanceof String);
        b.pop();

        b.push("anomalies");
        ANOMALIES = b.comment("Anomalies on raid maps (the Cordon has 21): they show themselves with particles and hurt players who walk in.")
                .define("enabled", true);
        ANOMALY_DAMAGE = b.comment("Multiplier for anomaly damage.")
                .defineInRange("damageMultiplier", 1.0, 0.0, 10.0);
        ARTIFACT_CHANCE = b.comment("Chance that an anomaly holds an artifact in a raid. Rolled for every anomaly at raid start.")
                .defineInRange("artifactChance", 0.45, 0.0, 1.0);
        ARTIFACT_ITEMS = b.comment("Items an anomaly can hold (one is picked at random). Ids that are not installed are skipped.")
                .defineListAllowEmpty(List.of("artifactItems"), List.of(
                        "anomalyzone:bubble", "anomalyzone:flash", "anomalyzone:hedgehog", "anomalyzone:spiral", "anomalyzone:prism",
                        "anomalyzone:snake_eye", "anomalyzone:sun", "anomalyzone:thunder", "anomalyzone:polyhedron", "anomalyzone:hoop",
                        "anomalyzone:magma_pearl", "anomalyzone:heat_stone", "anomalyzone:crust", "anomalyzone:atom"), o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private RaidConfig() {
    }
}
