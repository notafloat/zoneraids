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

/** Top-right raid panel: map, time left, assigned exits and extraction progress. */
public final class RaidHudOverlay implements IGuiOverlay {
    private static final int BG = 0x90000000;
    private static final int RED = 0xFFE04040;
    private static final int WHITE = 0xFFE8E8E8;
    private static final int GREY = 0xFFA0A0A0;
    private static final int GREEN = 0xFF60D060;

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

        int lines = 2 + hud.extracts().size();
        int panelW = 150;
        int x = width - panelW - 6;
        int y = 6;
        g.fill(x - 4, y - 4, x + panelW, y + lines * 11 + (hud.progress() >= 0 ? 16 : 0), BG);

        g.drawString(font, Component.translatable("zoneraids.hud.raid", hud.mapName()), x, y, RED);
        y += 11;
        g.drawString(font, Component.translatable("zoneraids.hud.time", RaidManager.formatTime(seconds)), x, y, seconds <= 60 ? RED : WHITE);
        y += 11;
        for (RaidHudPacket.ExtractEntry extract : hud.extracts()) {
            Component line;
            if (extract.hasPos()) {
                int dist = (int) Math.sqrt(mc.player.distanceToSqr(extract.x(), extract.y(), extract.z()));
                line = Component.translatable("zoneraids.hud.extract_dist", extract.name(), dist);
            } else {
                line = Component.translatable("zoneraids.hud.extract", extract.name());
            }
            g.drawString(font, line, x, y, extract.name().equals(hud.extracting()) ? GREEN : GREY);
            y += 11;
        }
        if (hud.progress() >= 0) {
            g.drawString(font, Component.translatable("zoneraids.hud.extracting", hud.extracting()), x, y, GREEN);
            y += 10;
            int barW = panelW - 8;
            g.fill(x, y, x + barW, y + 4, 0xFF303030);
            g.fill(x, y, x + (int) (barW * hud.progress()), y + 4, GREEN);
        }
    }
}
