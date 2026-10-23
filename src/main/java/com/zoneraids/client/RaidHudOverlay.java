package com.zoneraids.client;

import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.raid.RaidManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-right raid panel in the PDA style: map and time left in the header, then round, objective with a
 * direction arrow, assigned exits with arrows and distances, and the extraction meter. The panel moves
 * down below the vanilla effect icons so they never overlap.
 */
public final class RaidHudOverlay implements IGuiOverlay {
    private static final int MIN_W = 150;
    private static final int LINE_H = 11;

    /** A HUD line: text on the left, optional arrow and distance right-aligned. */
    private record Row(Component left, @Nullable Component right, int color) {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        RaidHudPacket hud = ClientRaidState.hud;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (hud == null || player == null || mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        long elapsed = (Util.getMillis() - ClientRaidState.hudReceivedAt) / 1000L;
        int seconds = (int) Math.max(0, hud.secondsLeft() - elapsed);
        RaidHudPacket.Round round = hud.round();
        boolean rounds = round.count() > 1;
        boolean intermission = rounds && round.intermission();
        Component mode = Component.translatable("zoneraids.round." + round.type());

        Component title = Component.translatable(intermission ? "zoneraids.hud.session" : "zoneraids.hud.raid", hud.mapName());
        Component timer = Component.literal(RaidManager.formatTime(seconds));
        boolean blink = !intermission && seconds <= 60 && (Util.getMillis() / 500L) % 2 == 0;
        int timerColor = !intermission && seconds <= 60 ? (blink ? ZoneUi.RED : ZoneUi.TEXT) : ZoneUi.TEXT;

        List<Row> rows = new ArrayList<>();
        int exitsHeader = -1;
        if (rounds) {
            rows.add(new Row(Component.translatable("zoneraids.hud.round", round.number(), round.count(), mode), null, ZoneUi.AMBER));
        }
        if (intermission) {
            rows.add(new Row(round.status().copy().append(" " + RaidManager.formatTime(seconds)), null, ZoneUi.TEXT));
        } else {
            if (round.hasTarget()) {
                rows.add(new Row(Component.literal("◆ ").append(round.targetLabel()),
                        direction(player, round.tx(), round.tz()), ZoneUi.AMBER));
            }
            if (!round.status().getString().isEmpty()) {
                rows.add(new Row(round.status(), null, ZoneUi.TEXT));
            }
            if (!hud.extracts().isEmpty()) {
                exitsHeader = rows.size();
                rows.add(new Row(Component.translatable("zoneraids.hud.exits"), null, ZoneUi.DIM));
            }
            for (RaidHudPacket.ExtractEntry extract : hud.extracts()) {
                boolean here = extract.name().equals(hud.extracting());
                rows.add(new Row(Component.literal((here ? "● " : "○ ") + extract.name()),
                        extract.hasPos() ? direction(player, extract.x(), extract.z()) : null,
                        here ? ZoneUi.GREEN : ZoneUi.TEXT));
            }
        }

        boolean objectiveBar = round.progress() >= 0;
        boolean extractBar = hud.progress() >= 0;
        int panelW = Math.max(MIN_W, font.width(title) + font.width(timer) + 20);
        for (Row row : rows) {
            int w = font.width(row.left()) + (row.right() == null ? 0 : font.width(row.right()) + 12);
            panelW = Math.max(panelW, w + 12);
        }
        int panelH = 16 + 3 + rows.size() * LINE_H + (objectiveBar ? 6 : 0) + (extractBar ? 18 : 0) + 2;
        int x0 = width - panelW - 4;
        int y0 = topOffset(player);
        int x1 = x0 + panelW;

        ZoneUi.panel(g, x0, y0, x1, y0 + panelH, ZoneUi.BG_HUD);
        ZoneUi.header(g, font, x0, y0, x1, title, intermission ? ZoneUi.AMBER : ZoneUi.RED, timer, timerColor);

        int y = y0 + 19;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i == exitsHeader) {
                g.fill(x0 + 6, y - 1, x1 - 6, y, 0x40D9A441);
            }
            g.drawString(font, row.left(), x0 + 6, y + 1, row.color());
            if (row.right() != null) {
                g.drawString(font, row.right(), x1 - 6 - font.width(row.right()), y + 1, row.color() == ZoneUi.TEXT ? ZoneUi.DIM : row.color());
            }
            y += LINE_H;
        }
        int barW = panelW - 12;
        if (objectiveBar) {
            ZoneUi.bar(g, x0 + 6, y + 1, barW, 3, round.progress(), ZoneUi.AMBER);
            y += 6;
        }
        if (extractBar) {
            g.drawString(font, Component.translatable("zoneraids.hud.extracting", hud.extracting()), x0 + 6, y + 1, ZoneUi.GREEN);
            Component pct = Component.literal(Math.round(hud.progress() * 100) + "%");
            g.drawString(font, pct, x1 - 6 - font.width(pct), y + 1, ZoneUi.GREEN);
            ZoneUi.bar(g, x0 + 6, y + 11, barW, 4, hud.progress(), ZoneUi.GREEN);
        }
    }

    private static Component direction(LocalPlayer player, double x, double z) {
        int dist = (int) Math.sqrt(sq(player.getX() - x) + sq(player.getZ() - z));
        String arrow = ZoneUi.arrow(player.getX(), player.getZ(), player.getYRot(), x, z);
        return Component.literal(arrow + " ").append(Component.translatable("zoneraids.hud.meters", dist));
    }

    /** Vanilla draws beneficial effect icons in a row at y 1..25 and harmful ones at 26..50 in this corner. */
    private static int topOffset(LocalPlayer player) {
        boolean good = false;
        boolean bad = false;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.showIcon()) {
                continue;
            }
            if (effect.getEffect().isBeneficial()) {
                good = true;
            } else {
                bad = true;
            }
        }
        return bad ? 54 : good ? 29 : 5;
    }

    private static double sq(double v) {
        return v * v;
    }
}
