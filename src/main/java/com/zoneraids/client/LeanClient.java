package com.zoneraids.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zoneraids.ZoneRaids;
import com.zoneraids.network.LeanPacket;
import com.zoneraids.network.RaidNetwork;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Leaning on Q/E: the leaning player's camera rolls and moves sideways to where the tilted head is, and every
 * client draws the whole body tilted sideways, pivoting at the feet. Both are interpolated per frame, so the lean eases in and out instead of stepping.
 *
 * TACtical Movement's own lean (camera roll set per tick, torso animation that snaps back on release) looked
 * jerky, so its keys are unbound by KeyLayout and Zone Raids has its own. The local player's lean is sent to
 * the server, which relays it to everyone who sees the player (see com.zoneraids.lean.Lean).
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LeanClient {
    private static final float MAX_ANGLE = 9.0F;
    /** Camera roll per degree of body tilt: the view tilts less than the body, enough to feel the lean. */
    private static final float CAMERA_ROLL = 0.8F;
    private static final float SPEED = 0.3F;

    /** entity id -> {previous angle, current angle, target direction}. */
    private static final Map<Integer, float[]> TILTS = new HashMap<>();
    private static int sent;
    private static boolean pushed;
    private static Method cameraSetter;
    private static boolean cameraSetterResolved;

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
        // Standing still, the body turns after the head only past 50 degrees, so a lean along the body would go
        // off to the side of where the player looks. While leaning, the body follows the view.
        player.yBodyRotO = player.yHeadRotO;
        player.yBodyRot = player.yHeadRot;
        float yaw = Mth.rotLerp(event.getPartialTick(), player.yHeadRotO, player.yHeadRot);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // Turn so that +Z is where the player looks, roll about it (positive = to the player's right), turn back.
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

    /**
     * LOWEST, so the roll is added after other mods (TACtical Movement, Camera Overhaul, TaCZ) have set theirs;
     * one of them setting the roll outright after this handler made the lean invisible in first person.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        float[] tilt = TILTS.get(player.getId());
        if (tilt == null) {
            return;
        }
        float angle = Mth.lerp((float) event.getPartialTick(), tilt[0], tilt[1]);
        if (Math.abs(angle) < 0.05F) {
            return;
        }
        event.setRoll(event.getRoll() + angle * CAMERA_ROLL);
        peek(event.getCamera(), player, angle, event.getYaw(), (float) event.getPartialTick());
    }

    /**
     * Moves the first-person camera to where the tilted head is, as other players see it: sideways by
     * sin(angle) of the eye height and a little down. Stops short of walls so the lean cannot look through them.
     */
    private static void peek(Camera camera, LocalPlayer player, float angle, float yaw, float partialTick) {
        Method setter = cameraSetter();
        if (setter == null) {
            return;
        }
        float rad = angle * Mth.DEG_TO_RAD;
        double side = Mth.sin(rad) * player.getEyeHeight();
        double down = (1.0F - Mth.cos(rad)) * player.getEyeHeight();
        float yawRad = yaw * Mth.DEG_TO_RAD;
        // The player's right when looking along yaw: yaw 0 looks to +Z, the right hand is -X.
        Vec3 offset = new Vec3(-Mth.cos(yawRad) * side, -down, -Mth.sin(yawRad) * side);
        Vec3 from = camera.getPosition();
        Vec3 to = from.add(offset);
        BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS) {
            double reach = Math.max(0.0, hit.getLocation().distanceTo(from) - 0.1);
            to = from.add(offset.normalize().scale(Math.min(reach, offset.length())));
        }
        try {
            setter.invoke(camera, to);
        } catch (ReflectiveOperationException | RuntimeException e) {
            cameraSetter = null;
            cameraSetterResolved = true;
        }
    }

    /** Camera.setPosition(Vec3) is protected; found by signature so it works with and without obfuscated names. */
    private static Method cameraSetter() {
        if (!cameraSetterResolved) {
            cameraSetterResolved = true;
            for (Method m : Camera.class.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] == Vec3.class && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    cameraSetter = m;
                    break;
                }
            }
        }
        return cameraSetter;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TILTS.clear();
        sent = 0;
    }
}
