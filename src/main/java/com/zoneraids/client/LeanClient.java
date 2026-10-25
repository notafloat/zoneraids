package com.zoneraids.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zoneraids.ZoneRaids;
import com.zoneraids.network.LeanPacket;
import com.zoneraids.network.RaidNetwork;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
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
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
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
    private static final float MAX_ANGLE = 15.0F;
    /** Camera roll per degree of body tilt: the view tilts less than the body, enough to feel the lean. */
    private static final float CAMERA_ROLL = 0.8F;
    private static final float SPEED = 0.3F;

    /** Mouse wheel step of the lean (fraction of a full lean) and of the stance height. */
    private static final float STEP = 0.1F;
    /** First-person camera drop at the lowest standing stance (just above crouch) and below a full crouch. */
    private static final float STAND_DROP = 0.3F;
    private static final float CROUCH_DROP = 0.25F;

    /** entity id -> {previous angle, current angle, target lean from -1 to 1}. */
    private static final Map<Integer, float[]> TILTS = new HashMap<>();
    private static int sent;
    private static boolean pushed;
    private static Method cameraSetter;
    private static boolean cameraSetterResolved;
    /** The local player's stored lean (a Q/E toggle or the wheel lean) and stance height (1 standing, 0 lowest). */
    private static float lean;
    private static float stance = 1.0F;
    private static float stancePrev = 1.0F;
    private static float stanceCur = 1.0F;

    private LeanClient() {
    }

    public static void setAmount(int entityId, int amount) {
        TILTS.computeIfAbsent(entityId, id -> new float[3])[2] = Mth.clamp(amount, -100, 100) / 100.0F;
    }

    private static boolean canLean(Player player) {
        Pose pose = player.getPose();
        return player.isAlive() && !player.isSpectator() && !player.isPassenger() && !player.isSleeping()
                && (pose == Pose.STANDING || pose == Pose.CROUCHING);
    }

    /** Whether a modifier is physically held; its key may be shared (Alt with tactical sprint, Ctrl with sprint). */
    static boolean held(KeyMapping key) {
        InputConstants.Key k = key.getKey();
        if (k.getType() == InputConstants.Type.KEYSYM && k.getValue() != InputConstants.UNKNOWN.getValue()) {
            return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), k.getValue());
        }
        return key.isDown();
    }

    /**
     * Escape from Tarkov's lean controls: Q/E lean fully (toggled or held, see ClientSettings), the lean modifier
     * (Alt) with the wheel leans by steps and the lean stays, the stance modifier (Ctrl) with the wheel lowers
     * or raises the stance. A press of Q/E clears a wheel lean; sprinting stands up straight.
     */
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
        boolean toggle = ClientSettings.toggle();
        while (ClientSetup.LEAN_LEFT.consumeClick()) {
            lean = toggle && lean > -0.99F ? -1.0F : 0.0F;
        }
        while (ClientSetup.LEAN_RIGHT.consumeClick()) {
            lean = toggle && lean < 0.99F ? 1.0F : 0.0F;
        }
        if (!player.isAlive() || player.isSprinting()) {
            lean = 0.0F;
            stance = 1.0F;
        }
        float target = 0.0F;
        if (canLean(player)) {
            target = lean;
            if (!toggle && (ClientSetup.LEAN_LEFT.isDown() || ClientSetup.LEAN_RIGHT.isDown())) {
                target = (ClientSetup.LEAN_RIGHT.isDown() ? 1 : 0) - (ClientSetup.LEAN_LEFT.isDown() ? 1 : 0);
            }
        }
        int amount = Math.round(target * 100.0F);
        if (amount != sent) {
            sent = amount;
            RaidNetwork.sendToServer(new LeanPacket(amount));
        }
        setAmount(player.getId(), amount);
        stancePrev = stanceCur;
        stanceCur += (stance - stanceCur) * SPEED;

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

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || event.getScrollDelta() == 0) {
            return;
        }
        float step = event.getScrollDelta() > 0 ? STEP : -STEP;
        if (held(ClientSetup.LEAN_ADJUST)) {
            // Wheel up leans right, down leans left, in tenths of a full lean.
            lean = Mth.clamp(Math.round((lean + step) * 10.0F) / 10.0F, -1.0F, 1.0F);
            event.setCanceled(true);
        } else if (held(ClientSetup.STANCE_ADJUST)) {
            stance = Mth.clamp(Math.round((stance + step) * 10.0F) / 10.0F, 0.0F, 1.0F);
            event.setCanceled(true);
        }
    }

    /** Below half height the stance is a real crouch: hitbox, speed and the pose others see. */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (stance < 0.5F && canLean(event.getEntity()) && !event.getEntity().isSprinting()) {
            event.getInput().shiftKeyDown = true;
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
        float partialTick = (float) event.getPartialTick();
        float[] tilt = TILTS.get(player.getId());
        float angle = tilt == null ? 0.0F : Mth.lerp(partialTick, tilt[0], tilt[1]);
        float height = Mth.lerp(partialTick, stancePrev, stanceCur);
        float drop = 0.0F;
        if (player.getPose() == Pose.STANDING) {
            drop = (1.0F - Math.max(height, 0.5F)) * 2.0F * STAND_DROP;
        } else if (player.getPose() == Pose.CROUCHING && height < 0.5F) {
            drop = (0.5F - height) * 2.0F * CROUCH_DROP;
        }
        if (Math.abs(angle) < 0.05F && drop < 0.005F) {
            return;
        }
        event.setRoll(event.getRoll() + angle * CAMERA_ROLL);
        peek(event.getCamera(), player, angle, drop, event.getYaw());
    }

    /**
     * Moves the first-person camera to where the tilted head is, as other players see it: sideways by
     * sin(angle) of the eye height and a little down, plus the stance drop. Stops short of walls so the lean
     * cannot look through them.
     */
    private static void peek(Camera camera, LocalPlayer player, float angle, float drop, float yaw) {
        Method setter = cameraSetter();
        if (setter == null) {
            return;
        }
        float rad = angle * Mth.DEG_TO_RAD;
        double side = Mth.sin(rad) * player.getEyeHeight();
        double down = (1.0F - Mth.cos(rad)) * player.getEyeHeight() + drop;
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
        lean = 0.0F;
        stance = stancePrev = stanceCur = 1.0F;
    }
}
