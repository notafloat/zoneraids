package com.zoneraids.client;

import com.mojang.logging.LogUtils;
import com.zoneraids.ZoneRaids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * The roll on its own key (C). Moves Like Mafuyu only rolls on a sprint press while moving; its sprint trigger
 * is switched off in its config, and this key starts its roll directly, which also works standing still
 * (forward, or towards the held movement keys). Mafuyu's own cooldown is not checked by that entry point,
 * so the cooldown lives here.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RollClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks between the end of one roll and the next: a dash into cover, not a way to move. */
    private static final int COOLDOWN = 60;

    private static Method startRoll;
    private static Method isRolling;
    private static boolean resolved;
    private static int cooldown;

    private RollClient() {
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (ModList.get().isLoaded("moveslikemafuyu")) {
                try {
                    Class<?> roll = Class.forName("com.mafuyu404.moveslikemafuyu.event.RollEvent");
                    startRoll = roll.getMethod("startRoll", Player.class);
                    isRolling = roll.getMethod("isRolling", Player.class);
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("Zone Raids: Moves Like Mafuyu has no roll entry point, the roll key does nothing", e);
                }
            }
        }
        return startRoll != null;
    }

    private static boolean canRoll(LocalPlayer player) {
        Pose pose = player.getPose();
        return player.isAlive() && !player.isSpectator() && !player.isPassenger() && player.onGround()
                && !player.isInWater() && !player.isFallFlying()
                && !player.getTags().contains("slide") && !player.getTags().contains("craw")
                && (pose == Pose.STANDING || pose == Pose.CROUCHING);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !resolve()) {
            while (ClientSetup.ROLL.consumeClick()) {
                // drop presses while there is nothing to roll with
            }
            return;
        }
        try {
            boolean rolling = (boolean) isRolling.invoke(null, player);
            if (cooldown > 0 && !rolling) {
                cooldown--;
            }
            while (ClientSetup.ROLL.consumeClick()) {
                if (mc.screen == null && cooldown == 0 && !rolling && canRoll(player)
                        && (boolean) startRoll.invoke(null, player)) {
                    cooldown = COOLDOWN;
                    rolling = true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Zone Raids: Moves Like Mafuyu roll failed, the roll key is off", e);
            startRoll = null;
        }
    }
}
