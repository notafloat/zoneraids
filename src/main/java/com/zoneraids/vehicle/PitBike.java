package com.zoneraids.vehicle;

import com.google.common.collect.Lists;
import com.zoneraids.client.vehicle.PitBikeSound;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Two-seat pit bike. Driven like a boat: W/S throttle and brake, A/D steer, Shift to get off. The driver's
 * client simulates it and vanilla vehicle packets sync it, so it costs the server next to nothing while driven.
 * A bike spawned for a raid carries that raid's id and is removed when the raid ends (see VehicleSpawner).
 */
public class PitBike extends Entity {
    private static final EntityDataAccessor<Float> DATA_HEALTH = SynchedEntityData.defineId(PitBike.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_HURT = SynchedEntityData.defineId(PitBike.class, EntityDataSerializers.INT);

    private static final int SEATS = 2;
    private static final double ACCELERATION = 0.02;
    private static final double BRAKE = 0.04;
    private static final double REVERSE_SPEED = 0.1;
    private static final float TURN_DEGREES = 5.0F;
    /** Slower than this the bike barely turns (steering needs rolling wheels). */
    private static final double TURN_FULL_SPEED = 0.15;
    private static final double WATER_SPEED = 0.06;
    /** How fast the steering follows A/D, per tick (1 = instantly). */
    private static final float STEER_EASE = 0.35F;

    /** Raid the bike was spawned for, or null for a free bike placed by an admin. */
    @Nullable
    private UUID raidSession;
    /** Forward speed in blocks per tick; only meaningful where the bike is simulated. */
    private double speed;
    /** Smoothed steering input, -1..1, and the turn applied this tick (also to the riders). */
    private float steer;
    private float deltaRotation;

    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;
    private double lerpXRot;

    // Client-side animation state.
    public float wheelRot;
    public float wheelRotO;
    public float lean;
    public float leanO;
    public float clientSpeed;
    private boolean soundStarted;

    public PitBike(EntityType<? extends PitBike> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
        setMaxUpStep(1.0F);
    }

    public PitBike(Level level, double x, double y, double z, float yaw) {
        this(Vehicles.PIT_BIKE.get(), level);
        setPos(x, y, z);
        setYRot(yaw);
        this.xo = x;
        this.yo = y;
        this.zo = z;
        this.yRotO = yaw;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_HEALTH, Vehicles.health());
        entityData.define(DATA_HURT, 0);
    }

    @Nullable
    public UUID raidSession() {
        return raidSession;
    }

    public void setRaidSession(@Nullable UUID session) {
        this.raidSession = session;
    }

    public float health() {
        return entityData.get(DATA_HEALTH);
    }

    public int hurtTicks() {
        return entityData.get(DATA_HURT);
    }

    @Override
    protected Component getTypeName() {
        return Component.translatable("item.zoneraids.pit_bike");
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(Vehicles.PIT_BIKE_ITEM.get());
    }

    // ------------------------------------------------------------------ riding

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive() || !canAddPassenger(player) || player.isPassenger()) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide) {
            return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < SEATS && !isUnderWater();
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player player ? player : null;
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.45;
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) {
            return;
        }
        // Driver over the tank end of the seat, passenger behind.
        double back = getPassengers().indexOf(passenger) == 0 ? -0.05 : 0.5;
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        double x = getX() + Mth.sin(yaw) * back;
        double z = getZ() - Mth.cos(yaw) * back;
        double y = getY() + getPassengersRidingOffset() + passenger.getMyRidingOffset();
        move.accept(passenger, x, y, z);
        // Turning riders here (after their own tick saved last rotation) lets the camera interpolate the
        // turn between frames, like a boat; turning them during the bike's tick made first person jerk.
        if (deltaRotation != 0.0F) {
            passenger.setYRot(passenger.getYRot() + deltaRotation);
            passenger.setYHeadRot(passenger.getYHeadRot() + deltaRotation);
        }
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (isControlledByLocalInstance() && lerpSteps > 0) {
            lerpSteps = 0;
            absMoveTo(lerpX, lerpY, lerpZ, (float) lerpYRot, (float) lerpXRot);
        }
    }

    @Override
    public void onPassengerTurned(Entity passenger) {
        passenger.setYBodyRot(getYRot());
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Vec3 escape = getCollisionHorizontalEscapeVector(getBbWidth() * Mth.SQRT_OF_TWO, passenger.getBbWidth(), passenger.getYRot());
        double x = getX() + escape.x;
        double z = getZ() + escape.z;
        BlockPos pos = BlockPos.containing(x, getBoundingBox().maxY, z);
        BlockPos below = pos.below();
        if (!level().isWaterAt(below)) {
            List<Vec3> spots = Lists.newArrayList();
            double floor = level().getBlockFloorHeight(pos);
            if (DismountHelper.isBlockFloorValid(floor)) {
                spots.add(new Vec3(x, pos.getY() + floor, z));
            }
            double floorBelow = level().getBlockFloorHeight(below);
            if (DismountHelper.isBlockFloorValid(floorBelow)) {
                spots.add(new Vec3(x, below.getY() + floorBelow, z));
            }
            for (Pose pose : passenger.getDismountPoses()) {
                for (Vec3 spot : spots) {
                    if (DismountHelper.canDismountTo(level(), spot, passenger, pose)) {
                        passenger.setPose(pose);
                        return spot;
                    }
                }
            }
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        int hurt = hurtTicks();
        if (hurt > 0 && !level().isClientSide) {
            entityData.set(DATA_HURT, hurt - 1);
        }
        super.tick();

        if (isControlledByLocalInstance()) {
            lerpSteps = 0;
            syncPacketPositionCodec(getX(), getY(), getZ());
            drive();
            move(MoverType.SELF, getDeltaMovement());
            if (horizontalCollision) {
                speed *= 0.3;
            }
        } else {
            speed = 0.0;
            steer = 0.0F;
            deltaRotation = 0.0F;
            setDeltaMovement(Vec3.ZERO);
        }
        tickLerp();
        checkInsideBlocks();

        if (level().isClientSide) {
            animate();
            if (!soundStarted && getControllingPassenger() != null) {
                soundStarted = true;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PitBikeSound.start(this));
            } else if (getControllingPassenger() == null) {
                soundStarted = false;
            }
            if (health() < Vehicles.health() * 0.3F && random.nextInt(4) == 0) {
                float yaw = getYRot() * Mth.DEG_TO_RAD;
                level().addParticle(ParticleTypes.LARGE_SMOKE, getX() + Mth.sin(yaw) * 0.4, getY() + 0.8, getZ() - Mth.cos(yaw) * 0.4, 0, 0.03, 0);
            }
        }
    }

    /** Throttle, brake, steering and gravity for the side that simulates the bike. */
    private void drive() {
        deltaRotation = 0.0F;
        Vec3 motion = getDeltaMovement();
        float forward = 0.0F;
        float strafe = 0.0F;
        if (getControllingPassenger() instanceof Player driver) {
            forward = driver.zza;
            strafe = driver.xxa;
        }
        double max = isInWater() ? WATER_SPEED : Vehicles.maxSpeed();
        if (onGround()) {
            if (forward > 0.0F) {
                speed = Math.min(speed + ACCELERATION, max);
            } else if (forward < 0.0F) {
                speed = speed > 0.01 ? speed - BRAKE : Math.max(speed - ACCELERATION * 0.5, -REVERSE_SPEED);
            } else {
                speed *= 0.96;
            }
            if (Math.abs(speed) < 0.003) {
                speed = 0.0;
            }
            if (speed > max) {
                speed = Math.max(max, speed * 0.9);
            }
            float grip = (float) Mth.clamp(Math.abs(speed) / TURN_FULL_SPEED, 0.0, 1.0);
            // The handlebar eases towards the key instead of snapping, so turns start and end smoothly.
            steer += (strafe - steer) * STEER_EASE;
            if (Math.abs(steer) < 0.01F) {
                steer = 0.0F;
            }
            deltaRotation = -steer * TURN_DEGREES * grip * (speed < 0 ? -1.0F : 1.0F);
            setYRot(getYRot() + deltaRotation);
        } else {
            speed *= 0.99;
        }
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        double dx = -Mth.sin(yaw) * speed;
        double dz = Mth.cos(yaw) * speed;
        double dy = onGround() ? -0.04 : (motion.y - 0.08) * 0.98;
        if (isInWater()) {
            dy = Math.max(dy, -0.05);
        }
        setDeltaMovement(dx, dy, dz);
    }

    private void animate() {
        wheelRotO = wheelRot;
        leanO = lean;
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        double moved = (getX() - xo) * -Mth.sin(yaw) + (getZ() - zo) * Mth.cos(yaw);
        clientSpeed = (float) moved;
        wheelRot += (float) (moved / (5.0 / 16.0));
        float turned = Mth.wrapDegrees(getYRot() - yRotO);
        float target = Mth.clamp(-turned * 4.0F, -25.0F, 25.0F);
        lean += (target - lean) * 0.3F;
    }

    private void tickLerp() {
        if (isControlledByLocalInstance()) {
            lerpSteps = 0;
            syncPacketPositionCodec(getX(), getY(), getZ());
        }
        if (lerpSteps > 0) {
            double x = getX() + (lerpX - getX()) / lerpSteps;
            double y = getY() + (lerpY - getY()) / lerpSteps;
            double z = getZ() + (lerpZ - getZ()) / lerpSteps;
            setYRot(getYRot() + (float) Mth.wrapDegrees(lerpYRot - getYRot()) / lerpSteps);
            setXRot(getXRot() + (float) (lerpXRot - getXRot()) / lerpSteps);
            lerpSteps--;
            setPos(x, y, z);
            setRot(getYRot(), getXRot());
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpXRot = xRot;
        lerpSteps = 10;
    }

    // ------------------------------------------------------------------ damage

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || isRemoved()) {
            return false;
        }
        Entity attacker = source.getEntity();
        if (attacker != null && hasPassenger(attacker)) {
            // Riders shooting forward or down must not wreck their own bike.
            return false;
        }
        if (level().isClientSide) {
            return true;
        }
        if (attacker instanceof Player player && player.getAbilities().instabuild) {
            ejectPassengers();
            discard();
            return true;
        }
        float health = health() - amount;
        entityData.set(DATA_HEALTH, health);
        entityData.set(DATA_HURT, 10);
        gameEvent(GameEvent.ENTITY_DAMAGE, attacker);
        if (health <= 0.0F) {
            wreck();
        } else {
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.NEUTRAL, 0.4F, 1.4F + random.nextFloat() * 0.3F);
        }
        return true;
    }

    private void wreck() {
        ejectPassengers();
        if (level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.5, getZ(), 1, 0, 0, 0, 0);
            server.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.5, getZ(), 12, 0.4, 0.3, 0.4, 0.02);
            server.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.8F, 1.3F);
        }
        discard();
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        if (distance > 4.0F && !level().isClientSide) {
            hurt(source, (distance - 4.0F) * 2.0F);
        }
        // Riders land with the bike and take the fall themselves, softened by the suspension.
        return super.causeFallDamage(Math.max(0.0F, distance - 2.0F), multiplier, source);
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    public void push(Entity entity) {
        if (!entity.hasPassenger(this) && !hasPassenger(entity) && entity instanceof PitBike) {
            super.push(entity);
        }
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Health")) {
            entityData.set(DATA_HEALTH, tag.getFloat("Health"));
        }
        raidSession = tag.hasUUID("RaidSession") ? tag.getUUID("RaidSession") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Health", health());
        if (raidSession != null) {
            tag.putUUID("RaidSession", raidSession);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
