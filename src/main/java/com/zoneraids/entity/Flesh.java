package com.zoneraids.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.entity.monster.hoglin.HoglinBase;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Flesh: a slow, tough mutated pig that rams and tosses its target. Uses the vanilla hoglin model.
 */
public class Flesh extends Monster implements HoglinBase {
    private static final byte ATTACK_EVENT = 4;
    private int attackAnimationRemainingTicks;

    public Flesh(EntityType<? extends Flesh> type, Level level) {
        super(type, level);
        xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.ATTACK_DAMAGE, 3.5)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
                .add(Attributes.FOLLOW_RANGE, 20.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15, true));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.7));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(10, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        if (!(target instanceof LivingEntity living)) {
            return false;
        }
        attackAnimationRemainingTicks = 10;
        level().broadcastEntityEvent(this, ATTACK_EVENT);
        playSound(SoundEvents.HOGLIN_ATTACK, 1.0F, getVoicePitch());
        return HoglinBase.hurtAndThrowTarget(this, living);
    }

    @Override
    protected void blockedByShield(LivingEntity defender) {
        HoglinBase.throwTarget(this, defender);
    }

    @Override
    public void aiStep() {
        if (attackAnimationRemainingTicks > 0) {
            attackAnimationRemainingTicks--;
        }
        super.aiStep();
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == ATTACK_EVENT) {
            attackAnimationRemainingTicks = 10;
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public int getAttackAnimationRemainingTicks() {
        return attackAnimationRemainingTicks;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PIG_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PIG_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PIG_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.6F;
    }
}
