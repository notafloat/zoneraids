package com.zoneraids.client;

import com.zoneraids.util.FirstAidCompat;
import com.zoneraids.util.FirstAidCompat.Part;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Shared look of the mod's screens and HUD: a worn PDA in the spirit of S.T.A.L.K.E.R. Everything is
 * plain fills and text (no textures, no shaders), so it costs nothing on weak GPUs.
 */
public final class ZoneUi {
    public static final int BG = 0xE8141510;
    public static final int BG_HUD = 0xB0101109;
    public static final int HEADER = 0xF0262619;
    public static final int ROW = 0x701E2016;
    public static final int ROW_HOVER = 0x90303424;
    public static final int EDGE = 0xFF45452F;
    public static final int TROUGH = 0xFF20211A;

    public static final int AMBER = 0xFFD9A441;
    public static final int TEXT = 0xFFDCD8C2;
    public static final int DIM = 0xFF8E8C76;
    public static final int RED = 0xFFD84A30;
    public static final int GREEN = 0xFF8CC060;
    public static final int GREY = 0xFF6C6C60;

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private ZoneUi() {
    }

    /** Dark panel with a thin olive edge and amber corner brackets. */
    public static void panel(GuiGraphics g, int x0, int y0, int x1, int y1, int bg) {
        g.fill(x0, y0, x1, y1, bg);
        outline(g, x0, y0, x1, y1, EDGE);
        corners(g, x0, y0, x1, y1, AMBER, 6);
    }

    public static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    public static void corners(GuiGraphics g, int x0, int y0, int x1, int y1, int color, int len) {
        g.fill(x0 - 1, y0 - 1, x0 + len, y0, color);
        g.fill(x0 - 1, y0, x0, y0 + len, color);
        g.fill(x1 - len, y0 - 1, x1 + 1, y0, color);
        g.fill(x1, y0, x1 + 1, y0 + len, color);
        g.fill(x0 - 1, y1, x0 + len, y1 + 1, color);
        g.fill(x0 - 1, y1 - len, x0, y1, color);
        g.fill(x1 - len, y1, x1 + 1, y1 + 1, color);
        g.fill(x1, y1 - len, x1 + 1, y1, color);
    }

    /** Header strip across the top of a panel: title on the left, optional text on the right, amber rule below. */
    public static void header(GuiGraphics g, Font font, int x0, int y0, int x1, Component title, int titleColor,
                              Component right, int rightColor) {
        g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 15, HEADER);
        g.fill(x0 + 1, y0 + 15, x1 - 1, y0 + 16, 0x80D9A441);
        g.drawString(font, title, x0 + 6, y0 + 4, titleColor);
        if (right != null) {
            g.drawString(font, right, x1 - 6 - font.width(right), y0 + 4, rightColor);
        }
    }

    /** Segmented progress bar, like the PDA meters. */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float progress, int color) {
        g.fill(x, y, x + w, y + h, TROUGH);
        int filled = (int) (w * Mth.clamp(progress, 0.0F, 1.0F));
        for (int sx = 0; sx < filled; sx += 4) {
            g.fill(x + sx, y, x + Math.min(sx + 3, filled), y + h, color);
        }
    }

    /** Arrow pointing from the viewer (at {@code px, pz}, facing {@code yaw}) to the point {@code tx, tz}. */
    public static String arrow(double px, double pz, float yaw, double tx, double tz) {
        float target = (float) Math.toDegrees(Math.atan2(-(tx - px), tz - pz));
        float rel = Mth.wrapDegrees(target - yaw);
        return ARROWS[(Math.round(rel / 45.0F) + 8) % 8];
    }

    /** Text cut to fit {@code maxWidth}, with an ellipsis. */
    public static String clip(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    /** Green, amber or red by health fraction; dark when the part is at zero. */
    public static int healthColor(float fraction) {
        return fraction <= 0.0F ? 0xFF2C2C26 : fraction < 0.34F ? RED : fraction < 0.67F ? AMBER : GREEN;
    }

    /** Height in pixels of {@link #body} drawn with unit {@code u}. */
    public static int bodyHeight(int u) {
        int gap = Math.max(1, u / 3);
        return 15 * u + 3 * gap + Math.max(2, 3 * u / 2);
    }

    /**
     * Front view of a body, each limb coloured by its First Aid health (the player's left limbs on the
     * viewer's right). Without First Aid data every part shows {@code fallback}, the vanilla health fraction.
     */
    public static void body(GuiGraphics g, int cx, int y, int u, @Nullable FirstAidCompat.Snapshot s, float fallback) {
        int gap = Math.max(1, u / 3);
        int bodyTop = y + 4 * u + gap;
        int bodyBottom = bodyTop + 6 * u;
        int legTop = bodyBottom + gap;
        int legBottom = legTop + 5 * u;
        int footTop = legBottom + gap;
        int footBottom = footTop + Math.max(2, 3 * u / 2);
        int legInner = Math.max(1, gap / 2);
        limb(g, cx - 2 * u, y, cx + 2 * u, y + 4 * u, s, Part.HEAD, fallback);
        limb(g, cx - 2 * u, bodyTop, cx + 2 * u, bodyBottom, s, Part.BODY, fallback);
        limb(g, cx + 2 * u + gap, bodyTop, cx + 4 * u + gap, bodyBottom, s, Part.LEFT_ARM, fallback);
        limb(g, cx - 4 * u - gap, bodyTop, cx - 2 * u - gap, bodyBottom, s, Part.RIGHT_ARM, fallback);
        limb(g, cx + legInner, legTop, cx + 2 * u, legBottom, s, Part.LEFT_LEG, fallback);
        limb(g, cx - 2 * u, legTop, cx - legInner, legBottom, s, Part.RIGHT_LEG, fallback);
        limb(g, cx + legInner, footTop, cx + 2 * u, footBottom, s, Part.LEFT_FOOT, fallback);
        limb(g, cx - 2 * u, footTop, cx - legInner, footBottom, s, Part.RIGHT_FOOT, fallback);
    }

    private static void limb(GuiGraphics g, int x0, int y0, int x1, int y1, @Nullable FirstAidCompat.Snapshot s, Part part, float fallback) {
        float fraction = s == null ? fallback : s.fraction(part);
        g.fill(x0, y0, x1, y1, healthColor(fraction));
        if (x1 - x0 >= 6) {
            outline(g, x0, y0, x1, y1, 0x60000000);
        }
    }

    /** Dark inventory slot frame around an item at {@code x, y}. */
    public static void slotFrame(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xE00C0D09);
        outline(g, x - 1, y - 1, x + 17, y + 17, EDGE);
    }

    /** Badge for a contamination type: a dark square with the hazard's sign, edged and coloured by its level. */
    public static void hazard(GuiGraphics g, Font font, int x, int y, int size, String glyph, int color) {
        g.fill(x, y, x + size, y + size, 0xC0000000);
        outline(g, x, y, x + size, y + size, color);
        g.drawCenteredString(font, glyph, x + size / 2, y + (size - 8) / 2, color);
    }

    /** Green when there is none, amber while harmless, red once it hurts. */
    public static int hazardColor(double value, double harmful) {
        return value <= 0 ? GREEN : value > harmful ? RED : AMBER;
    }
}
