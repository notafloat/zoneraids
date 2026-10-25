package com.zoneraids.survival;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.PlayerRaidData;
import com.zoneraids.network.MapMarkerPacket;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.ShopDataPacket;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.shop.ShopCatalog;
import com.zoneraids.shop.ShopService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trader contracts in survival mode: the trader's "Contracts" tab offers three jobs, and a player takes one at
 * a time. Hunt N enemies of a kind, clear the nearest military base or ruin, kill the boss of the nearest lair,
 * or bring an artifact. Hunts count kills, places and lairs are checked every few seconds, an artifact is handed
 * over at the trader. A finished contract pays money at once (over the PDA) and brings new offers.
 * <p>
 * Everything is kept in the player's data under {@code Contracts}: the offers with the game time they were made
 * (they are renewed after {@link #OFFER_TICKS}) and the active contract.
 */
public final class Contracts {
    public enum Kind {
        HUNT, CLEAR, BOSS, FETCH
    }

    private static final String ROOT = "Contracts";
    private static final String OFFERS = "Offers";
    private static final String ACTIVE = "Active";
    private static final String MADE = "Made";
    private static final int OFFER_COUNT = 3;
    /** Offers nobody took are replaced after this long (overworld game time). */
    private static final long OFFER_TICKS = 30L * 60L * 20L;
    /** Money per enemy of a hunt; kinds not listed pay {@link #DEFAULT_BOUNTY}. */
    private static final Map<String, Integer> BOUNTY = Map.of(
            "blind_dog", 120, "flesh", 150, "bandit", 300, "snork", 400, "military", 450, "bloodsucker", 1200);
    private static final int DEFAULT_BOUNTY = 250;
    private static final int BOSS_REWARD = 6000;
    private static final double FETCH_PREMIUM = 1.6;
    private static final int FETCH_DEFAULT = 2500;
    private static final RandomSource random = RandomSource.create();

    private Contracts() {
    }

    public static boolean enabled() {
        return SurvivalMode.enabled() && RaidConfig.SURVIVAL_CONTRACTS.get() && RaidConfig.SHOP.get();
    }

    // ------------------------------------------------------------------ trader screen

    /** The active contract first, then the offers; empty when contracts are off. Renews old offers. */
    public static List<ShopDataPacket.Contract> entries(ServerPlayer player) {
        List<ShopDataPacket.Contract> list = new ArrayList<>();
        if (!enabled()) {
            return list;
        }
        CompoundTag root = root(player);
        CompoundTag active = root.getCompound(ACTIVE);
        if (!active.isEmpty() && kind(active) == null) {
            // A contract kind from another version of the mod.
            root.remove(ACTIVE);
            active = new CompoundTag();
        }
        if (!active.isEmpty()) {
            list.add(entry(player, -1, active, true));
        }
        long now = player.server.overworld().getGameTime();
        ListTag offers = root.getList(OFFERS, Tag.TAG_COMPOUND);
        if (offers.isEmpty() || now - root.getLong(MADE) > OFFER_TICKS || now < root.getLong(MADE)) {
            offers = generate(player, active);
            root.put(OFFERS, offers);
            root.putLong(MADE, now);
        }
        for (int i = 0; i < offers.size(); i++) {
            if (kind(offers.getCompound(i)) != null) {
                list.add(entry(player, i, offers.getCompound(i), false));
            }
        }
        return list;
    }

    private static ShopDataPacket.Contract entry(ServerPlayer player, int slot, CompoundTag c, boolean active) {
        Kind kind = kind(c);
        String target = c.getString("Target");
        int count = c.getInt("Count");
        Component title = switch (kind) {
            case HUNT -> Component.translatable("zoneraids.contract.hunt", enemyName(target), count);
            case CLEAR -> Component.translatable("zoneraids.contract.clear", Component.translatable(c.getString("Name")));
            case BOSS -> Component.translatable("zoneraids.contract.boss", Component.translatable(c.getString("Name")));
            case FETCH -> Component.translatable("zoneraids.contract.fetch", itemStack(target).getHoverName());
        };
        boolean carried = kind == Kind.FETCH && carried(player, target) >= 0;
        Component detail = switch (kind) {
            case HUNT -> active ? Component.translatable("zoneraids.contract.progress", c.getInt("Progress"), count)
                    : Component.translatable("zoneraids.contract.hunt_detail");
            case CLEAR, BOSS -> where(player, c.getInt("X"), c.getInt("Z"));
            case FETCH -> Component.translatable(!active ? "zoneraids.contract.fetch_detail"
                    : carried ? "zoneraids.contract.fetch_have" : "zoneraids.contract.fetch_need");
        };
        ItemStack icon = switch (kind) {
            case HUNT -> new ItemStack(Items.IRON_SWORD);
            case CLEAR -> new ItemStack(Items.MAP);
            case BOSS -> new ItemStack(Items.SKELETON_SKULL);
            case FETCH -> itemStack(target);
        };
        return new ShopDataPacket.Contract(slot, c.getInt("Id"), title, detail, c.getInt("Reward"), icon, active, active && carried);
    }

    private static Component where(ServerPlayer player, int x, int z) {
        if (player.level() != player.server.overworld()) {
            return Component.translatable("zoneraids.contract.elsewhere");
        }
        int distance = (int) Math.sqrt(Math.pow(player.getX() - x, 2) + Math.pow(player.getZ() - z, 2));
        return Component.translatable("zoneraids.contract.where", distance, Lairs.direction(player, x, z));
    }

    // ------------------------------------------------------------------ actions

    /** @param arg {@code slot:id} of the offer as the client saw it */
    public static void take(ServerPlayer player, String arg) {
        if (!enabled() || !ShopService.allowed(player)) {
            return;
        }
        CompoundTag root = root(player);
        if (!root.getCompound(ACTIVE).isEmpty()) {
            player.displayClientMessage(Component.translatable("zoneraids.contract.busy").withStyle(ChatFormatting.RED), true);
            ShopService.refresh(player);
            return;
        }
        ListTag offers = root.getList(OFFERS, Tag.TAG_COMPOUND);
        CompoundTag offer = null;
        String[] parts = arg.split(":");
        if (parts.length == 2) {
            try {
                int slot = Integer.parseInt(parts[0]);
                int id = Integer.parseInt(parts[1]);
                if (slot >= 0 && slot < offers.size() && offers.getCompound(slot).getInt("Id") == id) {
                    offer = (CompoundTag) offers.remove(slot);
                }
            } catch (NumberFormatException ignored) {
                // Malformed request: nothing to take.
            }
        }
        if (offer == null) {
            ShopService.refresh(player);
            return;
        }
        offer.putInt("Progress", 0);
        root.put(ACTIVE, offer);
        root.put(OFFERS, offers);
        Kind kind = kind(offer);
        if (kind == Kind.CLEAR || kind == Kind.BOSS) {
            RaidNetwork.sendTo(player, new MapMarkerPacket(offer.getInt("X"), offer.getInt("Z"), MapMarkerPacket.CONTRACT,
                    "zoneraids.contract.marker"));
        }
        player.sendSystemMessage(Component.translatable("zoneraids.contract.taken", entry(player, -1, offer, true).title())
                .withStyle(ChatFormatting.AQUA));
        player.playNotifySound(SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.5F, 0.8F);
        ShopService.refresh(player);
    }

    public static void abandon(ServerPlayer player) {
        CompoundTag root = root(player);
        if (!root.getCompound(ACTIVE).isEmpty()) {
            root.remove(ACTIVE);
            player.displayClientMessage(Component.translatable("zoneraids.contract.abandoned").withStyle(ChatFormatting.GRAY), true);
        }
        ShopService.refresh(player);
    }

    /** Hands over the artifact an active fetch contract wants. Only where trading is allowed. */
    public static void turnIn(ServerPlayer player) {
        if (!enabled() || !ShopService.allowed(player)) {
            return;
        }
        CompoundTag active = root(player).getCompound(ACTIVE);
        if (kind(active) != Kind.FETCH) {
            ShopService.refresh(player);
            return;
        }
        int slot = carried(player, active.getString("Target"));
        if (slot < 0) {
            player.displayClientMessage(Component.translatable("zoneraids.contract.fetch_need").withStyle(ChatFormatting.RED), true);
            ShopService.refresh(player);
            return;
        }
        player.getInventory().getItem(slot).shrink(1);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        complete(player, active);
        ShopService.refresh(player);
    }

    // ------------------------------------------------------------------ progress

    /** A player killed something: counts for a hunt of that enemy kind. */
    public static void onKill(ServerPlayer player, Entity dead) {
        if (!enabled()) {
            return;
        }
        CompoundTag active = root(player).getCompound(ACTIVE);
        String kind = dead.getPersistentData().getString(EnemySpawner.TAG_KIND);
        if (kind(active) != Kind.HUNT || kind.isEmpty() || !kind.equals(active.getString("Target"))) {
            return;
        }
        int progress = active.getInt("Progress") + 1;
        active.putInt("Progress", progress);
        int count = active.getInt("Count");
        if (progress >= count) {
            complete(player, active);
        } else {
            player.displayClientMessage(Component.translatable("zoneraids.contract.hunt_progress", enemyName(active.getString("Target")),
                    progress, count).withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** Every few seconds: a cleared place or a beaten boss finishes the contract; a site that cannot be built voids it. */
    static void second(MinecraftServer server, int seconds) {
        if (!enabled() || seconds % 5 != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag active = root(player).getCompound(ACTIVE);
            Kind kind = kind(active);
            String key = active.getString("Target");
            boolean done;
            boolean gone;
            if (kind == Kind.CLEAR) {
                PlaceData.Entry entry = PlaceData.get(server).get(key);
                done = entry != null && entry.state == PlaceData.State.CLEARED;
                gone = entry != null && entry.state == PlaceData.State.SKIPPED;
            } else if (kind == Kind.BOSS) {
                LairData.Entry entry = LairData.get(server).get(key);
                done = entry != null && entry.state == LairData.State.DEFEATED;
                gone = entry != null && entry.state == LairData.State.SKIPPED;
            } else {
                continue;
            }
            if (done) {
                complete(player, active);
            } else if (gone) {
                root(player).remove(ACTIVE);
                player.sendSystemMessage(Component.translatable("zoneraids.contract.void").withStyle(ChatFormatting.GRAY));
            }
        }
    }

    private static void complete(ServerPlayer player, CompoundTag contract) {
        int reward = contract.getInt("Reward");
        Component title = entry(player, -1, contract, true).title();
        CompoundTag root = root(player);
        root.remove(ACTIVE);
        // A finished job brings a fresh board.
        root.remove(OFFERS);
        PlayerRaidData.setMoney(player, PlayerRaidData.getMoney(player) + reward);
        player.sendSystemMessage(Component.translatable("zoneraids.contract.done", title, reward).withStyle(ChatFormatting.GREEN));
        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.0F);
    }

    // ------------------------------------------------------------------ offers

    private static ListTag generate(ServerPlayer player, CompoundTag active) {
        ListTag offers = new ListTag();
        List<Kind> kinds = new ArrayList<>(List.of(Kind.values()));
        Collections.shuffle(kinds, new java.util.Random(random.nextLong()));
        Set<String> used = new HashSet<>();
        // What the player already works on is not offered again.
        if (!active.isEmpty()) {
            used.add(active.getString("Target"));
        }
        for (Kind kind : kinds) {
            CompoundTag offer = make(player, kind, used);
            if (offer != null && offers.size() < OFFER_COUNT) {
                offers.add(offer);
            }
        }
        // Fewer places, lairs or artifacts than kinds: more hunts.
        for (int attempt = 0; offers.size() < OFFER_COUNT && attempt < 4; attempt++) {
            CompoundTag offer = make(player, Kind.HUNT, used);
            if (offer != null) {
                offers.add(offer);
            }
        }
        return offers;
    }

    @Nullable
    private static CompoundTag make(ServerPlayer player, Kind kind, Set<String> used) {
        ServerLevel overworld = player.server.overworld();
        boolean here = player.level() == overworld;
        double multiplier = RaidConfig.SURVIVAL_CONTRACT_REWARD_MULTIPLIER.get();
        CompoundTag c = new CompoundTag();
        c.putString("Kind", kind.name());
        c.putInt("Id", random.nextInt(Integer.MAX_VALUE - 1) + 1);
        switch (kind) {
            case HUNT -> {
                List<String> kinds = new ArrayList<>(huntKinds());
                kinds.removeAll(used);
                if (kinds.isEmpty()) {
                    return null;
                }
                String target = kinds.get(random.nextInt(kinds.size()));
                int count = switch (target) {
                    case "bloodsucker" -> 1 + random.nextInt(2);
                    case "military", "snork" -> 3 + random.nextInt(2);
                    default -> 4 + random.nextInt(4);
                };
                c.putString("Target", target);
                c.putInt("Count", count);
                c.putInt("Reward", round((double) count * BOUNTY.getOrDefault(target, DEFAULT_BOUNTY) * multiplier));
            }
            case CLEAR -> {
                Places.Found place = here && Places.enabled() ? Places.nearestHostile(overworld, player.getBlockX(), player.getBlockZ()) : null;
                if (place == null || used.contains(place.key())) {
                    return null;
                }
                c.putString("Target", place.key());
                c.putString("Name", "zoneraids.place." + place.type().name().toLowerCase());
                c.putInt("X", place.pos().getX());
                c.putInt("Z", place.pos().getZ());
                int reward = switch (place.type()) {
                    case MILITARY_BASE -> 3000;
                    case RUINED_BASE -> 2000;
                    default -> 1500;
                };
                c.putInt("Reward", round(reward * multiplier));
            }
            case BOSS -> {
                Lairs.Open lair = here && Lairs.enabled() ? Lairs.nearestOpenSite(overworld, player.getBlockX(), player.getBlockZ()) : null;
                if (lair == null || used.contains(lair.key())) {
                    return null;
                }
                c.putString("Target", lair.key());
                c.putString("Name", "zoneraids.lair." + lair.type().name().toLowerCase());
                c.putInt("X", lair.pos().getX());
                c.putInt("Z", lair.pos().getZ());
                c.putInt("Reward", round(BOSS_REWARD * multiplier));
            }
            case FETCH -> {
                List<String> artifacts = new ArrayList<>();
                for (String id : RaidConfig.ARTIFACT_ITEMS.get()) {
                    ResourceLocation key = ResourceLocation.tryParse(id);
                    if (key != null && ForgeRegistries.ITEMS.containsKey(key) && !used.contains(id)) {
                        artifacts.add(id);
                    }
                }
                if (artifacts.isEmpty()) {
                    return null;
                }
                String target = artifacts.get(random.nextInt(artifacts.size()));
                ShopCatalog.SellPrice price = ShopCatalog.sellPrice(target);
                c.putString("Target", target);
                c.putInt("Reward", round((price == null ? FETCH_DEFAULT : price.price() * FETCH_PREMIUM) * multiplier));
            }
        }
        used.add(c.getString("Target"));
        return c;
    }

    /** Enemy kinds that roam in survival mode and are installed. */
    private static Set<String> huntKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        for (String line : RaidConfig.SURVIVAL_SPAWNS.get()) {
            String[] parts = line.trim().split("\\s+");
            EnemySpawner.EnemyType type = parts.length > 0 ? EnemySpawner.types().get(parts[0]) : null;
            if (type != null && type.isInstalled()) {
                kinds.add(parts[0]);
            }
        }
        return kinds;
    }

    // ------------------------------------------------------------------ helpers

    private static CompoundTag root(ServerPlayer player) {
        CompoundTag root = PlayerRaidData.root(player);
        if (!root.contains(ROOT, Tag.TAG_COMPOUND)) {
            root.put(ROOT, new CompoundTag());
        }
        return root.getCompound(ROOT);
    }

    @Nullable
    private static Kind kind(CompoundTag contract) {
        try {
            return contract.contains("Kind") ? Kind.valueOf(contract.getString("Kind")) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Component enemyName(String kind) {
        return Component.translatableWithFallback("zoneraids.contract.enemy." + kind, kind);
    }

    private static ItemStack itemStack(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return new ItemStack(item == null ? Items.BARRIER : item);
    }

    /** Inventory slot holding the item, or -1. */
    private static int carried(ServerPlayer player, String id) {
        ItemStack wanted = itemStack(id);
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(wanted.getItem()) && !wanted.is(Items.BARRIER)) {
                return i;
            }
        }
        return -1;
    }

    /** To the nearest 50, at least 50. */
    private static int round(double amount) {
        return (int) Math.max(50, Math.round(amount / 50.0) * 50);
    }
}
