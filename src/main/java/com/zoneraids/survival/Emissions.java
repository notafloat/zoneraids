package com.zoneraids.survival;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import com.zoneraids.network.MapMarkerPacket;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.stash.StashDrives;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emissions in survival mode, like the raid round of the same name but for the whole overworld: the PDA warns,
 * and a minute and a half later everyone under open sky takes damage that grows the longer they stay out, under
 * a thunderstorm. Cover is any solid block above the head (a roof, a cave, a bunker; leaves do not count).
 * When it is over, fresh artifacts lie around every survivor and the PDA marks them.
 * <p>
 * The next emission is kept in {@link EventData} in overworld game time, so a restart does not reset the wait;
 * one in progress when the server stops is simply over.
 */
public final class Emissions {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Seconds under open sky before the damage starts, for someone who was a step from the door. */
    private static final int GRACE_SECONDS = 2;
    private static final int ARTIFACT_MIN_DISTANCE = 40;
    private static final int ARTIFACT_MAX_DISTANCE = 160;
    private static final int ARTIFACT_ATTEMPTS = 12;
    private static final RandomSource random = RandomSource.create();

    private enum Phase {
        NONE, WARNING, ACTIVE
    }

    private static Phase phase = Phase.NONE;
    private static int secondsLeft;
    /** Seconds each player has spent under open sky during the current emission. */
    private static final Map<UUID, Integer> outside = new HashMap<>();

    private Emissions() {
    }

    public static boolean enabled() {
        return SurvivalMode.enabled() && RaidConfig.SURVIVAL_EMISSIONS.get();
    }

    /** True while the emission itself runs (not during the warning): roaming enemies stay hidden. */
    public static boolean active() {
        return phase == Phase.ACTIVE;
    }

    static void stop() {
        phase = Phase.NONE;
        outside.clear();
    }

    /** Called once a second in survival mode. */
    static void second(MinecraftServer server) {
        if (!enabled()) {
            stop();
            return;
        }
        ServerLevel level = server.overworld();
        EventData data = EventData.get(server);
        long now = level.getGameTime();
        switch (phase) {
            case NONE -> {
                if (data.nextEmission == 0) {
                    schedule(data, now);
                } else if (now >= data.nextEmission) {
                    schedule(data, now);
                    // Nobody to hide: the emission passes unnoticed and the next one is due as usual.
                    if (!SurvivalMode.players(level).isEmpty()) {
                        warn(level);
                    }
                }
            }
            case WARNING -> warningSecond(level);
            case ACTIVE -> activeSecond(level);
        }
    }

    /** Starts the warning now (admin command). False if one is already coming. */
    public static boolean startNow(MinecraftServer server) {
        if (phase != Phase.NONE) {
            return false;
        }
        EventData data = EventData.get(server);
        schedule(data, server.overworld().getGameTime());
        warn(server.overworld());
        return true;
    }

    private static void schedule(EventData data, long now) {
        long interval = RaidConfig.SURVIVAL_EMISSION_INTERVAL.get() * 60L * 20L;
        data.nextEmission = now + (long) (interval * (0.75 + random.nextDouble() * 0.5));
        data.setDirty();
    }

    private static void warn(ServerLevel level) {
        phase = Phase.WARNING;
        secondsLeft = RaidConfig.SURVIVAL_EMISSION_WARNING.get();
        outside.clear();
        Component chat = Component.translatable("zoneraids.emission.warning", secondsLeft).withStyle(ChatFormatting.RED);
        for (ServerPlayer player : SurvivalMode.players(level)) {
            player.sendSystemMessage(chat);
            title(player, Component.translatable("zoneraids.emission.title_warning").withStyle(ChatFormatting.RED),
                    Component.translatable("zoneraids.emission.subtitle_warning").withStyle(ChatFormatting.GOLD));
            player.playNotifySound(SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.8F, 0.5F);
        }
        LOGGER.info("Zone Raids: emission in {} s", secondsLeft);
    }

    private static void warningSecond(ServerLevel level) {
        secondsLeft--;
        if (secondsLeft <= 0) {
            begin(level);
            return;
        }
        boolean siren = secondsLeft == 60 || secondsLeft == 30 || secondsLeft <= 10;
        for (ServerPlayer player : SurvivalMode.players(level)) {
            boolean covered = sheltered(level, player);
            player.displayClientMessage(Component.translatable("zoneraids.emission.countdown", secondsLeft,
                    Component.translatable(covered ? "zoneraids.emission.covered" : "zoneraids.emission.exposed")
                            .withStyle(covered ? ChatFormatting.GREEN : ChatFormatting.RED)).withStyle(ChatFormatting.GOLD), true);
            if (siren) {
                sound(player, secondsLeft <= 10 ? SoundEvents.NOTE_BLOCK_BASS.get() : SoundEvents.ELDER_GUARDIAN_CURSE, secondsLeft <= 10 ? 0.5F : 0.6F);
            }
        }
    }

    private static void begin(ServerLevel level) {
        phase = Phase.ACTIVE;
        secondsLeft = RaidConfig.SURVIVAL_EMISSION_SECONDS.get();
        outside.clear();
        level.setWeatherParameters(0, (secondsLeft + 20) * 20, true, true);
        for (ServerPlayer player : SurvivalMode.players(level)) {
            title(player, Component.translatable("zoneraids.emission.title").withStyle(ChatFormatting.DARK_RED),
                    Component.translatable("zoneraids.emission.subtitle").withStyle(ChatFormatting.RED));
            player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 0.6F);
        }
        LOGGER.info("Zone Raids: emission for {} s", secondsLeft);
    }

    private static void activeSecond(ServerLevel level) {
        secondsLeft--;
        double base = RaidConfig.SURVIVAL_EMISSION_DAMAGE.get();
        for (ServerPlayer player : SurvivalMode.players(level)) {
            if (sheltered(level, player)) {
                outside.remove(player.getUUID());
                player.displayClientMessage(Component.translatable("zoneraids.emission.safe", Math.max(0, secondsLeft))
                        .withStyle(ChatFormatting.GREEN), true);
                continue;
            }
            int seconds = outside.merge(player.getUUID(), 1, Integer::sum);
            player.displayClientMessage(Component.translatable("zoneraids.emission.outside").withStyle(ChatFormatting.RED), true);
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, false, false));
            if (seconds > GRACE_SECONDS && base > 0) {
                // Grows by the base amount every 10 seconds spent outside, as in the raid round.
                player.hurt(level.damageSources().magic(), (float) (base * (1 + seconds / 10)));
            }
            if (random.nextInt(3) == 0) {
                strike(level, player);
            }
        }
        if (secondsLeft <= 0) {
            end(level);
        }
    }

    private static void end(ServerLevel level) {
        phase = Phase.NONE;
        outside.clear();
        level.setWeatherParameters(6000, 0, false, false);
        int count = RaidConfig.SURVIVAL_EMISSION_ARTIFACTS.get();
        List<Item> artifacts = artifacts();
        for (ServerPlayer player : SurvivalMode.players(level)) {
            int placed = 0;
            for (int n = 0; n < count && !artifacts.isEmpty(); n++) {
                if (placeArtifact(level, player, artifacts.get(random.nextInt(artifacts.size())))) {
                    placed++;
                }
            }
            player.sendSystemMessage(Component.translatable(placed > 0 ? "zoneraids.emission.over_artifacts" : "zoneraids.emission.over", placed)
                    .withStyle(ChatFormatting.GREEN));
            player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.get(), SoundSource.PLAYERS, 0.7F, 1.2F);
        }
        LOGGER.info("Zone Raids: emission over");
    }

    /** True with a solid block (not leaves) anywhere above the player's head: a roof, a cave, deep water. */
    public static boolean sheltered(ServerLevel level, ServerPlayer player) {
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, eye.getX(), eye.getZ()) > eye.getY();
    }

    /** A harmless lightning bolt somewhere around the player, for the look of it. */
    private static void strike(ServerLevel level, ServerPlayer player) {
        int x = player.getBlockX() + random.nextInt(49) - 24;
        int z = player.getBlockZ() + random.nextInt(49) - 24;
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(Vec3.atBottomCenterOf(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z)));
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
    }

    private static List<Item> artifacts() {
        List<Item> items = new ArrayList<>();
        for (String id : RaidConfig.ARTIFACT_ITEMS.get()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null && ForgeRegistries.ITEMS.containsKey(key)) {
                items.add(ForgeRegistries.ITEMS.getValue(key));
            }
        }
        return items;
    }

    /** Drops a glowing artifact on open ground some way off and marks it on the player's PDA map. */
    private static boolean placeArtifact(ServerLevel level, ServerPlayer player, Item item) {
        for (int attempt = 0; attempt < ARTIFACT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = ARTIFACT_MIN_DISTANCE + random.nextDouble() * (ARTIFACT_MAX_DISTANCE - ARTIFACT_MIN_DISTANCE);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            BlockPos pos = StashDrives.surface(level, x, z);
            if (pos == null) {
                continue;
            }
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, new ItemStack(item));
            entity.setDeltaMovement(Vec3.ZERO);
            entity.setUnlimitedLifetime();
            entity.setGlowingTag(true);
            if (!level.addFreshEntity(entity)) {
                continue;
            }
            RaidNetwork.sendTo(player, new MapMarkerPacket(pos.getX(), pos.getZ(), MapMarkerPacket.ARTIFACT, "zoneraids.emission.marker"));
            return true;
        }
        return false;
    }

    private static void title(ServerPlayer player, Component title, Component subtitle) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    private static void sound(ServerPlayer player, SoundEvent sound, float volume) {
        player.playNotifySound(sound, SoundSource.HOSTILE, volume, 0.5F);
    }
}
