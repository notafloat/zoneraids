package com.zoneraids.raid;

import com.zoneraids.RaidConfig;
import com.zoneraids.data.AnomalyPoint;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Anomalies of a running raid: ambient particles, harm to players who walk in, and artifacts that
 * appear in them. Ticked by {@link RaidManager} with the session, every 10 ticks. Anomalies only do
 * anything while a raid player is near, so an empty map costs nothing.
 */
final class Anomalies {
    /** Players closer than this wake an anomaly up (particles, artifact spawn). */
    private static final double WAKE_DISTANCE = 40;
    private static final double MAX_DY = 4;

    private final List<ServerPlayer> players = new ArrayList<>();

    /** Rolls which anomalies hold an artifact in this raid. Items that are not installed are skipped. */
    static void rollArtifacts(RaidSession session, Random random) {
        List<Item> items = new ArrayList<>();
        for (String id : RaidConfig.ARTIFACT_ITEMS.get()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null && ForgeRegistries.ITEMS.containsKey(key)) {
                items.add(ForgeRegistries.ITEMS.getValue(key));
            }
        }
        if (items.isEmpty() || !RaidConfig.ANOMALIES.get()) {
            return;
        }
        double chance = RaidConfig.ARTIFACT_CHANCE.get();
        for (int i = 0; i < session.pendingArtifacts.length; i++) {
            if (random.nextDouble() < chance) {
                session.pendingArtifacts[i] = items.get(random.nextInt(items.size()));
            }
        }
    }

    /** Removes artifacts nobody picked up. */
    static void clearArtifacts(RaidSession session, ServerLevel level) {
        for (int i = 0; i < session.artifactEntities.length; i++) {
            UUID id = session.artifactEntities[i];
            if (id != null) {
                Entity entity = level.getEntity(id);
                if (entity instanceof ItemEntity) {
                    entity.discard();
                }
                session.artifactEntities[i] = null;
            }
            session.pendingArtifacts[i] = null;
        }
    }

    void tick(RaidSession session, ServerLevel level, Iterable<ServerPlayer> online) {
        List<AnomalyPoint> anomalies = session.map.anomalies;
        if (anomalies.isEmpty() || !RaidConfig.ANOMALIES.get()) {
            return;
        }
        players.clear();
        for (ServerPlayer player : online) {
            if (player.isAlive() && player.level() == level && !player.isSpectator()) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        float damage = RaidConfig.ANOMALY_DAMAGE.get().floatValue();
        RandomSource random = level.getRandom();
        try {
            for (int i = 0; i < anomalies.size(); i++) {
                AnomalyPoint a = anomalies.get(i);
                double cx = a.centerX();
                double cy = a.pos().getY();
                double cz = a.centerZ();
                double reach = a.reach();
                boolean awake = false;
                for (ServerPlayer player : players) {
                    double dx = player.getX() - cx;
                    double dz = player.getZ() - cz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 > WAKE_DISTANCE * WAKE_DISTANCE) {
                        continue;
                    }
                    awake = true;
                    if (d2 <= reach * reach && Math.abs(player.getY() - cy) <= MAX_DY && !player.isCreative()) {
                        affect(level, a, player, Math.sqrt(d2), damage, random);
                    }
                }
                if (awake) {
                    ambient(level, a, random);
                    showArtifact(session, level, i, a);
                }
            }
        } finally {
            players.clear();
        }
    }

    private static void showArtifact(RaidSession session, ServerLevel level, int index, AnomalyPoint a) {
        Item pending = session.pendingArtifacts[index];
        if (pending != null) {
            // Spawned only now, with a player near, so the chunk is loaded and the item ages only while someone is around.
            session.pendingArtifacts[index] = null;
            ItemEntity item = new ItemEntity(level, a.centerX(), a.pos().getY() + 0.1, a.centerZ(), new ItemStack(pending), 0, 0, 0);
            item.setInvulnerable(true);
            item.setExtendedLifetime();
            if (level.addFreshEntity(item)) {
                session.artifactEntities[index] = item.getUUID();
            }
            return;
        }
        UUID id = session.artifactEntities[index];
        if (id != null) {
            Entity entity = level.getEntity(id);
            if (entity == null || entity.isRemoved()) {
                session.artifactEntities[index] = null;
            } else {
                level.sendParticles(ParticleTypes.END_ROD, entity.getX(), entity.getY() + 0.6, entity.getZ(), 1, 0.15, 0.2, 0.15, 0.01);
            }
        }
    }

    private static void ambient(ServerLevel level, AnomalyPoint a, RandomSource random) {
        double x = a.centerX();
        double y = a.pos().getY();
        double z = a.centerZ();
        double r = a.radius();
        switch (a.type()) {
            case ELECTRA -> {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.8, z, 8, r * 0.4, 0.6, r * 0.4, 0.2);
                if (random.nextInt(6) == 0) {
                    level.playSound(null, x, y, z, SoundEvents.BEEHIVE_SHEAR, SoundSource.AMBIENT, 0.5F, 2.0F);
                }
            }
            case BURNER -> {
                level.sendParticles(ParticleTypes.FLAME, x, y + 0.1, z, 4, r * 0.3, 0.05, r * 0.3, 0.02);
                level.sendParticles(ParticleTypes.SMOKE, x, y + 0.6, z, 3, r * 0.3, 0.3, r * 0.3, 0.01);
            }
            case VORTEX -> {
                // Dust drawn in towards the centre from all sides.
                for (int k = 0; k < 6; k++) {
                    double angle = random.nextDouble() * Math.PI * 2;
                    double dist = r * (1.2 + random.nextDouble());
                    double px = x + Math.cos(angle) * dist;
                    double pz = z + Math.sin(angle) * dist;
                    level.sendParticles(ParticleTypes.POOF, px, y + 0.2 + random.nextDouble(), pz, 0, x - px, 0.15, z - pz, 0.12);
                }
            }
            case ACID -> {
                level.sendParticles(ParticleTypes.ITEM_SLIME, x, y + 0.2, z, 5, r * 0.4, 0.1, r * 0.4, 0.0);
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, x, y + 0.5, z, 2, r * 0.4, 0.3, r * 0.4, 0.0);
            }
        }
    }

    private static void affect(ServerLevel level, AnomalyPoint a, ServerPlayer player, double distance, float damage, RandomSource random) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        switch (a.type()) {
            case ELECTRA -> {
                player.hurt(level.damageSources().lightningBolt(), 5.0F * damage);
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 2, false, false));
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 1, z, 25, 0.4, 0.8, 0.4, 0.6);
                level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 0.7F, 1.4F);
            }
            case BURNER -> {
                player.setSecondsOnFire(5);
                player.hurt(level.damageSources().inFire(), 3.0F * damage);
                level.sendParticles(ParticleTypes.FLAME, x, y + 0.5, z, 15, 0.3, 0.6, 0.3, 0.05);
                level.playSound(null, x, y, z, SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 0.6F, 0.8F);
            }
            case VORTEX -> {
                if (distance <= Math.max(1.2, a.radius() * 0.4)) {
                    player.hurt(level.damageSources().magic(), 6.0F * damage);
                    player.setDeltaMovement((random.nextDouble() - 0.5) * 1.2, 1.1, (random.nextDouble() - 0.5) * 1.2);
                    level.sendParticles(ParticleTypes.EXPLOSION, a.centerX(), y + 0.5, a.centerZ(), 1, 0, 0, 0, 0);
                    level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.6F, 1.6F);
                } else {
                    double dx = a.centerX() - x;
                    double dz = a.centerZ() - z;
                    double pull = 0.25 / Math.max(0.5, distance);
                    Vec3 v = player.getDeltaMovement();
                    player.setDeltaMovement(v.x + dx * pull, v.y, v.z + dz * pull);
                }
                // Motion set on the server only reaches the client with this flag.
                player.hurtMarked = true;
            }
            case ACID -> {
                player.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
                player.hurt(level.damageSources().magic(), 1.5F * damage);
                level.sendParticles(ParticleTypes.ITEM_SLIME, x, y + 0.3, z, 10, 0.3, 0.2, 0.3, 0.0);
                level.playSound(null, x, y, z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 0.7F, 0.8F);
            }
        }
    }
}
