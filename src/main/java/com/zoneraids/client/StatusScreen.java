package com.zoneraids.client;

import com.zoneraids.network.TerminalDataPacket;
import com.zoneraids.perk.Perk;
import com.zoneraids.raid.RaidManager;
import com.zoneraids.util.AnomalyZoneCompat;
import com.zoneraids.util.AnomalyZoneCompat.Stat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PDA status tab: what every mod knows about the player, in one place. Contamination from worn
 * artifacts (radiation, psi, bio, heat) as badges, stamina and load, body stats, effects, the
 * worn container's bonuses, perks and Anomaly Zone food buffs. All read on the client; the
 * Anomaly Zone stats are recomputed twice a second.
 */
public final class StatusScreen extends Screen {
    /** Contamination types in badge order, with their signs. */
    static final Stat[] HAZARDS = {Stat.RADIATION, Stat.PSI, Stat.BIO, Stat.THERMO};
    static final String[] HAZARD_GLYPHS = {"☢", "Ψ", "☣", "♨"};
    private static final Stat[] BONUSES = {Stat.SPEED, Stat.HEALTH, Stat.REGEN, Stat.SATURATION, Stat.DAMAGE, Stat.ARMOR};
    private static final int BADGE = 22;
    private static final int REFRESH_TICKS = 10;

    @Nullable
    private double[] stats;
    private List<AnomalyZoneCompat.FoodBuff> buffs = List.of();
    private int refreshTicks;

    public StatusScreen() {
        super(Component.translatable("zoneraids.pda.status.title"));
    }

    @Override
    protected void init() {
        PdaTabs.buttons(PdaTabs.left(width), PdaTabs.top(height), PdaTabs.Tab.STATUS).forEach(this::addRenderableWidget);
        read();
    }

    private void read() {
        if (minecraft != null && minecraft.player != null) {
            stats = AnomalyZoneCompat.stats(minecraft.player);
            buffs = AnomalyZoneCompat.foodBuffs();
        }
    }

    @Override
    public void tick() {
        if (++refreshTicks >= REFRESH_TICKS) {
            refreshTicks = 0;
            read();
        }
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
        ZoneUi.header(g, font, left, top, right, title, ZoneUi.AMBER, null, 0);
        Player player = minecraft == null ? null : minecraft.player;
        if (player != null) {
            int mid = left + PdaTabs.PANEL_W / 2;
            drawLeft(g, player, left + 10, top + 22, mid - 6 - (left + 10), top + PdaTabs.PANEL_H - 6, mouseX, mouseY);
            g.fill(mid - 1, top + 20, mid, top + PdaTabs.PANEL_H - 6, 0x40D9A441);
            drawRight(g, mid + 6, top + 22, right - 10 - (mid + 6), top + PdaTabs.PANEL_H - 6, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** Contamination, stamina, body and effects. */
    private void drawLeft(GuiGraphics g, Player player, int x, int y, int w, int bottom, int mouseX, int mouseY) {
        y = section(g, x, y, w, "zoneraids.pda.status.hazards");
        if (stats == null) {
            g.drawString(font, Component.translatable("zoneraids.pda.status.no_anomalyzone"), x, y, ZoneUi.DIM);
            y += 14;
        } else {
            int step = Math.min(BADGE + 26, w / HAZARDS.length);
            boolean harmful = false;
            for (int i = 0; i < HAZARDS.length; i++) {
                double value = stats[HAZARDS[i].ordinal()];
                harmful |= value > AnomalyZoneCompat.HARMFUL;
                int color = ZoneUi.hazardColor(value, AnomalyZoneCompat.HARMFUL);
                int bx = x + i * step;
                ZoneUi.hazard(g, font, bx, y, BADGE, HAZARD_GLYPHS[i], color);
                String text = String.format("%.2f", Math.max(0, value));
                g.drawString(font, text, bx + BADGE + 3, y + 7, value <= 0 ? ZoneUi.DIM : color);
                if (mouseX >= bx && mouseX < bx + BADGE && mouseY >= y && mouseY < y + BADGE) {
                    setTooltipForNextRenderPass(Component.translatable("zoneraids.pda.status.hazard." + HAZARDS[i].id));
                }
            }
            y += BADGE + 3;
            g.drawString(font, Component.translatable(harmful ? "zoneraids.pda.status.harmful" : "zoneraids.pda.status.safe"),
                    x, y, harmful ? ZoneUi.RED : ZoneUi.DIM);
            y += 13;
        }

        // Stamina (sprinting, jumping, load). Anomaly Zone's own stamina follows it (see Fatigue).
        boolean exhausted = MovementClient.exhausted();
        g.drawString(font, Component.translatable("zoneraids.pda.status.stamina", Math.round(MovementClient.stamina() * 100)), x, y,
                exhausted ? ZoneUi.RED : ZoneUi.TEXT);
        ZoneUi.bar(g, x + 96, y + 2, w - 96, 4, MovementClient.stamina(), exhausted ? ZoneUi.RED : ZoneUi.AMBER);
        y += 11;
        y += 2;

        y = section(g, x, y, w, "zoneraids.pda.status.body");
        double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED) / player.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        y = row(g, x, y, w, Component.translatable("zoneraids.pda.status.max_health"), HealthScreen.fmt(player.getMaxHealth()), ZoneUi.TEXT);
        y = row(g, x, y, w, Component.translatable("zoneraids.pda.status.armor"),
                player.getArmorValue() + " / " + HealthScreen.fmt((float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS)), ZoneUi.TEXT);
        y = row(g, x, y, w, Component.translatable("zoneraids.pda.status.speed"), Math.round(speed * 100) + "%",
                speed < 0.99 ? ZoneUi.RED : speed > 1.01 ? ZoneUi.GREEN : ZoneUi.TEXT);
        y = row(g, x, y, w, Component.translatable("zoneraids.pda.status.load"), String.format("%.1f", MovementClient.load()), ZoneUi.TEXT);

        y = section(g, x, y + 2, w, "zoneraids.pda.status.effects");
        int shown = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.showIcon()) {
                continue;
            }
            if (y + 9 > bottom) {
                break;
            }
            Component name = effect.getEffect().getDisplayName().copy()
                    .append(effect.getAmplifier() > 0 ? " " + (effect.getAmplifier() + 1) : "");
            String time = effect.isInfiniteDuration() ? "∞" : RaidManager.formatTime(effect.getDuration() / 20);
            row(g, x, y, w, Component.literal(ZoneUi.clip(font, name.getString(), w - 34)), time,
                    effect.getEffect().isBeneficial() ? ZoneUi.GREEN : ZoneUi.RED);
            y += 10;
            shown++;
        }
        if (shown == 0) {
            g.drawString(font, Component.translatable("zoneraids.pda.status.no_effects"), x, y, ZoneUi.DIM);
        }
    }

    /** Container bonuses, perks and food buffs. */
    private void drawRight(GuiGraphics g, int x, int y, int w, int bottom, int mouseX, int mouseY) {
        y = section(g, x, y, w, "zoneraids.pda.status.artifacts");
        boolean any = false;
        if (stats != null) {
            for (Stat stat : BONUSES) {
                double value = stats[stat.ordinal()];
                if (Math.abs(value) < 0.005) {
                    continue;
                }
                any = true;
                y = row(g, x, y, w, Component.translatable("zoneraids.pda.status.stat." + stat.id), format(stat, value),
                        value > 0 ? ZoneUi.GREEN : ZoneUi.RED);
            }
        }
        if (!any) {
            g.drawString(font, Component.translatable(stats == null ? "zoneraids.pda.status.no_anomalyzone" : "zoneraids.pda.status.no_bonuses"), x, y, ZoneUi.DIM);
            y += 12;
        }

        y = section(g, x, y + 2, w, "zoneraids.pda.status.perks");
        TerminalDataPacket data = ClientRaidState.terminal;
        if (data == null) {
            g.drawString(font, Component.translatable("zoneraids.pda.status.no_perks"), x, y, ZoneUi.DIM);
            y += 12;
        } else {
            for (Perk perk : Perk.values()) {
                int level = perk.ordinal() < data.perks().length ? data.perks()[perk.ordinal()] : 0;
                g.drawString(font, Component.translatable("zoneraids.perk." + perk.id), x, y, level > 0 ? ZoneUi.TEXT : ZoneUi.DIM);
                for (int i = 0; i < Perk.MAX_LEVEL; i++) {
                    int px = x + w - (Perk.MAX_LEVEL - i) * 8;
                    if (i < level) {
                        g.fill(px, y + 1, px + 6, y + 7, ZoneUi.AMBER);
                    } else {
                        ZoneUi.outline(g, px, y + 1, px + 6, y + 7, ZoneUi.EDGE);
                    }
                }
                y += 10;
            }
            y = row(g, x, y, w, Component.translatable("zoneraids.pda.status.perk_points"), String.valueOf(data.perkPoints()), ZoneUi.AMBER);
        }

        if (!buffs.isEmpty() && y + 30 < bottom) {
            y = section(g, x, y + 2, w, "zoneraids.pda.status.food");
            int bx = x;
            for (AnomalyZoneCompat.FoodBuff buff : buffs) {
                if (bx + 18 > x + w) {
                    break;
                }
                ItemStack stack = new ItemStack(buff.item());
                ZoneUi.slotFrame(g, bx, y);
                g.renderItem(stack, bx, y);
                g.drawCenteredString(font, RaidManager.formatTime(buff.ticks() / 20), bx + 8, y + 18, ZoneUi.DIM);
                if (mouseX >= bx && mouseX < bx + 16 && mouseY >= y && mouseY < y + 16) {
                    setTooltipForNextRenderPass(stack.getHoverName());
                }
                bx += 30;
            }
        }
    }

    private static String format(Stat stat, double value) {
        String sign = value > 0 ? "+" : "";
        return switch (stat) {
            case SPEED -> sign + String.format("%.0f%%", value);
            case REGEN, SATURATION -> sign + String.format("%.2f/s", value);
            default -> sign + String.format("%.1f", value);
        };
    }

    /** Section title with a rule under it; returns the y for its first line. */
    private int section(GuiGraphics g, int x, int y, int w, String key) {
        g.drawString(font, Component.translatable(key), x, y, ZoneUi.AMBER);
        g.fill(x, y + 10, x + w, y + 11, 0x40D9A441);
        return y + 14;
    }

    /** Label on the left, value on the right; returns the next row's y. */
    private int row(GuiGraphics g, int x, int y, int w, Component label, String value, int valueColor) {
        g.drawString(font, label, x, y, ZoneUi.TEXT);
        g.drawString(font, value, x + w - font.width(value), y, valueColor);
        return y + 10;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
