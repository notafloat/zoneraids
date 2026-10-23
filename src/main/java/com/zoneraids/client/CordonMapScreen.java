package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import com.zoneraids.world.ZoneDimensions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Full-screen paper map of the Cordon. The game keeps running behind it, as reading a map in a
 * raid should cost attention. The holder's position is marked only while they are on the Cordon.
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
        graphics.blit(TEXTURE, x0, y0, size, size, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);

        Player player = minecraft == null ? null : minecraft.player;
        if (player != null && player.level().dimension().equals(ZoneDimensions.CORDON)) {
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
