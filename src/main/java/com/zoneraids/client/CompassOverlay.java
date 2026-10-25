package com.zoneraids.client;

import com.zoneraids.network.RaidHudPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Compass strip across the top of the screen, as on the PDA: a scale of azimuths (0 north, 90 east)
 * with a tick every 5 degrees, numbers every 15 and the cardinal letters every 45, the exact azimuth
 * under the centre notch, and pins at the bearing of the PDA map markers, the assigned exits and the
 * round target. A pin under the notch names its point and distance. Vanilla boss bars move down below it.
 */
public final class CompassOverlay implements IGuiOverlay {
    private static final int PX_PER_DEG = 2;
    private static final int MAX_HALF = 100;
    private static final int MIN_HALF = 60;
    private static final int TOP = 3;
    private static final int STRIP_H = 20;
    /** A pin this close to the notch, in degrees, is named in the readout. */
    private static final float LOCK_DEG = 4.0F;
    private static final String[] CARDINALS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};

    /** Lowest pixel row drawn last frame; boss bars are pushed below it. 0 when the compass is hidden. */
    private static int bottom;
    private static boolean bossShifted;

    private record Pin(String name, double x, double z, int color) {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || !ClientSettings.compass()) {
            bottom = 0;
            return;
        }
        Entity view = mc.getCameraEntity() != null ? mc.getCameraEntity() : player;
        Font font = mc.font;
        float heading = azimuth(view.getViewYRot(partialTick));

        // Centred, but kept clear of the raid panel in the top-right corner on narrow screens.
        int half = MAX_HALF;
        int cx = width / 2;
        int raidLeft = RaidHudOverlay.panelLeft;
        if (raidLeft >= 0 && cx + half + 6 > raidLeft) {
            half = Mth.clamp((raidLeft - 10) / 2, MIN_HALF, MAX_HALF);
            cx = Math.min(cx, raidLeft - half - 6);
        }
        int x0 = cx - half;
        int x1 = cx + half;
        int y0 = TOP;
        int y1 = y0 + STRIP_H;

        ZoneUi.panel(g, x0, y0, x1, y1, ZoneUi.BG_HUD);
        g.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        float span = (float) half / PX_PER_DEG;
        int first = Mth.floor((heading - span) / 5.0F) * 5;
        for (int deg = first; deg <= heading + span + 5; deg += 5) {
            int x = cx + Math.round((deg - heading) * PX_PER_DEG);
            int az = Math.floorMod(deg, 360);
            if (az % 45 == 0) {
                boolean main = az % 90 == 0;
                g.fill(x, y1 - 8, x + 1, y1 - 1, main ? ZoneUi.AMBER : ZoneUi.TEXT);
                g.drawCenteredString(font, cardinal(az), x + 1, y0 + 3, main ? ZoneUi.AMBER : ZoneUi.TEXT);
            } else if (az % 15 == 0) {
                g.fill(x, y1 - 6, x + 1, y1 - 1, ZoneUi.DIM);
                g.drawCenteredString(font, String.valueOf(az), x + 1, y0 + 3, ZoneUi.DIM);
            } else {
                g.fill(x, y1 - 4, x + 1, y1 - 1, ZoneUi.GREY);
            }
        }
        g.disableScissor();

        // Pins: inside the strip at their bearing, or held at its edge, dimmed, when out of view.
        Pin locked = null;
        float lockedOff = LOCK_DEG;
        for (Pin pin : pins(view)) {
            float off = Mth.wrapDegrees(bearing(view, pin.x(), pin.z()) - heading);
            boolean inside = Math.abs(off) * PX_PER_DEG <= half - 4;
            int px = inside ? cx + Math.round(off * PX_PER_DEG) : off < 0 ? x0 + 3 : x1 - 4;
            int color = inside ? pin.color() : (pin.color() & 0x00FFFFFF) | 0x80000000;
            pinMark(g, px, y1 - 7, color);
            if (Math.abs(off) < lockedOff) {
                lockedOff = Math.abs(off);
                locked = pin;
            }
        }

        // Centre notch and the readout below it.
        g.fill(cx, y0 + 1, cx + 1, y0 + 3, ZoneUi.AMBER);
        g.fill(cx - 2, y1, cx + 3, y1 + 1, ZoneUi.AMBER);
        g.fill(cx - 1, y1 + 1, cx + 2, y1 + 2, ZoneUi.AMBER);
        String deg = String.format("%03d°", Math.round(heading) % 360);
        Component dir = cardinal(sector(heading));
        int textW = font.width(deg) + 4 + font.width(dir);
        Component info = null;
        if (locked != null) {
            int dist = (int) Math.sqrt(sq(view.getX() - locked.x()) + sq(view.getZ() - locked.z()));
            info = Component.literal(ZoneUi.clip(font, locked.name(), 110) + " ")
                    .append(Component.translatable("zoneraids.hud.meters", dist));
        }
        int boxHalf = Math.max(textW, info == null ? 0 : font.width(info)) / 2 + 5;
        int by0 = y1 + 3;
        int by1 = by0 + 12 + (info == null ? 0 : 10);
        ZoneUi.panel(g, cx - boxHalf, by0, cx + boxHalf + 1, by1, ZoneUi.BG_HUD);
        int tx = cx + 1 - textW / 2;
        g.drawString(font, deg, tx, by0 + 2, ZoneUi.AMBER);
        g.drawString(font, dir, tx + font.width(deg) + 4, by0 + 2, ZoneUi.DIM);
        if (info != null) {
            g.drawCenteredString(font, info, cx + 1, by0 + 12, locked.color());
        }
        bottom = by1 + 2;
    }

    /** Points worth a pin: the PDA map markers of this dimension, the assigned exits and the round target. */
    private static List<Pin> pins(Entity view) {
        List<Pin> pins = new ArrayList<>();
        for (WorldMap.Marker marker : WorldMap.markers()) {
            pins.add(new Pin(marker.name, marker.x + 0.5, marker.z + 0.5, marker.rgb()));
        }
        RaidHudPacket hud = ClientRaidState.hud;
        if (hud != null && !hud.round().intermission()) {
            RaidHudPacket.Round round = hud.round();
            if (round.hasTarget()) {
                pins.add(new Pin("◆ " + round.targetLabel().getString(), round.tx(), round.tz(), ZoneUi.AMBER));
            }
            for (RaidHudPacket.ExtractEntry extract : hud.extracts()) {
                if (extract.hasPos()) {
                    pins.add(new Pin(extract.name(), extract.x(), extract.z(), ZoneUi.GREEN));
                }
            }
        }
        return pins;
    }

    /** A small diamond with a dark rim, so it reads over the ticks. */
    private static void pinMark(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 1, y - 2, x + 2, y + 5, 0xC0000000);
        g.fill(x - 2, y - 1, x + 3, y + 4, 0xC0000000);
        g.fill(x, y - 1, x + 1, y + 4, color);
        g.fill(x - 1, y, x + 2, y + 3, color);
    }

    /** Minecraft yaw (0 south, 90 west) as an azimuth in [0, 360): 0 north, 90 east. */
    static float azimuth(float yaw) {
        float az = (yaw + 180.0F) % 360.0F;
        return az < 0 ? az + 360.0F : az;
    }

    /** Azimuth from the viewer to the point {@code x, z}. */
    static float bearing(Entity view, double x, double z) {
        float az = (float) Math.toDegrees(Math.atan2(x - view.getX(), view.getZ() - z));
        return az < 0 ? az + 360.0F : az;
    }

    /** The cardinal or intercardinal sector the azimuth falls in, as its multiple of 45. */
    private static int sector(float az) {
        return (Math.round(az / 45.0F) % 8) * 45;
    }

    private static Component cardinal(int az) {
        return Component.translatable("zoneraids.compass." + CARDINALS[(az / 45) % 8]);
    }

    /**
     * Lowest priority, so it runs only when nothing cancelled the boss bars: they are shifted below the
     * compass, and {@link #onOverlayPost} undoes it.
     */
    static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (bottom > 0 && event.getOverlay().id().equals(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id())) {
            event.getGuiGraphics().pose().pushPose();
            // Vanilla draws the first boss name at y 3.
            event.getGuiGraphics().pose().translate(0, bottom - 2, 0);
            bossShifted = true;
        }
    }

    static void onOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (bossShifted && event.getOverlay().id().equals(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id())) {
            event.getGuiGraphics().pose().popPose();
            bossShifted = false;
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
