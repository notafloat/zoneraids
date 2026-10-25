package com.zoneraids.stash;

import com.zoneraids.RaidConfig;
import com.zoneraids.ZoneRaids;
import com.zoneraids.block.HiddenStashBlockEntity;
import com.zoneraids.data.RaidMap;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.StashMarkerPacket;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.raid.RaidManager;
import com.zoneraids.raid.RaidSession;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.survival.Lairs;
import com.zoneraids.world.ZoneDimensions;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Flash drives with stash coordinates. Human enemies (anything that is not one of Zone Raids' mutants) drop
 * them now and then, a bandit leader always; reading one hides a stash somewhere around the reader, inside
 * the raid map's bounds during a raid, and puts it on the reader's PDA map.
 */
public final class StashDrives {
    private static final int ATTEMPTS = 24;
    /** On a raid map without bounds the stash stays this close, whatever the survival distances say. */
    private static final int RAID_MAX_DISTANCE = 150;

    private StashDrives() {
    }

    public static void addDrops(LivingEntity entity, Collection<ItemEntity> drops) {
        CompoundTag data = entity.getPersistentData();
        boolean boss = data.contains(Lairs.TAG_BOSS);
        if ((!boss && !data.contains(EnemySpawner.TAG_KIND)) || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        double chance = RaidConfig.STASH_DRIVE_CHANCE.get();
        if (id == null || id.getNamespace().equals(ZoneRaids.MODID) || chance <= 0) {
            return;
        }
        if (boss || level.random.nextDouble() < chance) {
            drops.add(new ItemEntity(level, entity.getX(), entity.getY() + 0.5, entity.getZ(), new ItemStack(ModRegistry.STASH_DRIVE.get())));
        }
    }

    /** Reads a drive: hides the stash and marks it. False (the drive is kept) if there is nowhere to put it. */
    public static boolean reveal(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (level.dimension().equals(ZoneDimensions.HUB) || level.dimensionType().hasCeiling()) {
            player.displayClientMessage(Component.translatable("zoneraids.stash_drive.no_signal").withStyle(ChatFormatting.RED), true);
            return false;
        }
        RaidManager manager = RaidManager.get();
        RaidSession session = manager == null ? null : manager.sessionOf(player.getUUID());
        RaidMap map = session != null && session.map().dimension.equals(level.dimension()) ? session.map() : null;
        BlockPos pos = pickSpot(level, player, map);
        if (pos == null) {
            player.displayClientMessage(Component.translatable("zoneraids.stash_drive.no_spot").withStyle(ChatFormatting.RED), true);
            return false;
        }
        level.setBlock(pos, ModRegistry.HIDDEN_STASH.get().defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof HiddenStashBlockEntity stash) {
            ResourceLocation table = ResourceLocation.tryParse(RaidConfig.STASH_LOOT_TABLE.get());
            if (table != null) {
                stash.setLootTable(table, level.random.nextLong());
            }
            stash.setRaid(map != null ? session.id() : null);
        }
        RaidNetwork.sendTo(player, new StashMarkerPacket(pos.getX(), pos.getZ()));
        int distance = (int) Math.sqrt(horizontalDistanceSqr(player, pos));
        player.sendSystemMessage(Component.translatable("zoneraids.stash_drive.read", pos.getX(), pos.getY(), pos.getZ(), distance)
                .withStyle(ChatFormatting.GREEN));
        level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BIT.get(), SoundSource.PLAYERS, 0.6F, 1.6F);
        return true;
    }

    @Nullable
    private static BlockPos pickSpot(ServerLevel level, ServerPlayer player, @Nullable RaidMap map) {
        RandomSource random = level.random;
        int max = Math.max(RaidConfig.STASH_MIN_DISTANCE.get(), RaidConfig.STASH_MAX_DISTANCE.get());
        int min = Math.min(RaidConfig.STASH_MIN_DISTANCE.get(), max);
        if (map != null) {
            max = Math.min(max, RAID_MAX_DISTANCE);
            min = Math.min(min, max / 3);
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int x;
            int z;
            if (map != null && map.bounded) {
                x = map.minX + random.nextInt(map.maxX - map.minX + 1);
                z = map.minZ + random.nextInt(map.maxZ - map.minZ + 1);
            } else {
                double angle = random.nextDouble() * Math.PI * 2;
                double distance = min + random.nextDouble() * (max - min);
                x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
                z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            }
            BlockPos pos = surface(level, x, z);
            if (pos != null && (map == null || map.inBounds(x, z))) {
                return pos;
            }
        }
        return null;
    }

    /** Dry, solid ground open to the sky at x, z; loads (or generates) the chunk. */
    @Nullable
    private static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 1) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        BlockState here = level.getBlockState(pos);
        if (!ground.isFaceSturdy(level, below, Direction.UP) || !ground.getFluidState().isEmpty()
                || !here.canBeReplaced() || !here.getFluidState().isEmpty()) {
            return null;
        }
        return pos;
    }

    private static double horizontalDistanceSqr(ServerPlayer player, BlockPos pos) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }
}
