package com.zoneraids.raid;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.RaidMap;
import com.zoneraids.data.SpawnPoint;
import com.zoneraids.perk.PerkService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Objectives of the non-extraction parts of a session: the supply drop, the emission and deathmatch
 * safe zone, and the documents. All state lives in the {@link RaidSession}.
 */
final class RoundObjectives {
    private static final String DOCUMENTS_TAG = "ZoneRaidsDocuments";
    private static final double EMISSION_END_RADIUS = 18.0;
    private static final double DEATHMATCH_END_RADIUS = 14.0;
    private static final int DOCS_REISSUE_TICKS = 15 * 20;

    record Objective(boolean hasTarget, double x, double z, Component label, Component status, float progress) {
        static final Objective NONE = new Objective(false, 0, 0, Component.empty(), Component.empty(), -1.0F);
    }

    private RoundObjectives() {
    }

    // ------------------------------------------------------------------ setup

    /** Called once the round's players are on the map. */
    static void setup(MinecraftServer server, ServerLevel level, RaidSession s, Random random) {
        s.drop = null;
        s.dropGroup = -1;
        s.dropTicks = 0;
        s.dropTaken = false;
        s.docsHolder = null;
        s.docsKnown = false;
        s.docsLostTicks = 0;

        double[] area = playArea(s.map);
        double cx = area[0];
        double cz = area[1];
        double hx = area[2];
        double hz = area[3];
        switch (s.type) {
            case EMISSION -> {
                s.zoneX = cx + (random.nextDouble() * 2 - 1) * hx * 0.35;
                s.zoneZ = cz + (random.nextDouble() * 2 - 1) * hz * 0.35;
                s.zoneStartRadius = farthestCorner(s.zoneX, s.zoneZ, area);
                s.zoneEndRadius = EMISSION_END_RADIUS;
            }
            case DEATHMATCH -> {
                s.zoneX = cx;
                s.zoneZ = cz;
                s.zoneStartRadius = farthestCorner(cx, cz, area);
                s.zoneEndRadius = DEATHMATCH_END_RADIUS;
            }
            case SUPPLY_DROP -> s.drop = findDropSpot(level, cx, cz, hx, hz, random);
            case DOCUMENTS -> {
                List<ServerPlayer> online = onlineParticipants(server, level, s);
                if (!online.isEmpty()) {
                    giveDocuments(online.get(random.nextInt(online.size())), s, server);
                }
            }
            default -> {
            }
        }
    }

    /** Centre and half size of the play area: the map bounds, or the spread of the spawn points. */
    private static double[] playArea(RaidMap map) {
        if (map.bounded) {
            return new double[]{(map.minX + map.maxX + 1) / 2.0, (map.minZ + map.maxZ + 1) / 2.0,
                    (map.maxX - map.minX + 1) / 2.0, (map.maxZ - map.minZ + 1) / 2.0};
        }
        double x = 0;
        double z = 0;
        for (SpawnPoint spawn : map.spawns) {
            x += spawn.pos().getX() + 0.5;
            z += spawn.pos().getZ() + 0.5;
        }
        x /= Math.max(1, map.spawns.size());
        z /= Math.max(1, map.spawns.size());
        double half = 32;
        for (SpawnPoint spawn : map.spawns) {
            half = Math.max(half, Math.max(Math.abs(spawn.pos().getX() + 0.5 - x), Math.abs(spawn.pos().getZ() + 0.5 - z)));
        }
        return new double[]{x, z, half, half};
    }

    private static double farthestCorner(double x, double z, double[] area) {
        double dx = Math.abs(x - area[0]) + area[2];
        double dz = Math.abs(z - area[1]) + area[3];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** A spot on open ground (not a roof) in the middle part of the map. */
    private static BlockPos findDropSpot(ServerLevel level, double cx, double cz, double hx, double hz, Random random) {
        BlockPos fallback = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = (int) Math.floor(cx + (random.nextDouble() * 2 - 1) * hx * 0.6);
            int z = (int) Math.floor(cz + (random.nextDouble() * 2 - 1) * hz * 0.6);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight() + 1) {
                continue;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockState ground = level.getBlockState(pos.below());
            if (!ground.getFluidState().isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = pos;
            }
            if (ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(Blocks.GRAVEL) || ground.is(Blocks.COARSE_DIRT)) {
                return pos;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        int x = (int) Math.floor(cx);
        int z = (int) Math.floor(cz);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    // ------------------------------------------------------------------ ticking

    /** Runs every 10 ticks while a round is being played. */
    static void tick(MinecraftServer server, ServerLevel level, RaidSession s, int dt, long tick, Random random) {
        boolean second = tick % 20 == 0;
        switch (s.type) {
            case SUPPLY_DROP -> tickDrop(server, level, s, dt, second);
            case DOCUMENTS -> {
                if (second) {
                    tickDocuments(server, level, s, random);
                }
            }
            case EMISSION, DEATHMATCH -> {
                if (second) {
                    tickZone(server, level, s);
                }
            }
            default -> {
            }
        }
    }

    private static void tickDrop(MinecraftServer server, ServerLevel level, RaidSession s, int dt, boolean second) {
        BlockPos drop = s.drop;
        if (drop == null || s.dropTaken) {
            return;
        }
        if (second) {
            // Signal smoke rises high and is sent with the long-distance flag, so the drop can be seen from afar.
            for (ServerPlayer player : onlineParticipants(server, level, s)) {
                level.sendParticles(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, drop.getX() + 0.5, drop.getY() + 0.5,
                        drop.getZ() + 0.5, 3, 0.2, 0.4, 0.2, 0.01);
            }
        }
        int radius = RaidConfig.DROP_RADIUS.get();
        Set<Integer> groupsInside = new HashSet<>();
        List<ServerPlayer> inside = new ArrayList<>();
        for (ServerPlayer player : onlineParticipants(server, level, s)) {
            double dx = player.getX() - (drop.getX() + 0.5);
            double dz = player.getZ() - (drop.getZ() + 0.5);
            if (dx * dx + dz * dz <= radius * radius && Math.abs(player.getY() - drop.getY()) <= 6) {
                groupsInside.add(s.groupOf.getOrDefault(player.getUUID(), -1));
                inside.add(player);
            }
        }
        if (groupsInside.isEmpty()) {
            s.dropTicks = Math.max(0, s.dropTicks - dt);
            return;
        }
        if (groupsInside.size() > 1) {
            if (second) {
                Component msg = Component.translatable("zoneraids.round.drop.contested").withStyle(ChatFormatting.RED);
                inside.forEach(p -> p.displayClientMessage(msg, true));
            }
            return;
        }
        int group = groupsInside.iterator().next();
        if (group != s.dropGroup) {
            s.dropGroup = group;
            s.dropTicks = 0;
        }
        s.dropTicks += dt;
        if (s.dropTicks < RaidConfig.DROP_CAPTURE_SECONDS.get() * 20) {
            return;
        }
        s.dropTaken = true;
        spawnDropLoot(level, drop);
        for (ServerPlayer player : inside) {
            PerkService.award(player, 1, "zoneraids.perk.reason.drop");
        }
        Component msg = Component.translatable("zoneraids.round.drop.taken", inside.get(0).getDisplayName()).withStyle(ChatFormatting.GOLD);
        onlineParticipants(server, level, s).forEach(p -> p.sendSystemMessage(msg));
    }

    private static void spawnDropLoot(ServerLevel level, BlockPos drop) {
        ResourceLocation id = ResourceLocation.tryParse(RaidConfig.DROP_LOOT_TABLE.get());
        if (id == null) {
            return;
        }
        LootTable table = level.getServer().getLootData().getLootTable(id);
        Vec3 origin = Vec3.atCenterOf(drop);
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, origin).create(LootContextParamSets.CHEST);
        for (int i = 0; i < RaidConfig.DROP_LOOT_ROLLS.get(); i++) {
            for (ItemStack stack : table.getRandomItems(params)) {
                ItemEntity item = new ItemEntity(level, origin.x, origin.y, origin.z, stack);
                item.setDefaultPickUpDelay();
                level.addFreshEntity(item);
            }
        }
    }

    private static void tickDocuments(MinecraftServer server, ServerLevel level, RaidSession s, Random random) {
        for (ServerPlayer player : onlineParticipants(server, level, s)) {
            if (hasDocuments(player)) {
                s.docsHolder = player.getUUID();
                s.docsX = player.getX();
                s.docsZ = player.getZ();
                s.docsKnown = true;
                s.docsLostTicks = 0;
                player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
                return;
            }
        }
        s.docsHolder = null;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && isDocuments(item.getItem())) {
                item.setGlowingTag(true);
                s.docsX = item.getX();
                s.docsZ = item.getZ();
                s.docsKnown = true;
                s.docsLostTicks = 0;
                return;
            }
        }
        // Burned, fell out of the world or hidden in a container: issue a new copy.
        s.docsKnown = false;
        s.docsLostTicks += 20;
        if (s.docsLostTicks >= DOCS_REISSUE_TICKS) {
            s.docsLostTicks = 0;
            List<ServerPlayer> online = onlineParticipants(server, level, s);
            if (!online.isEmpty()) {
                giveDocuments(online.get(random.nextInt(online.size())), s, server);
            }
        }
    }

    private static void giveDocuments(ServerPlayer player, RaidSession s, MinecraftServer server) {
        ItemStack docs = new ItemStack(Items.PAPER);
        docs.getOrCreateTag().putBoolean(DOCUMENTS_TAG, true);
        docs.setHoverName(Component.translatable("zoneraids.round.documents.item").withStyle(ChatFormatting.GOLD));
        if (!player.getInventory().add(docs)) {
            player.drop(docs, false);
        }
        s.docsHolder = player.getUUID();
        Component msg = Component.translatable("zoneraids.round.documents.given", player.getDisplayName()).withStyle(ChatFormatting.GOLD);
        for (UUID id : s.participants.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.sendSystemMessage(msg);
            }
        }
    }

    static boolean isDocuments(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(DOCUMENTS_TAG);
    }

    static boolean hasDocuments(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isDocuments(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return isDocuments(player.containerMenu.getCarried());
    }

    /** Removes every copy of the documents the player carries. @return true if there was one. */
    static boolean stripDocuments(ServerPlayer player) {
        boolean found = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isDocuments(player.getInventory().getItem(i))) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
                found = true;
            }
        }
        if (isDocuments(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            found = true;
        }
        return found;
    }

    private static void tickZone(MinecraftServer server, ServerLevel level, RaidSession s) {
        double radius = s.zoneRadius();
        double damage = RaidConfig.ZONE_DAMAGE.get();
        Component warning = Component.translatable(s.type == RoundType.EMISSION ? "zoneraids.round.emission.outside"
                : "zoneraids.round.deathmatch.outside").withStyle(ChatFormatting.RED);
        for (RaidSession.Participant part : new ArrayList<>(s.participants.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(part.id);
            if (player == null || !player.isAlive() || player.level() != level) {
                continue;
            }
            double dx = player.getX() - s.zoneX;
            double dz = player.getZ() - s.zoneZ;
            if (dx * dx + dz * dz <= radius * radius) {
                part.outsideTicks = 0;
                continue;
            }
            part.outsideTicks += 20;
            player.displayClientMessage(warning, true);
            if (damage > 0) {
                // Grows by the base amount every 10 seconds spent outside.
                player.hurt(level.damageSources().magic(), (float) (damage * (1 + part.outsideTicks / 200)));
            }
        }
    }

    // ------------------------------------------------------------------ HUD

    static Objective objective(RaidSession s, ServerPlayer viewer) {
        return switch (s.type) {
            case SUPPLY_DROP -> {
                if (s.drop == null) {
                    yield Objective.NONE;
                }
                if (s.dropTaken) {
                    yield new Objective(false, 0, 0, Component.empty(), Component.translatable("zoneraids.hud.drop_taken"), -1.0F);
                }
                int needed = RaidConfig.DROP_CAPTURE_SECONDS.get() * 20;
                boolean capturing = s.dropTicks > 0;
                yield new Objective(true, s.drop.getX() + 0.5, s.drop.getZ() + 0.5, Component.translatable("zoneraids.hud.drop"),
                        capturing ? Component.translatable("zoneraids.hud.drop_capture", s.dropTicks * 100 / needed)
                                : Component.translatable("zoneraids.hud.drop_hold", RaidConfig.DROP_CAPTURE_SECONDS.get()),
                        capturing ? Math.min(1.0F, s.dropTicks / (float) needed) : -1.0F);
            }
            case DOCUMENTS -> {
                if (viewer.getUUID().equals(s.docsHolder)) {
                    yield new Objective(false, 0, 0, Component.empty(), Component.translatable("zoneraids.hud.docs_yours"), -1.0F);
                }
                yield new Objective(s.docsKnown, s.docsX, s.docsZ, Component.translatable("zoneraids.hud.docs"),
                        s.docsKnown ? Component.empty() : Component.translatable("zoneraids.hud.docs_lost"), -1.0F);
            }
            case EMISSION, DEATHMATCH -> {
                int radius = (int) s.zoneRadius();
                double dx = viewer.getX() - s.zoneX;
                double dz = viewer.getZ() - s.zoneZ;
                boolean outside = dx * dx + dz * dz > radius * (double) radius;
                Component status = s.type == RoundType.DEATHMATCH
                        ? Component.translatable("zoneraids.hud.alive", s.participants.size(), radius)
                        : Component.translatable("zoneraids.hud.zone", radius);
                if (outside) {
                    status = Component.translatable("zoneraids.hud.outside").withStyle(ChatFormatting.RED);
                }
                yield new Objective(true, s.zoneX, s.zoneZ, Component.translatable(s.type == RoundType.EMISSION
                        ? "zoneraids.hud.shelter" : "zoneraids.hud.zone_center"), status, -1.0F);
            }
            default -> Objective.NONE;
        };
    }

    static List<ServerPlayer> onlineParticipants(MinecraftServer server, @Nullable ServerLevel level, RaidSession s) {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : s.participants.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.isAlive() && (level == null || player.level() == level)) {
                list.add(player);
            }
        }
        return list;
    }
}
