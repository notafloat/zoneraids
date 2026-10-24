package com.zoneraids.entity;

import com.zoneraids.registry.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Controller: the boss of the underground laboratory. Weak in melee, it strikes the mind instead:
 * a player it can see is hit by a psi wave (nausea, darkness, slowness and damage), and it calls
 * snorks to its side. Breaking line of sight is the way to close in.
 */
public class Controller extends Monster {
    private static final int PSI_COOLDOWN = 90;
    private static final int PSI_WINDUP = 25;
    private static final double PSI_RANGE = 28.0;
    private static final int SUMMON_COOLDOWN = 360;
    private static final int MAX_MINIONS = 3;
    /** Gathering a psi wave, for the model. */
    private static final EntityDataAccessor<Boolean> CHARGING = SynchedEntityData.defineId(Controller.class, EntityDataSerializers.BOOLEAN);
    private final ServerBossEvent bossBar = new ServerBossEvent(getDisplayName(), BossEvent.BossBarColor.PURPLE,
            BossEvent.BossBarOverlay.NOTCHED_10);
    private int psiCooldown = PSI_COOLDOWN;
    private int psiWindup;
    private int summonCooldown = 100;

    public Controller(EntityType<? extends Controller> type, Level level) {
        super(type, level);
        xpReward = 80;
        setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(CHARGING, false);
    }

    public boolean isCharging() {
        return entityData.get(CHARGING);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 180.0)
                .add(Attributes.MOVEMENT_SPEED, 0.21)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 0.9, true));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.5));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 16.0F));
        goalSelector.addGoal(10, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, false));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        bossBar.setProgress(getHealth() / getMaxHealth());
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity target = getTarget();
        entityData.set(CHARGING, psiWindup > 0);
        if (psiWindup > 0) {
            level.sendParticles(ParticleTypes.WITCH, getX(), getEyeY(), getZ(), 3, 0.4, 0.3, 0.4, 0.0);
            if (--psiWindup == 0 && target instanceof Player player && canPsi(player)) {
                psi(level, player);
            }
        } else if (psiCooldown > 0) {
            psiCooldown--;
        } else if (target instanceof Player player && canPsi(player)) {
            psiWindup = PSI_WINDUP;
            psiCooldown = PSI_COOLDOWN;
            playSound(SoundEvents.WARDEN_SONIC_CHARGE, 1.5F, 0.7F);
        }
        if (summonCooldown > 0) {
            summonCooldown--;
        } else if (target != null) {
            summonCooldown = SUMMON_COOLDOWN;
            summon(level);
        }
    }

    private boolean canPsi(Player player) {
        return !player.isCreative() && !player.isSpectator() && distanceToSqr(player) < PSI_RANGE * PSI_RANGE && hasLineOfSight(player);
    }

    private void psi(ServerLevel level, Player player) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.0F, 0.6F);
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0), this);
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0), this);
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1), this);
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 0), this);
        player.hurt(damageSources().indirectMagic(this, this), 4.0F);
    }

    private void summon(ServerLevel level) {
        int alive = level.getEntitiesOfClass(Snork.class, getBoundingBox().inflate(20.0)).size();
        for (int n = alive; n < Math.min(MAX_MINIONS, alive + 2); n++) {
            Snork snork = ModEntities.SNORK.get().create(level);
            if (snork == null) {
                return;
            }
            double angle = random.nextDouble() * Math.PI * 2;
            snork.moveTo(getX() + Math.cos(angle) * 2.5, getY(), getZ() + Math.sin(angle) * 2.5, random.nextFloat() * 360.0F, 0.0F);
            if (!level.noCollision(snork)) {
                continue;
            }
            snork.finalizeSpawn(level, level.getCurrentDifficultyAt(snork.blockPosition()), MobSpawnType.MOB_SUMMONED, null, null);
            snork.setTarget(getTarget());
            level.addFreshEntity(snork);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, snork.getX(), snork.getY() + 1.0, snork.getZ(), 10, 0.3, 0.6, 0.3, 0.02);
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
        return SoundEvents.ZOMBIE_VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ZOMBIE_VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_VILLAGER_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.5F;
    }
}
