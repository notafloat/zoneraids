package com.zoneraids.client;

import com.zoneraids.raid.RaidManager;
import com.zoneraids.util.FirstAidCompat;
import com.zoneraids.util.FirstAidCompat.Part;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * PDA health tab: a body diagram and a bar per limb from First Aid, then health, food, armor, morphine
 * and active effects. Read live from the client's player every frame; nothing goes over the network.
 */
public final class HealthScreen extends Screen {
    private static final int ROW_H = 15;

    public HealthScreen() {
        super(Component.translatable("zoneraids.pda.health.title"));
    }

    @Override
    protected void init() {
        PdaTabs.buttons(PdaTabs.left(width), PdaTabs.top(height), PdaTabs.Tab.HEALTH).forEach(this::addRenderableWidget);
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = PdaTabs.left(width);
        int top = PdaTabs.top(height);
        int right = left + PdaTabs.PANEL_W;
        ZoneUi.panel(g, left, top, right, top + PdaTabs.PANEL_H, ZoneUi.BG);
        Player player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        float hpFraction = player.getMaxHealth() <= 0 ? 0 : player.getHealth() / player.getMaxHealth();
        Component hp = Component.translatable("zoneraids.pda.hp", fmt(player.getHealth()), fmt(player.getMaxHealth()));
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, hp, ZoneUi.healthColor(hpFraction));

        FirstAidCompat.Snapshot limbs = FirstAidCompat.read(player);
        int u = 7;
        int bodyTop = top + 26;
        g.fill(left + 8, top + 20, left + 150, top + 26 + ZoneUi.bodyHeight(u) + 8, ZoneUi.ROW);
        ZoneUi.body(g, left + 79, bodyTop, u, limbs, hpFraction);

        int x = left + 160;
        int barX = x + 78;
        int barW = right - 10 - barX - 40;
        int y = top + 22;
        if (limbs != null) {
            for (Part part : Part.values()) {
                float fraction = limbs.fraction(part);
                int color = ZoneUi.healthColor(fraction);
                g.drawString(font, Component.translatable("zoneraids.pda.part." + part.id), x, y + 2, fraction <= 0 ? ZoneUi.RED : ZoneUi.TEXT);
                ZoneUi.bar(g, barX, y + 4, barW, 4, fraction, color == 0xFF2C2C26 ? ZoneUi.RED : color);
                String value = fmt(limbs.health()[part.ordinal()]) + "/" + limbs.max()[part.ordinal()];
                g.drawString(font, value, right - 10 - font.width(value), y + 2, ZoneUi.DIM);
                y += ROW_H;
            }
        } else {
            g.drawWordWrap(font, Component.translatable("zoneraids.pda.no_firstaid"), x, y + 2, right - 10 - x, ZoneUi.DIM);
            y += 40;
        }

        y = Math.max(y, top + 26 + ZoneUi.bodyHeight(u) + 14);
        g.fill(left + 8, y - 2, right - 8, y - 1, 0x40D9A441);
        drawVitals(g, font, player, limbs, left + 10, y + 2, PdaTabs.PANEL_W - 20);
        g.drawString(font, Component.translatable("zoneraids.pda.health.hint"), left + 10, top + PdaTabs.PANEL_H - 12, ZoneUi.DIM);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** Food, armor, morphine and effects in two columns starting at {@code x, y}. Returns the y below them. */
    static int drawVitals(GuiGraphics g, Font font, Player player, @Nullable FirstAidCompat.Snapshot limbs, int x, int y, int w) {
        int col = w / 2;
        int food = player.getFoodData().getFoodLevel();
        g.drawString(font, Component.translatable("zoneraids.pda.food", food), x, y, ZoneUi.TEXT);
        ZoneUi.bar(g, x + 70, y + 2, col - 80, 4, food / 20.0F, ZoneUi.AMBER);
        g.drawString(font, Component.translatable("zoneraids.pda.armor", player.getArmorValue()), x + col, y, ZoneUi.TEXT);
        int air = player.getAirSupply();
        if (air < player.getMaxAirSupply()) {
            ZoneUi.bar(g, x + col + 70, y + 2, col - 80, 4, Math.max(0, air) / (float) player.getMaxAirSupply(), 0xFF5A9AD0);
        }
        y += 12;
        if (limbs != null && limbs.morphineTicks() > 0) {
            g.drawString(font, Component.translatable("zoneraids.pda.morphine", RaidManager.formatTime(limbs.morphineTicks() / 20)), x, y, ZoneUi.GREEN);
            y += 12;
        }
        int i = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.showIcon()) {
                continue;
            }
            Component name = effect.getEffect().getDisplayName().copy()
                    .append(effect.getAmplifier() > 0 ? " " + (effect.getAmplifier() + 1) : "")
                    .append(effect.isInfiniteDuration() ? "" : " " + RaidManager.formatTime(effect.getDuration() / 20));
            int ex = x + (i % 2) * col;
            g.drawString(font, ZoneUi.clip(font, name.getString(), col - 6), ex, y, effect.getEffect().isBeneficial() ? ZoneUi.GREEN : ZoneUi.RED);
            if (++i % 2 == 0) {
                y += 11;
            }
            if (i >= 6) {
                break;
            }
        }
        return i % 2 == 0 ? y : y + 11;
    }

    static String fmt(float value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.format("%.1f", value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
