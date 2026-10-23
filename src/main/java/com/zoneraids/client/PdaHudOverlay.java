package com.zoneraids.client;

import com.zoneraids.util.AnomalyZoneCompat;
import com.zoneraids.util.FirstAidCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;

import java.util.Set;

/**
 * The game's own HUD redrawn as a PDA: hotbar slots in a dark panel with the selected one in amber,
 * the off-hand slot beside it, a strip with health and food bars above, a small body diagram coloured
 * by First Aid limb health with contamination badges above it, and the experience as a
 * thin line. Replaces the vanilla hearts, food, armor, air and experience bars and First Aid's own
 * corner overlay. Spectators keep the vanilla hotbar, which carries the spectator menu.
 */
public final class PdaHudOverlay implements IGuiOverlay {
    private static final int SLOT = 20;
    private static final int STEP = 22;
    private static final int HOTBAR_W = 9 * STEP - 2;
    private static final int HAZARD = 11;
    /** Room TaCZ's ammo counter takes in the bottom-right corner. */
    private static final int GUN_HUD_W = 120;
    private static final ResourceLocation FIRST_AID_HUD = new ResourceLocation("firstaid", "hud");
    private static final Set<ResourceLocation> REPLACED = Set.of(VanillaGuiOverlay.PLAYER_HEALTH.id(), VanillaGuiOverlay.ARMOR_LEVEL.id(),
            VanillaGuiOverlay.FOOD_LEVEL.id(), VanillaGuiOverlay.AIR_LEVEL.id(), VanillaGuiOverlay.EXPERIENCE_BAR.id(), FIRST_AID_HUD);

    /** Hazards from the worn artifacts, read every half second. */
    private static double[] hazards;
    private static int hazardsTick = -1;

    /** Cancels the vanilla parts this overlay draws itself. Called from {@link ClientEvents}. */
    static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isSpectator()) {
            return;
        }
        ResourceLocation id = event.getOverlay().id();
        if (REPLACED.contains(id) || id.equals(VanillaGuiOverlay.HOTBAR.id())) {
            event.setCanceled(true);
        }
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        boolean survival = mc.gameMode != null && mc.gameMode.canHurtPlayer();
        int x0 = width / 2 - HOTBAR_W / 2;
        int y0 = height - SLOT - 2;
        int sy = y0 - 3 - 11;
        ZoneUi.panel(g, x0 - 3, survival ? sy : y0 - 3, x0 + HOTBAR_W + 3, height, ZoneUi.BG_HUD);
        for (int i = 0; i < 9; i++) {
            slot(g, font, player, player.getInventory().items.get(i), x0 + i * STEP, y0, i == player.getInventory().selected, i + 1);
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) {
            int ox = x0 - 3 - 8 - SLOT - 3;
            ZoneUi.panel(g, ox - 3, y0 - 3, ox + SLOT + 3, height, ZoneUi.BG_HUD);
            slot(g, font, player, offhand, ox, y0, false, 10);
        }
        // Experience as a thin line along the bottom of the hotbar panel.
        if (player.experienceProgress > 0) {
            g.fill(x0, height - 1, x0 + (int) (HOTBAR_W * player.experienceProgress), height, 0xC08CC060);
        }
        // The vanilla hotbar is cancelled, which also skips its Post event; mods draw on that event
        // (Anomaly Zone's detector radar and food buff icons), so it is sent here in its place.
        MinecraftForge.EVENT_BUS.post(new RenderGuiOverlayEvent.Post(mc.getWindow(), g, partialTick, VanillaGuiOverlay.HOTBAR.type()));

        if (!survival) {
            return;
        }
        // Stamina as the line between the bars and the hotbar: amber, red while exhausted.
        g.fill(x0, y0 - 3, x0 + HOTBAR_W, y0 - 2, 0x40D9A441);
        g.fill(x0, y0 - 3, x0 + (int) (HOTBAR_W * MovementClient.stamina()), y0 - 2,
                MovementClient.exhausted() ? ZoneUi.RED : ZoneUi.AMBER);
        float hp = player.getHealth();
        float hpFraction = player.getMaxHealth() <= 0 ? 0 : hp / player.getMaxHealth();
        int half = HOTBAR_W / 2;
        String hpText = "+" + (int) Math.ceil(hp);
        int hpColor = ZoneUi.healthColor(hpFraction);
        g.drawString(font, hpText, x0 + 1, sy + 2, hpColor == 0xFF2C2C26 ? ZoneUi.RED : hpColor);
        int hpBarX = x0 + 4 + font.width("+20");
        ZoneUi.bar(g, hpBarX, sy + 4, x0 + half - 4 - hpBarX, 3, hpFraction, hpColor);

        int air = player.getAirSupply();
        boolean underwater = air < player.getMaxAirSupply();
        int food = player.getFoodData().getFoodLevel();
        String rightText = underwater ? "O" + Math.max(0, air * 10 / player.getMaxAirSupply()) : String.valueOf(food);
        int rightColor = underwater ? 0xFF5A9AD0 : food <= 6 ? ZoneUi.RED : ZoneUi.AMBER;
        int textX = x0 + HOTBAR_W - 1 - font.width(rightText);
        g.drawString(font, rightText, textX, sy + 2, rightColor);
        float rightFraction = underwater ? Math.max(0, air) / (float) player.getMaxAirSupply() : food / 20.0F;
        ZoneUi.bar(g, x0 + half + 4, sy + 4, textX - 4 - (x0 + half + 4), 3, rightFraction, rightColor);

        // Body diagram and armor to the right of the hotbar. On narrow screens that corner belongs to
        // TaCZ's ammo counter, so the box goes above the right end of the strip instead.
        int bodyH = ZoneUi.bodyHeight(2);
        int armor = player.getArmorValue();
        String armorText = armor > 0 ? String.valueOf(armor) : "";
        int boxW = 20 + (armor > 0 ? font.width(armorText) + 3 : 0);
        int bx = x0 + HOTBAR_W + 3 + 4;
        int bottom = height;
        if (bx + boxW > width - GUN_HUD_W) {
            bx = x0 + HOTBAR_W + 3 - boxW;
            bottom = sy - 3 - 2;
        }
        ZoneUi.panel(g, bx, bottom - bodyH - 6, bx + boxW, bottom, ZoneUi.BG_HUD);
        ZoneUi.body(g, bx + 10, bottom - bodyH - 3, 2, FirstAidCompat.read(player), hpFraction);
        if (armor > 0) {
            g.drawString(font, armorText, bx + 19, bottom - 11, ZoneUi.DIM);
        }
        drawHazards(g, font, player, bx + boxW - HAZARD, bottom - bodyH - 6 - 3);
    }

    /** Contamination badges (radiation, psi, bio, heat) in a row ending at {@code right}, above {@code bottom}, only for the ones present. */
    private static void drawHazards(GuiGraphics g, Font font, LocalPlayer player, int right, int bottom) {
        if (hazardsTick < 0 || Math.abs(player.tickCount - hazardsTick) >= 10) {
            hazardsTick = player.tickCount;
            hazards = AnomalyZoneCompat.stats(player);
        }
        if (hazards == null) {
            return;
        }
        int x = right;
        for (int i = 0; i < StatusScreen.HAZARDS.length; i++) {
            double value = hazards[StatusScreen.HAZARDS[i].ordinal()];
            if (value > 0) {
                ZoneUi.hazard(g, font, x, bottom - HAZARD, HAZARD, StatusScreen.HAZARD_GLYPHS[i], ZoneUi.hazardColor(value, AnomalyZoneCompat.HARMFUL));
                x -= HAZARD + 1;
            }
        }
    }

    private static void slot(GuiGraphics g, Font font, LocalPlayer player, ItemStack stack, int x, int y, boolean selected, int seed) {
        g.fill(x, y, x + SLOT, y + SLOT, selected ? 0xC0363824 : 0x90000000);
        ZoneUi.outline(g, x, y, x + SLOT, y + SLOT, selected ? ZoneUi.AMBER : ZoneUi.EDGE);
        if (selected) {
            ZoneUi.outline(g, x - 1, y - 1, x + SLOT + 1, y + SLOT + 1, 0x80D9A441);
        }
        if (!stack.isEmpty()) {
            g.renderItem(player, stack, x + 2, y + 2, seed);
            g.renderItemDecorations(font, stack, x + 2, y + 2);
        }
    }
}
