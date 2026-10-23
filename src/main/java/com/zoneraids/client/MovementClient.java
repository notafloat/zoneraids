package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Arma 3 style movement on the client: the body speeds up and slows down over a moment instead of at once
 * (slower with a heavy load), a walk toggle, and no sprinting while exhausted (stamina and load come from the
 * server, see com.zoneraids.movement.Fatigue).
 *
 * Vanilla starts a sprint only when the forward input is at least 0.8, so capping it below that holds a
 * sprint back without touching the sprint key, whose toggle mode a forced press would flip.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MovementClient {
    /** Share of the gap to the pressed speed closed per tick when speeding up (unloaded) and when slowing down. */
    private static final float ACCELERATE = 0.25F;
    private static final float DECELERATE = 0.35F;
    private static final float WALK = 0.4F;
    private static final float NO_SPRINT = 0.79F;

    private static int stamina = 100;
    private static boolean exhausted;
    private static float load;
    private static boolean walking;
    private static float forward;
    private static float strafe;

    private MovementClient() {
    }

    public static void set(int stamina, boolean exhausted, int loadTenths) {
        MovementClient.stamina = stamina;
        MovementClient.exhausted = exhausted;
        MovementClient.load = loadTenths / 10.0F;
    }

    public static float stamina() {
        return stamina / 100.0F;
    }

    public static boolean exhausted() {
        return exhausted;
    }

    public static float load() {
        return load;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        while (ClientSetup.WALK.consumeClick()) {
            walking = !walking;
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(walking ? "zoneraids.move.walk" : "zoneraids.move.run"), true);
            }
        }
    }

    /** LOW, so Zone Raids' stance crouch (LeanClient) and other mods' input changes come first. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player) || player.isPassenger() || player.getAbilities().flying) {
            forward = strafe = 0.0F;
            return;
        }
        Input input = event.getInput();
        float rate = ACCELERATE / (1.0F + load / 30.0F);
        forward = approach(forward, input.forwardImpulse, rate);
        strafe = approach(strafe, input.leftImpulse, rate);
        input.forwardImpulse = forward;
        input.leftImpulse = strafe;
        if (walking) {
            input.forwardImpulse *= WALK;
            input.leftImpulse *= WALK;
        }
        if ((walking || exhausted) && !player.isCreative()) {
            input.forwardImpulse = Math.min(input.forwardImpulse, NO_SPRINT);
            if (player.isSprinting()) {
                player.setSprinting(false);
            }
        }
    }

    private static float approach(float current, float target, float rate) {
        boolean faster = Math.abs(target) > Math.abs(current) && Math.signum(target) != -Math.signum(current);
        float next = current + (target - current) * (faster ? rate : DECELERATE);
        return Math.abs(next - target) < 0.01F ? target : next;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stamina = 100;
        exhausted = false;
        load = 0.0F;
        walking = false;
        forward = strafe = 0.0F;
    }
}
