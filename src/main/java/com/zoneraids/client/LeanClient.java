package com.zoneraids.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zoneraids.ZoneRaids;
import com.zoneraids.network.LeanPacket;
import com.zoneraids.network.RaidNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Leaning on Q/E: the leaning player's camera rolls and every client draws the whole body tilted sideways,
 * pivoting at the feet. Both are interpolated per frame, so the lean eases in and out instead of stepping.
 *
 * TACtical Movement's own lean (camera roll set per tick, torso animation that snaps back on release) looked
 * jerky, so its keys are unbound by KeyLayout and Zone Raids has its own. The local player's lean is sent to
 * the server, which relays it to everyone who sees the player (see com.zoneraids.lean.Lean).
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LeanClient {
    private static final float MAX_ANGLE = 16.0F;
    /** Camera roll per degree of body tilt: the view tilts less than the body, enough to feel the lean. */
    private static final float CAMERA_ROLL = 0.6F;
    private static final float SPEED = 0.3F;

    /** entity id -> {previous angle, current angle, target direction}. */
    private static final Map<Integer, float[]> TILTS = new HashMap<>();
    private static int sent;
    private static boolean pushed;

    private LeanClient() {
    }

    public static void setDirection(int entityId, int direction) {
        TILTS.computeIfAbsent(entityId, id -> new float[3])[2] = Mth.clamp(direction, -1, 1);
    }

    private static boolean canLean(Player player) {
        Pose pose = player.getPose();
        return player.isAlive() && !player.isSpectator() && !player.isPassenger() && !player.isSleeping()
                && (pose == Pose.STANDING || pose == Pose.CROUCHING);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        int direction = 0;
        if (canLean(player)) {
            direction = (ClientSetup.LEAN_RIGHT.isDown() ? 1 : 0) - (ClientSetup.LEAN_LEFT.isDown() ? 1 : 0);
        }
        if (direction != sent) {
            sent = direction;
            RaidNetwork.sendToServer(new LeanPacket(direction));
        }
        setDirection(player.getId(), direction);

        Iterator<Map.Entry<Integer, float[]>> it = TILTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, float[]> entry = it.next();
            float[] tilt = entry.getValue();
            tilt[0] = tilt[1];
            tilt[1] += (tilt[2] * MAX_ANGLE - tilt[1]) * SPEED;
            boolean gone = mc.level.getEntity(entry.getKey()) == null;
            if (gone || (tilt[2] == 0 && Math.abs(tilt[1]) < 0.05F && Math.abs(tilt[0]) < 0.05F)) {
                it.remove();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPre(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        float[] tilt = TILTS.get(player.getId());
        if (tilt == null || !canLean(player)) {
            return;
        }
        float angle = Mth.lerp(event.getPartialTick(), tilt[0], tilt[1]);
        if (Math.abs(angle) < 0.05F) {
            return;
        }
        float yaw = Mth.rotLerp(event.getPartialTick(), player.yBodyRotO, player.yBodyRot);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // Turn so that +Z is where the body faces, roll about it (positive = to the player's right), turn back.
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.ZP.rotationDegrees(angle));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pushed = true;
    }

    @SubscribeEvent
    public static void onRenderPost(RenderPlayerEvent.Post event) {
        if (pushed) {
            pushed = false;
            event.getPoseStack().popPose();
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        float[] tilt = TILTS.get(mc.player.getId());
        if (tilt != null) {
            float angle = Mth.lerp((float) event.getPartialTick(), tilt[0], tilt[1]);
            event.setRoll(event.getRoll() + angle * CAMERA_ROLL);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TILTS.clear();
        sent = 0;
    }
}
