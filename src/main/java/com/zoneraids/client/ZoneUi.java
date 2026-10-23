package com.zoneraids.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

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
}
