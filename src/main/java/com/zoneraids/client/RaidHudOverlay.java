package com.zoneraids.client;

import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.raid.RaidManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.List;

/** Top-right raid panel: map, round, time left, objective, assigned exits and extraction progress. */
public final class RaidHudOverlay implements IGuiOverlay {
    private static final int BG = 0x90000000;
    private static final int RED = 0xFFE04040;
    private static final int WHITE = 0xFFE8E8E8;
    private static final int GREY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF60D060;
    private static final int GOLD = 0xFFE0C050;

    private record Line(Component text, int color) {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        RaidHudPacket hud = ClientRaidState.hud;
        Minecraft mc = Minecraft.getInstance();
        if (hud == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        long elapsed = (Util.getMillis() - ClientRaidState.hudReceivedAt) / 1000L;
        int seconds = (int) Math.max(0, hud.secondsLeft() - elapsed);
        RaidHudPacket.Round round = hud.round();
        boolean rounds = round.count() > 1;
        Component mode = Component.translatable("zoneraids.round." + round.type());

        List<Line> lines = new ArrayList<>();
        if (rounds && round.intermission()) {
            lines.add(new Line(Component.translatable("zoneraids.hud.session", hud.mapName()), GOLD));
            lines.add(new Line(Component.translatable("zoneraids.hud.round", round.number(), round.count(), mode), WHITE));
            lines.add(new Line(round.status().copy().append(" " + RaidManager.formatTime(seconds)), WHITE));
        } else {
            lines.add(new Line(Component.translatable("zoneraids.hud.raid", hud.mapName()), RED));
            if (rounds) {
                lines.add(new Line(Component.translatable("zoneraids.hud.round", round.number(), round.count(), mode), GOLD));
            }
            lines.add(new Line(Component.translatable("zoneraids.hud.time", RaidManager.formatTime(seconds)), seconds <= 60 ? RED : WHITE));
            if (round.hasTarget()) {
                int dist = (int) Math.sqrt(sq(mc.player.getX() - round.tx()) + sq(mc.player.getZ() - round.tz()));
                lines.add(new Line(Component.translatable("zoneraids.hud.target", round.targetLabel(), dist), GOLD));
            }
            if (!round.status().getString().isEmpty()) {
                lines.add(new Line(round.status(), WHITE));
            }
            for (RaidHudPacket.ExtractEntry extract : hud.extracts()) {
                Component line;
                if (extract.hasPos()) {
                    int dist = (int) Math.sqrt(mc.player.distanceToSqr(extract.x(), extract.y(), extract.z()));
                    line = Component.translatable("zoneraids.hud.extract_dist", extract.name(), dist);
                } else {
                    line = Component.translatable("zoneraids.hud.extract", extract.name());
                }
                lines.add(new Line(line, extract.name().equals(hud.extracting()) ? GREEN : GREY));
            }
        }

        boolean extractBar = hud.progress() >= 0;
        boolean objectiveBar = round.progress() >= 0;
        int panelW = 150;
        for (Line line : lines) {
            panelW = Math.max(panelW, font.width(line.text()) + 4);
        }
        int x = width - panelW - 6;
        int y = 6;
        g.fill(x - 4, y - 4, x + panelW, y + lines.size() * 11 + (extractBar ? 16 : 0) + (objectiveBar ? 6 : 0), BG);
        for (Line line : lines) {
            g.drawString(font, line.text(), x, y, line.color());
            y += 11;
        }
        int barW = panelW - 8;
        if (objectiveBar) {
            g.fill(x, y, x + barW, y + 4, 0xFF303030);
            g.fill(x, y, x + (int) (barW * round.progress()), y + 4, GOLD);
            y += 6;
        }
        if (extractBar) {
            g.drawString(font, Component.translatable("zoneraids.hud.extracting", hud.extracting()), x, y, GREEN);
            y += 10;
            g.fill(x, y, x + barW, y + 4, 0xFF303030);
            g.fill(x, y, x + (int) (barW * hud.progress()), y + 4, GREEN);
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
