package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.world.ZoneDimensions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Full-screen paper map of the Cordon. The game keeps running behind it, as reading a map in a
 * raid should cost attention. The holder's position, their assigned exits and the round target are
 * marked only while they are on the Cordon.
 */
public final class CordonMapScreen extends Screen {
    private static final ResourceLocation TEXTURE = new ResourceLocation(ZoneRaids.MODID, "textures/gui/cordon_map.png");
    private static final int TEXTURE_SIZE = 512;
    /** The texture covers blocks -160..159 on both axes. */
    private static final int MAP_MIN = -160;
    private static final int MAP_SIZE = 320;

    private static final int COLOR_FRAME = 0xFF3A2E20;
    private static final int COLOR_MARKER = 0xFFD02020;
    private static final int COLOR_MARKER_EDGE = 0xFF000000;
    private static final int COLOR_TEXT = 0xFFE0E0D0;
    private static final int COLOR_EXIT = 0xFF3C9A3C;
    private static final int COLOR_TARGET = 0xFFE0A020;

    public CordonMapScreen() {
        super(Component.translatable("item.zoneraids.cordon_map"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int size = Math.max(64, Math.min(width, height) - 24);
        int x0 = (width - size) / 2;
        int y0 = (height - size) / 2;
        graphics.fill(x0 - 3, y0 - 3, x0 + size + 3, y0 + size + 3, COLOR_FRAME);
        ZoneUi.corners(graphics, x0 - 3, y0 - 3, x0 + size + 3, y0 + size + 3, ZoneUi.AMBER, 10);
        graphics.blit(TEXTURE, x0, y0, size, size, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);

        Player player = minecraft == null ? null : minecraft.player;
        if (player != null && player.level().dimension().equals(ZoneDimensions.CORDON)) {
            RaidHudPacket hud = ClientRaidState.hud;
            if (hud != null && !hud.round().intermission()) {
                for (RaidHudPacket.ExtractEntry extract : hud.extracts()) {
                    if (extract.hasPos()) {
                        point(graphics, x0, y0, size, extract.x(), extract.z(), COLOR_EXIT, extract.name());
                    }
                }
                if (hud.round().hasTarget()) {
                    point(graphics, x0, y0, size, hud.round().tx(), hud.round().tz(), COLOR_TARGET, hud.round().targetLabel().getString());
                }
            }
            double fx = (player.getX() - MAP_MIN) / MAP_SIZE;
            double fz = (player.getZ() - MAP_MIN) / MAP_SIZE;
            if (fx >= 0 && fx <= 1 && fz >= 0 && fz <= 1) {
                int px = x0 + (int) (fx * size);
                int pz = y0 + (int) (fz * size);
                graphics.fill(px - 3, pz - 3, px + 4, pz + 4, COLOR_MARKER_EDGE);
                graphics.fill(px - 2, pz - 2, px + 3, pz + 3, COLOR_MARKER);
                double yaw = Math.toRadians(player.getYRot());
                int hx = px + (int) Math.round(-Math.sin(yaw) * 7);
                int hz = pz + (int) Math.round(Math.cos(yaw) * 7);
                graphics.fill(hx - 1, hz - 1, hx + 2, hz + 2, COLOR_MARKER);
            }
        } else {
            graphics.drawCenteredString(font, Component.translatable("zoneraids.map.not_here"), width / 2, y0 + size + 6 > height - 10 ? height - 12 : y0 + size + 6, COLOR_TEXT);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Square marker with a label, for a world position on the Cordon; nothing if it is off the sheet. */
    private void point(GuiGraphics graphics, int x0, int y0, int size, double x, double z, int color, String label) {
        double fx = (x - MAP_MIN) / MAP_SIZE;
        double fz = (z - MAP_MIN) / MAP_SIZE;
        if (fx < 0 || fx > 1 || fz < 0 || fz > 1) {
            return;
        }
        int px = x0 + (int) (fx * size);
        int pz = y0 + (int) (fz * size);
        graphics.fill(px - 4, pz - 4, px + 5, pz + 5, COLOR_MARKER_EDGE);
        graphics.fill(px - 3, pz - 3, px + 4, pz + 4, color);
        int w = font.width(label);
        int lx = Math.min(px + 7, x0 + size - w - 2);
        graphics.fill(lx - 2, pz - 5, lx + w + 2, pz + 5, 0xB0000000);
        graphics.drawString(font, label, lx, pz - 4, color, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
