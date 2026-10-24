package com.zoneraids.entity;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Pseudogiant: the boss of the ruined collective farm. A huge, slow flesh that tosses whoever it
 * reaches and, every few seconds, stamps the ground: everyone close by is hurt and thrown back,
 * so the fight is about keeping distance and timing the reload between stamps.
 */
public class Pseudogiant extends Flesh {
    private static final int STOMP_COOLDOWN = 110;
    private static final int STOMP_WINDUP = 18;
    private static final double STOMP_RADIUS = 7.0;
    /** Rearing up before a stamp, for the model. */
    private static final EntityDataAccessor<Boolean> WINDUP = SynchedEntityData.defineId(Pseudogiant.class, EntityDataSerializers.BOOLEAN);
    private final ServerBossEvent bossBar = new ServerBossEvent(getDisplayName(), BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);
    private int stompCooldown = STOMP_COOLDOWN;
    private int windup;

    public Pseudogiant(EntityType<? extends Pseudogiant> type, Level level) {
        super(type, level);
        xpReward = 80;
        setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(WINDUP, false);
    }

    public boolean isWindingUp() {
        return entityData.get(WINDUP);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Flesh.createAttributes()
                .add(Attributes.MAX_HEALTH, 320.0)
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.ATTACK_DAMAGE, 7.0)
                .add(Attributes.ATTACK_KNOCKBACK, 2.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ARMOR, 8.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        bossBar.setProgress(getHealth() / getMaxHealth());
        LivingEntity target = getTarget();
        if (windup > 0) {
            if (--windup == 0) {
                entityData.set(WINDUP, false);
                stomp();
            }
            return;
        }
        if (stompCooldown > 0) {
            stompCooldown--;
        } else if (target != null && onGround() && distanceToSqr(target) < STOMP_RADIUS * STOMP_RADIUS) {
            // A roar warns of the stamp: players who back off in time take nothing.
            windup = STOMP_WINDUP;
            entityData.set(WINDUP, true);
            stompCooldown = STOMP_COOLDOWN;
            playSound(SoundEvents.RAVAGER_ROAR, 2.0F, 0.6F);
        }
    }

    private void stomp() {
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        playSound(SoundEvents.GENERIC_EXPLODE, 2.0F, 0.5F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, getBlockStateOn()), getX(), getY() + 0.1, getZ(),
                120, STOMP_RADIUS / 2, 0.2, STOMP_RADIUS / 2, 0.15);
        level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.5, getZ(), 6, 2.0, 0.3, 2.0, 0.0);
        for (Player player : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(STOMP_RADIUS, 3.0, STOMP_RADIUS))) {
            double distance = player.distanceTo(this);
            if (distance > STOMP_RADIUS || player.isCreative() || player.isSpectator() || !player.onGround()) {
                continue;
            }
            float falloff = (float) (1.0 - distance / STOMP_RADIUS);
            player.hurt(damageSources().mobAttack(this), 3.0F + 6.0F * falloff);
            Vec3 away = player.position().subtract(position()).multiply(1, 0, 1).normalize().scale(1.2 * falloff + 0.3);
            player.push(away.x, 0.6 + 0.3 * falloff, away.z);
            player.hurtMarked = true;
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossBar.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossBar.removePlayer(player);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.7F;
    }
}
