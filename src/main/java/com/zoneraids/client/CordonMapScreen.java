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
 * PDA map tab: the paper map of the Cordon, as large as the screen allows, with a legend and the
 * assigned exits beside it. The game keeps running behind it, as reading a map in a raid should cost
 * attention. The holder's position, their assigned exits and the round target are marked only while
 * they are on the Cordon. The map item opens this sheet; the PDA's map tab opens the terrain map with the
 * player's markers ({@link WorldMapScreen}), and each has a button to the other.
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

    private static final int SIDE_W = 150;

    private int size;
    private int panelW;
    private int panelH;
    private int left;
    private int top;

    public CordonMapScreen() {
        super(Component.translatable("item.zoneraids.cordon_map"));
    }

    @Override
    protected void init() {
        size = Math.max(96, Math.min(height - PdaTabs.TAB_H - 34, width - SIDE_W - 36));
        panelW = Math.max(PdaTabs.PANEL_W, size + SIDE_W + 24);
        panelH = size + 30;
        left = (width - panelW) / 2;
        top = Math.max(PdaTabs.TAB_H + 1, (height - panelH + PdaTabs.TAB_H) / 2);
        PdaTabs.buttons(left, top, panelW, PdaTabs.Tab.MAP).forEach(this::addRenderableWidget);
        int x = left + 10 + size + 12;
        int right = left + panelW - 10;
        addRenderableWidget(new ZoneButton(x, top + panelH - 24, right - x, 14, Component.translatable("zoneraids.worldmap.terrain"),
                b -> minecraft.setScreen(new WorldMapScreen())));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ClientSetup.OPEN_TERMINAL.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        ZoneUi.panel(graphics, left, top, left + panelW, top + panelH, ZoneUi.BG);
        ZoneUi.header(graphics, font, left, top, left + panelW, title, ZoneUi.AMBER, null, 0);
        int x0 = left + 10;
        int y0 = top + 22;
        graphics.fill(x0 - 3, y0 - 3, x0 + size + 3, y0 + size + 3, COLOR_FRAME);
        ZoneUi.corners(graphics, x0 - 3, y0 - 3, x0 + size + 3, y0 + size + 3, ZoneUi.AMBER, 10);
        graphics.blit(TEXTURE, x0, y0, size, size, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);

        Player player = minecraft == null ? null : minecraft.player;
        boolean here = player != null && player.level().dimension().equals(ZoneDimensions.CORDON);
        side(graphics, player, here, x0 + size + 12, y0, left + panelW - 10);
        if (here) {
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
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Legend, position and the assigned exits with direction and distance, in the column right of the map. */
    private void side(GuiGraphics graphics, Player player, boolean here, int x, int y, int right) {
        int w = right - x;
        legend(graphics, x, y, COLOR_MARKER, Component.translatable("zoneraids.map.legend.you"));
        legend(graphics, x, y + 11, COLOR_EXIT, Component.translatable("zoneraids.map.legend.exit"));
        legend(graphics, x, y + 22, COLOR_TARGET, Component.translatable("zoneraids.map.legend.target"));
        y += 38;
        if (!here || player == null) {
            graphics.drawWordWrap(font, Component.translatable("zoneraids.map.not_here"), x, y, w, ZoneUi.DIM);
            return;
        }
        graphics.drawString(font, Component.translatable("zoneraids.map.position", (int) Math.floor(player.getX()), (int) Math.floor(player.getZ())), x, y, ZoneUi.TEXT);
        y += 14;
        RaidHudPacket hud = ClientRaidState.hud;
        if (hud == null || hud.round().intermission() || hud.extracts().isEmpty()) {
            return;
        }
        graphics.drawString(font, Component.translatable("zoneraids.hud.exits"), x, y, ZoneUi.DIM);
        y += 11;
        for (RaidHudPacket.ExtractEntry extract : hud.extracts()) {
            boolean active = extract.name().equals(hud.extracting());
            String name = ZoneUi.clip(font, extract.name(), extract.hasPos() ? w - 44 : w);
            graphics.drawString(font, name, x, y, active ? ZoneUi.GREEN : ZoneUi.TEXT);
            if (extract.hasPos()) {
                int dist = (int) Math.sqrt((player.getX() - extract.x()) * (player.getX() - extract.x())
                        + (player.getZ() - extract.z()) * (player.getZ() - extract.z()));
                String text = ZoneUi.arrow(player.getX(), player.getZ(), player.getYRot(), extract.x(), extract.z()) + " " + dist;
                graphics.drawString(font, text, right - font.width(text), y, ZoneUi.DIM);
            }
            y += 11;
        }
    }

    private void legend(GuiGraphics graphics, int x, int y, int color, Component label) {
        graphics.fill(x, y + 1, x + 7, y + 8, COLOR_MARKER_EDGE);
        graphics.fill(x + 1, y + 2, x + 6, y + 7, color);
        graphics.drawString(font, label, x + 11, y, COLOR_TEXT);
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
