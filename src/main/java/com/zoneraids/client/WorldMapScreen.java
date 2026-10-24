package com.zoneraids.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.zoneraids.ZoneRaids;
import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.world.ZoneDimensions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PDA map tab: the terrain the player has explored ({@link WorldMap}), in both builds and every dimension.
 * Drag to move, wheel to zoom, right-click to put a marker (or take one off); a clicked marker can be renamed,
 * recoloured or deleted in the column beside the map, which lists all markers with direction and distance.
 * On the Cordon the round's exits and target are drawn too, and the paper map is a button away. The terrain is
 * one small texture, redrawn only when the view moves or once a second; markers are fills and text.
 */
public final class WorldMapScreen extends Screen {
    private static final ResourceLocation TEXTURE = new ResourceLocation(ZoneRaids.MODID, "worldmap");
    /** GUI pixels per block. */
    private static final float[] SCALES = {4.0F, 2.0F, 1.0F, 0.5F, 0.25F, 0.125F};
    private static final int SIDE_W = 156;
    private static final int UNKNOWN = 0xFF0C0E0A;
    private static final int REDRAW_TICKS = 20;
    private static final int LIST_ROW = 11;

    /** The view outlives the screen: the map opens where it was left. */
    private static int scaleIndex = 2;
    private static double centerX;
    private static double centerZ;
    private static boolean follow = true;

    @Nullable
    private static WorldMap.Marker selected;

    private int panelW;
    private int panelH;
    private int left;
    private int top;
    private int mapX;
    private int mapY;
    private int mapW;
    private int mapH;

    @Nullable
    private DynamicTexture texture;
    private int texW;
    private int texH;
    private int drawnX = Integer.MIN_VALUE;
    private int drawnZ;
    private int drawnScale = -1;
    private int redrawTicks;
    private boolean dragging;

    private EditBox name;
    private ZoneButton color;
    private ZoneButton delete;
    private int listY;

    public WorldMapScreen() {
        super(Component.translatable("zoneraids.worldmap.title"));
    }

    @Override
    protected void init() {
        panelW = Math.max(PdaTabs.PANEL_W, Math.min(width - 8, 640));
        panelH = Math.max(PdaTabs.PANEL_H, Math.min(height - PdaTabs.TAB_H - 6, 380));
        left = (width - panelW) / 2;
        top = Math.max(PdaTabs.TAB_H + 1, (height - panelH + PdaTabs.TAB_H) / 2);
        mapX = left + 10;
        mapY = top + 22;
        mapW = panelW - SIDE_W - 30;
        mapH = panelH - 32;
        PdaTabs.buttons(left, top, panelW, PdaTabs.Tab.MAP).forEach(this::addRenderableWidget);

        int x = mapX + mapW + 12;
        int w = left + panelW - 10 - x;
        int by = top + 22;
        addRenderableWidget(new ZoneButton(x, by, 20, 14, Component.literal("+"), b -> zoom(-1, mapX + mapW / 2.0, mapY + mapH / 2.0)));
        addRenderableWidget(new ZoneButton(x + 22, by, 20, 14, Component.literal("−"), b -> zoom(1, mapX + mapW / 2.0, mapY + mapH / 2.0)));
        addRenderableWidget(new ZoneButton(x + 44, by, w - 44, 14, Component.translatable("zoneraids.worldmap.center"), b -> follow = true));
        by += 18;
        if (onCordon()) {
            addRenderableWidget(new ZoneButton(x, by, w, 14, Component.translatable("zoneraids.worldmap.paper"),
                    b -> minecraft.setScreen(new CordonMapScreen())));
            by += 18;
        }
        // The selected marker: name, colour, delete.
        name = new EditBox(font, x + 1, by + 1, w - 2, 12, Component.translatable("zoneraids.worldmap.name"));
        name.setMaxLength(32);
        name.setResponder(text -> {
            if (selected != null && !text.equals(selected.name)) {
                WorldMap.rename(selected, text);
            }
        });
        addRenderableWidget(name);
        by += 16;
        color = new ZoneButton(x, by, w / 2 - 1, 14, Component.translatable("zoneraids.worldmap.color"), b -> {
            if (selected != null) {
                WorldMap.recolor(selected);
            }
        });
        delete = new ZoneButton(x + w / 2 + 1, by, w - w / 2 - 1, 14, Component.translatable("zoneraids.worldmap.delete"), b -> {
            if (selected != null) {
                WorldMap.removeMarker(selected);
                select(null);
            }
        });
        addRenderableWidget(color);
        addRenderableWidget(delete);
        listY = by + 22;
        select(selected != null && WorldMap.markers().contains(selected) ? selected : null);
    }

    private boolean onCordon() {
        return minecraft != null && minecraft.player != null && minecraft.player.level().dimension().equals(ZoneDimensions.CORDON);
    }

    private void select(@Nullable WorldMap.Marker marker) {
        selected = marker;
        name.setValue(marker == null ? "" : marker.name);
        name.setEditable(marker != null);
        name.setVisible(marker != null);
        color.visible = marker != null;
        delete.visible = marker != null;
        if (marker == null) {
            name.setFocused(false);
        }
    }

    private float scale() {
        return SCALES[scaleIndex];
    }

    private void zoom(int step, double atX, double atY) {
        int next = Mth.clamp(scaleIndex + step, 0, SCALES.length - 1);
        if (next == scaleIndex) {
            return;
        }
        // The block under the cursor stays under it.
        double wx = centerX + (atX - mapX - mapW / 2.0) / scale();
        double wz = centerZ + (atY - mapY - mapH / 2.0) / scale();
        scaleIndex = next;
        if (!follow) {
            centerX = wx - (atX - mapX - mapW / 2.0) / scale();
            centerZ = wz - (atY - mapY - mapH / 2.0) / scale();
        }
    }

    private boolean overMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
    }

    /** Blocks per texel and GUI pixels per texel at the current zoom. */
    private int blocksPerTexel() {
        return scale() >= 1.0F ? 1 : Math.round(1.0F / scale());
    }

    private int pixelsPerTexel() {
        return scale() >= 1.0F ? Math.round(scale()) : 1;
    }

    /** The view's centre, snapped to whole texels so the terrain does not shimmer while it moves. */
    private int snappedX() {
        return Mth.floor(centerX / blocksPerTexel()) * blocksPerTexel();
    }

    private int snappedZ() {
        return Mth.floor(centerZ / blocksPerTexel()) * blocksPerTexel();
    }

    private int screenX(double worldX) {
        return mapX + mapW / 2 + (int) Math.floor((worldX - snappedX()) * scale());
    }

    private int screenY(double worldZ) {
        return mapY + mapH / 2 + (int) Math.floor((worldZ - snappedZ()) * scale());
    }

    @Override
    public void tick() {
        name.tick();
        if (follow && minecraft != null && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        redrawTicks++;
    }

    private void redraw() {
        int ppt = pixelsPerTexel();
        int bpt = blocksPerTexel();
        int w = mapW / ppt + 2;
        int h = mapH / ppt + 2;
        if (texture == null || w != texW || h != texH) {
            texW = w;
            texH = h;
            texture = new DynamicTexture(w, h, true);
            minecraft.getTextureManager().register(TEXTURE, texture);
        }
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        int originX = snappedX() - (texW / 2) * bpt;
        int originZ = snappedZ() - (texH / 2) * bpt;
        for (int ty = 0; ty < texH; ty++) {
            int bz = originZ + ty * bpt;
            int lastChunk = Integer.MIN_VALUE;
            byte[] chunk = null;
            for (int tx = 0; tx < texW; tx++) {
                int bx = originX + tx * bpt;
                int cx = bx >> 4;
                if (cx != lastChunk) {
                    lastChunk = cx;
                    chunk = WorldMap.chunk(cx, bz >> 4);
                }
                int c = WorldMap.color(bx, bz, chunk);
                image.setPixelRGBA(tx, ty, c == 0 ? UNKNOWN : c);
            }
        }
        texture.upload();
        drawnX = snappedX();
        drawnZ = snappedZ();
        drawnScale = scaleIndex;
        redrawTicks = 0;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        ZoneUi.panel(g, left, top, left + panelW, top + panelH, ZoneUi.BG);
        Player player = minecraft == null ? null : minecraft.player;
        Component pos = player == null ? null
                : Component.translatable("zoneraids.map.position", (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
        ZoneUi.header(g, font, left, top, left + panelW, title, ZoneUi.AMBER, pos, ZoneUi.DIM);
        if (player != null) {
            WorldMap.use(player.level());
        }

        if (texture == null || drawnX != snappedX() || drawnZ != snappedZ() || drawnScale != scaleIndex || redrawTicks >= REDRAW_TICKS) {
            redraw();
        }
        g.fill(mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, ZoneUi.EDGE);
        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        int ppt = pixelsPerTexel();
        int tx0 = mapX + mapW / 2 - (texW / 2) * ppt;
        int ty0 = mapY + mapH / 2 - (texH / 2) * ppt;
        g.blit(TEXTURE, tx0, ty0, texW * ppt, texH * ppt, 0, 0, texW, texH, texW, texH);
        drawOverlays(g, player, mouseX, mouseY);
        g.disableScissor();
        ZoneUi.corners(g, mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, ZoneUi.AMBER, 10);
        String hint = Component.translatable("zoneraids.worldmap.hint").getString();
        g.drawString(font, ZoneUi.clip(font, hint, mapW - 8), mapX + 4, mapY + mapH - 11, ZoneUi.DIM);

        drawSide(g, player, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
        if (overMap(mouseX, mouseY)) {
            WorldMap.Marker hovered = markerAt(mouseX, mouseY);
            if (hovered != null) {
                g.renderTooltip(font, Component.literal(hovered.name + "  " + hovered.x + " · " + hovered.z), mouseX, mouseY);
            }
        }
    }

    private void drawOverlays(GuiGraphics g, @Nullable Player player, int mouseX, int mouseY) {
        if (player != null && onCordon()) {
            RaidHudPacket hud = ClientRaidState.hud;
            if (hud != null && !hud.round().intermission()) {
                for (RaidHudPacket.ExtractEntry extract : hud.extracts()) {
                    if (extract.hasPos()) {
                        point(g, extract.x(), extract.z(), ZoneUi.GREEN, extract.name(), false);
                    }
                }
                if (hud.round().hasTarget()) {
                    point(g, hud.round().tx(), hud.round().tz(), ZoneUi.AMBER, hud.round().targetLabel().getString(), false);
                }
            }
        }
        for (WorldMap.Marker marker : WorldMap.markers()) {
            point(g, marker.x + 0.5, marker.z + 0.5, marker.rgb(), marker.name, marker == selected);
        }
        if (player != null) {
            int px = screenX(player.getX());
            int pz = screenY(player.getZ());
            g.fill(px - 3, pz - 3, px + 4, pz + 4, 0xFF000000);
            g.fill(px - 2, pz - 2, px + 3, pz + 3, ZoneUi.RED);
            double yaw = Math.toRadians(player.getYRot());
            int hx = px + (int) Math.round(-Math.sin(yaw) * 7);
            int hz = pz + (int) Math.round(Math.cos(yaw) * 7);
            g.fill(hx - 1, hz - 1, hx + 2, hz + 2, ZoneUi.RED);
        }
    }

    /** A point on the map: a diamond-ish square with its name beside it. */
    private void point(GuiGraphics g, double x, double z, int rgb, String label, boolean lit) {
        int px = screenX(x);
        int pz = screenY(z);
        if (px < mapX - 40 || px > mapX + mapW + 40 || pz < mapY - 10 || pz > mapY + mapH + 10) {
            return;
        }
        g.fill(px - 4, pz - 4, px + 5, pz + 5, lit ? ZoneUi.AMBER : 0xFF000000);
        g.fill(px - 3, pz - 3, px + 4, pz + 4, rgb);
        g.fill(px, pz - 1, px + 1, pz + 2, 0xFF000000);
        g.fill(px - 1, pz, px + 2, pz + 1, 0xFF000000);
        int w = font.width(label);
        g.fill(px + 6, pz - 5, px + 10 + w, pz + 5, 0xB0000000);
        g.drawString(font, label, px + 8, pz - 4, rgb, false);
    }

    @Nullable
    private WorldMap.Marker markerAt(double mouseX, double mouseY) {
        for (WorldMap.Marker marker : WorldMap.markers()) {
            int px = screenX(marker.x + 0.5);
            int pz = screenY(marker.z + 0.5);
            if (Math.abs(mouseX - px) <= 5 && Math.abs(mouseY - pz) <= 5) {
                return marker;
            }
        }
        return null;
    }

    /** Legend and the marker list, with direction and distance from the player. */
    private void drawSide(GuiGraphics g, @Nullable Player player, int mouseX, int mouseY) {
        int x = mapX + mapW + 12;
        int right = left + panelW - 10;
        int w = right - x;
        int y = listY;
        if (selected == null) {
            g.drawWordWrap(font, Component.translatable("zoneraids.worldmap.select_hint"), x, name.getY(), w, ZoneUi.DIM);
        }
        List<WorldMap.Marker> markers = WorldMap.markers();
        g.drawString(font, Component.translatable("zoneraids.worldmap.markers", markers.size()), x, y, ZoneUi.AMBER);
        y += 12;
        int bottom = top + panelH - 8;
        for (WorldMap.Marker marker : markers) {
            if (y + LIST_ROW > bottom) {
                g.drawString(font, "…", x, y, ZoneUi.DIM);
                break;
            }
            boolean hot = marker == selected || mouseX >= x - 2 && mouseX < right && mouseY >= y - 1 && mouseY < y + LIST_ROW - 1;
            if (hot) {
                g.fill(x - 2, y - 1, right + 2, y + LIST_ROW - 1, ZoneUi.ROW_HOVER);
            }
            g.fill(x, y + 1, x + 7, y + 8, marker.rgb());
            String info = "";
            if (player != null) {
                int dist = (int) Math.sqrt((player.getX() - marker.x) * (player.getX() - marker.x) + (player.getZ() - marker.z) * (player.getZ() - marker.z));
                info = ZoneUi.arrow(player.getX(), player.getZ(), player.getYRot(), marker.x + 0.5, marker.z + 0.5) + " " + dist;
            }
            g.drawString(font, ZoneUi.clip(font, marker.name, w - 14 - font.width(info) - 4), x + 11, y, ZoneUi.TEXT);
            g.drawString(font, info, right - font.width(info), y, ZoneUi.DIM);
            y += LIST_ROW;
        }
        if (markers.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("zoneraids.worldmap.no_markers"), x, y, w, ZoneUi.DIM);
        }
    }

    /** Marker of the side list row under the mouse, or null. */
    @Nullable
    private WorldMap.Marker rowAt(double mouseX, double mouseY) {
        int x = mapX + mapW + 12;
        if (mouseX < x - 2 || mouseX >= left + panelW - 8) {
            return null;
        }
        int row = (int) Math.floor((mouseY - (listY + 12 - 1)) / LIST_ROW);
        int bottom = top + panelH - 8;
        List<WorldMap.Marker> markers = WorldMap.markers();
        if (row < 0 || row >= markers.size() || listY + 12 + (row + 1) * LIST_ROW > bottom) {
            return null;
        }
        return markers.get(row);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (overMap(mouseX, mouseY)) {
            name.setFocused(false);
            setFocused(null);
            WorldMap.Marker marker = markerAt(mouseX, mouseY);
            if (button == 1) {
                if (marker != null) {
                    WorldMap.removeMarker(marker);
                    if (marker == selected) {
                        select(null);
                    }
                } else {
                    int wx = Mth.floor(snappedX() + (mouseX - mapX - mapW / 2.0) / scale());
                    int wz = Mth.floor(snappedZ() + (mouseY - mapY - mapH / 2.0) / scale());
                    select(WorldMap.addMarker(wx, wz));
                }
                return true;
            }
            if (button == 0) {
                if (marker != null) {
                    select(marker);
                } else {
                    dragging = true;
                }
                return true;
            }
        }
        WorldMap.Marker row = rowAt(mouseX, mouseY);
        if (row != null && button == 0) {
            select(row);
            follow = false;
            centerX = row.x + 0.5;
            centerZ = row.z + 0.5;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            follow = false;
            centerX -= dragX / scale();
            centerZ -= dragY / scale();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (overMap(mouseX, mouseY) && delta != 0) {
            zoom(delta > 0 ? -1 : 1, mouseX, mouseY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (name.isFocused()) {
            // Typing a name: only Escape and Enter leave the box.
            if (keyCode == 256 || keyCode == 257 || keyCode == 335) {
                name.setFocused(false);
                setFocused(null);
                return true;
            }
            name.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (ClientSetup.OPEN_TERMINAL.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        if (texture != null) {
            minecraft.getTextureManager().release(TEXTURE);
            texture = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
