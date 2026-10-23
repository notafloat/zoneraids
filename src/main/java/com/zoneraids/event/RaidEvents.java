package com.zoneraids.event;

import com.zoneraids.RaidConfig;
import com.zoneraids.ZoneRaids;
import com.zoneraids.command.RaidCommands;
import com.zoneraids.raid.EnemySpawner;
import com.zoneraids.raid.RaidManager;
import com.zoneraids.registry.ModRegistry;
import com.zoneraids.util.Teleports;
import com.zoneraids.world.WorldBuilder;
import com.zoneraids.world.WorldSetup;
import com.zoneraids.world.ZoneDimensions;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RaidEvents {
    private static final String ANOMALY_ZONE_WELCOME = "anomalyzone_valhalla_welcome_given";

    private RaidEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        RaidManager.start(event.getServer());
        WorldSetup.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RaidManager.stop();
        WorldBuilder.clear();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        RaidCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        RaidManager manager = RaidManager.get();
        if (manager != null) {
            manager.tick();
        }
        WorldBuilder.tick(ServerLifecycleHooks.getCurrentServer());
    }

    /**
     * Anomaly Zone searches 48 chunks around a joining player for a Red Forest to put its Valhalla
     * bubble in, on the server thread, and retries on every login until it finds one. Players live
     * in the hub here, so the search never succeeds and froze the server for 20-90 s per login.
     * Marking its welcome as already given skips the search.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void skipAnomalyZoneWelcome(PlayerEvent.PlayerLoggedInEvent event) {
        CompoundTag data = event.getEntity().getPersistentData();
        CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!persisted.getBoolean(ANOMALY_ZONE_WELCOME)) {
            persisted.putBoolean(ANOMALY_ZONE_WELCOME, true);
            data.put(Player.PERSISTED_NBT_TAG, persisted);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        RaidManager manager = RaidManager.get();
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.onLogin(player);
            manager.tryLateJoin(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RaidManager manager = RaidManager.get();
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        RaidManager manager = RaidManager.get();
        if (manager != null && !event.isEndConquered() && event.getEntity() instanceof ServerPlayer player) {
            manager.onRespawn(player);
            manager.tryLateJoin(player);
        }
    }

    /** Lowest priority so totems and medical mods (e.g. First Aid) get to cancel the death first. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        RaidManager manager = RaidManager.get();
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.onDeath(player, event.getSource());
        }
    }

    /** Raid enemies whose raid is over (saved in a chunk, or across a restart) never come back. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && EnemySpawner.shouldRejectOnLoad(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            EnemySpawner.onRemoved(event.getEntity());
        }
    }

    /** Highest priority so the scaled amount is what First Aid and armor work with. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingHurtEvent event) {
        EnemySpawner.scaleDamageToPlayer(event);
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        EnemySpawner.addDrops(event.getEntity(), event.getDrops());
    }

    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        if (RaidConfig.HUB_PVP.get() || !(event.getEntity() instanceof ServerPlayer target)
                || !(event.getSource().getEntity() instanceof ServerPlayer attacker) || attacker == target) {
            return;
        }
        RaidManager manager = RaidManager.get();
        if (manager != null && !manager.isInRaid(target.getUUID()) && !manager.isInRaid(attacker.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (Teleports.isAuthorized() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        RaidManager manager = RaidManager.get();
        if (manager != null && manager.isInRaid(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (isProtected(event.getPlayer(), event.getState())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player, event.getPlacedBlock())) {
            event.setCanceled(true);
        }
    }

    private static boolean isProtected(Player player, BlockState state) {
        if (player == null || player.isCreative()) {
            return false;
        }
        if (RaidConfig.PROTECT_HUB_BLOCKS.get() && player.level().dimension().equals(ZoneDimensions.HUB)) {
            return true;
        }
        if (!RaidConfig.PROTECT_RAID_BLOCKS.get() || state.is(ModRegistry.RAID_BREAKABLE)) {
            return false;
        }
        RaidManager manager = RaidManager.get();
        return manager != null && manager.isInRaid(player.getUUID());
    }

    /** Someone watching a raid only looks: no opening crates, doors or chests on the raid map. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWatcherUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isWatching(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWatcherUseEntity(PlayerInteractEvent.EntityInteract event) {
        if (isWatching(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWatcherUseEntityAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (isWatching(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean isWatching(Player player) {
        RaidManager manager = RaidManager.get();
        return manager != null && !player.level().isClientSide() && manager.isWatching(player.getUUID());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!RaidConfig.BLOCK_ENDER_CHEST_IN_RAID.get() || event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof EnderChestBlock)) {
            return;
        }
        RaidManager manager = RaidManager.get();
        if (manager != null && manager.isInRaid(event.getEntity().getUUID())) {
            event.setCanceled(true);
            event.getEntity().displayClientMessage(Component.translatable("zoneraids.raid.ender_chest_blocked").withStyle(ChatFormatting.RED), true);
        }
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player) || player.hasPermissions(2)) {
            return;
        }
        RaidManager manager = RaidManager.get();
        if (manager == null || !manager.isInRaid(player.getUUID())) {
            return;
        }
        String input = event.getParseResults().getReader().getString().trim();
        if (input.startsWith("/")) {
            input = input.substring(1);
        }
        int space = input.indexOf(' ');
        String root = (space < 0 ? input : input.substring(0, space)).toLowerCase();
        if (RaidConfig.BLOCKED_COMMANDS_IN_RAID.get().contains(root)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.translatable("zoneraids.raid.command_blocked").withStyle(ChatFormatting.RED), true);
        }
    }
}
