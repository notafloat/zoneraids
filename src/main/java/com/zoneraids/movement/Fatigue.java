package com.zoneraids.movement;

import com.zoneraids.ZoneRaids;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.StaminaPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arma 3 style stamina and load, kept on the server. Sprinting and jumping spend stamina, faster the heavier
 * the load; standing still restores it fastest. At zero the player is exhausted: no sprinting (the client
 * holds it back, see MovementClient) and slower until a quarter is back. The load slows the player through a
 * movement speed modifier, so the speed is the server's and the same for every client.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class Fatigue {
    private static final UUID LOAD_SPEED = UUID.fromString("0b6c1d52-4f0a-4c39-a1d6-3e2b7d1f9a01");
    private static final UUID EXHAUSTED_SPEED = UUID.fromString("0b6c1d52-4f0a-4c39-a1d6-3e2b7d1f9a02");
    /** Load at which the speed penalty is full, and the full penalty. */
    private static final float HEAVY = 45.0F;
    private static final double LOAD_PENALTY = 0.25;
    private static final double EXHAUSTED_PENALTY = 0.15;
    /** Unloaded, a full bar lasts about 17 seconds of sprinting. */
    private static final float SPRINT_COST = 0.3F;
    private static final float JUMP_COST = 4.0F;
    private static final float REST = 0.6F;
    private static final float MOVING_REST = 0.3F;
    private static final float RECOVERED = 25.0F;

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        float stamina = 100.0F;
        boolean exhausted;
        float load;
        int sentStamina = -1;
        boolean sentExhausted;
        int sentLoad = -1;
    }

    private Fatigue() {
    }

    private static float heaviness(State s) {
        return 1.0F + s.load / 30.0F;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        if (player.isCreative() || player.isSpectator() || !player.isAlive()) {
            s.stamina = 100.0F;
            s.exhausted = false;
            s.load = 0.0F;
            applySpeed(player, 0.0F, false);
            sync(player, s);
            return;
        }
        if (player.tickCount % 20 == 0) {
            s.load = Load.of(player);
            applySpeed(player, s.load, s.exhausted);
        }
        if (player.isSprinting()) {
            s.stamina -= SPRINT_COST * heaviness(s);
        } else {
            boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 0.0025;
            s.stamina += (moving ? MOVING_REST : REST) / heaviness(s);
        }
        s.stamina = Mth.clamp(s.stamina, 0.0F, 100.0F);
        boolean exhausted = s.exhausted ? s.stamina < RECOVERED : s.stamina <= 0.0F;
        if (exhausted != s.exhausted) {
            s.exhausted = exhausted;
            applySpeed(player, s.load, exhausted);
        }
        if (exhausted && player.isSprinting()) {
            player.setSprinting(false);
        }
        sync(player, s);
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.isCreative()) {
            State s = STATES.get(player.getUUID());
            if (s != null) {
                s.stamina = Math.max(0.0F, s.stamina - JUMP_COST * heaviness(s));
            }
        }
    }

    private static void applySpeed(ServerPlayer player, float load, boolean exhausted) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        double loadPenalty = -LOAD_PENALTY * Math.min(1.0F, load / HEAVY);
        AttributeModifier current = speed.getModifier(LOAD_SPEED);
        if (current == null || Math.abs(current.getAmount() - loadPenalty) > 0.005) {
            speed.removeModifier(LOAD_SPEED);
            if (loadPenalty < -0.005) {
                speed.addTransientModifier(new AttributeModifier(LOAD_SPEED, "zoneraids load", loadPenalty,
                        AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
        }
        boolean has = speed.getModifier(EXHAUSTED_SPEED) != null;
        if (exhausted && !has) {
            speed.addTransientModifier(new AttributeModifier(EXHAUSTED_SPEED, "zoneraids exhausted", -EXHAUSTED_PENALTY,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (!exhausted && has) {
            speed.removeModifier(EXHAUSTED_SPEED);
        }
    }

    private static void sync(ServerPlayer player, State s) {
        int stamina = Math.round(s.stamina);
        int load = Math.round(s.load * 10.0F);
        if (stamina != s.sentStamina || s.exhausted != s.sentExhausted || load != s.sentLoad) {
            s.sentStamina = stamina;
            s.sentExhausted = s.exhausted;
            s.sentLoad = load;
            RaidNetwork.sendTo(player, new StaminaPacket(stamina, s.exhausted, load));
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }
}
