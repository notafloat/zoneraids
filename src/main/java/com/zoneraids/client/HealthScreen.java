package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.raid.RaidManager;
import com.zoneraids.util.FirstAidCompat;
import com.zoneraids.util.FirstAidCompat.Part;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * PDA health tab: a body diagram and a bar per limb from First Aid, then health, food, armor, morphine
 * and active effects, read live from the client's player. Treatment works here too: pick a First Aid
 * healing item from the first-aid kit row at the bottom, then click the part to treat. The item is
 * applied for as long as First Aid's own window makes you hold it, then the server uses it up.
 */
public final class HealthScreen extends Screen {
    private static final int ROW_H = 15;
    private static final int LIST_Y = 22;
    private static final int KIT_SLOT = 20;
    /** Effects listed under the vitals; fewer than before, so the kit row fits. */
    private static final int MAX_EFFECTS = 4;

    /** Healing items in the inventory, one entry per item type: the first slot holding it and the total count. */
    private record Kit(int slot, ItemStack stack, int count) {
    }

    private List<Kit> kits = List.of();
    @Nullable
    private Item selected;
    @Nullable
    private Part applying;
    private long applyStart;
    private int applyMillis;

    public HealthScreen() {
        super(Component.translatable("zoneraids.pda.health.title"));
    }

    private int left() {
        return PdaTabs.left(width);
    }

    private int top() {
        return PdaTabs.top(height);
    }

    private int kitY() {
        return top() + PdaTabs.PANEL_H - 26;
    }

    @Override
    public void tick() {
        Player player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return;
        }
        List<Kit> found = new ArrayList<>();
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!FirstAidCompat.isHealingItem(stack)) {
                continue;
            }
            int index = -1;
            for (int k = 0; k < found.size(); k++) {
                if (found.get(k).stack().is(stack.getItem())) {
                    index = k;
                }
            }
            if (index < 0) {
                found.add(new Kit(i, stack, stack.getCount()));
            } else {
                Kit kit = found.get(index);
                found.set(index, new Kit(kit.slot(), kit.stack(), kit.count() + stack.getCount()));
            }
        }
        kits = found;
        Kit kit = selectedKit();
        if (kit == null) {
            selected = null;
            applying = null;
        } else if (applying != null && Util.getMillis() - applyStart >= applyMillis) {
            RaidNetwork.sendToServer(new TerminalActionPacket(TerminalActionPacket.Action.HEAL, kit.slot() + " " + applying.name()));
            applying = null;
        }
    }

    @Nullable
    private Kit selectedKit() {
        for (Kit kit : kits) {
            if (kit.stack().is(selected)) {
                return kit;
            }
        }
        return null;
    }

    /** Whether the selected item can go on {@code part}: hurt and not already being treated. */
    private static boolean treatable(@Nullable FirstAidCompat.Snapshot limbs, Part part) {
        return limbs != null && limbs.health()[part.ordinal()] < limbs.max()[part.ordinal()] && !limbs.healing()[part.ordinal()];
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int left = left();
        int ky = kitY();
        for (int i = 0; i < kits.size(); i++) {
            int kx = kitX(left, i);
            if (mouseX >= kx - 1 && mouseX < kx + 17 && mouseY >= ky - 1 && mouseY < ky + 17) {
                Item item = kits.get(i).stack().getItem();
                selected = item == selected ? null : item;
                applying = null;
                return true;
            }
        }
        Part part = rowAt(mouseX, mouseY);
        if (part != null && selected != null && minecraft != null && minecraft.player != null
                && treatable(FirstAidCompat.read(minecraft.player), part)) {
            Kit kit = selectedKit();
            if (kit != null) {
                applying = part == applying ? null : part;
                applyStart = Util.getMillis();
                applyMillis = FirstAidCompat.applyTimeMillis(kit.stack());
                return true;
            }
        }
        return false;
    }

    private int kitX(int left, int i) {
        return left + 12 + font.width(Component.translatable("zoneraids.pda.health.kit")) + 6 + i * KIT_SLOT;
    }

    @Nullable
    private Part rowAt(double mouseX, double mouseY) {
        int x = left() + 160;
        int right = left() + PdaTabs.PANEL_W - 10;
        if (mouseX < x - 3 || mouseX >= right + 3) {
            return null;
        }
        int row = (int) Math.floor((mouseY - (top() + LIST_Y)) / ROW_H);
        return row >= 0 && row < Part.values().length && mouseY >= top() + LIST_Y ? Part.values()[row] : null;
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
        int y = top + LIST_Y;
        if (limbs != null) {
            Part hovered = selected == null ? null : rowAt(mouseX, mouseY);
            for (Part part : Part.values()) {
                if (selected != null && treatable(limbs, part)) {
                    // With an item picked, the parts it can go on are lit; the hovered one more.
                    g.fill(x - 3, y, right - 7, y + ROW_H - 1, part == hovered ? ZoneUi.ROW_HOVER : ZoneUi.ROW);
                }
                float fraction = limbs.fraction(part);
                int color = ZoneUi.healthColor(fraction);
                g.drawString(font, Component.translatable("zoneraids.pda.part." + part.id), x, y + 2, fraction <= 0 ? ZoneUi.RED : ZoneUi.TEXT);
                if (part == applying) {
                    float done = Math.min(1.0F, (Util.getMillis() - applyStart) / (float) Math.max(1, applyMillis));
                    ZoneUi.bar(g, barX, y + 4, barW, 4, done, ZoneUi.AMBER);
                } else {
                    ZoneUi.bar(g, barX, y + 4, barW, 4, fraction, color == 0xFF2C2C26 ? ZoneUi.RED : color);
                }
                String value = limbs.healing()[part.ordinal()] ? "+" + fmt(limbs.health()[part.ordinal()])
                        : fmt(limbs.health()[part.ordinal()]) + "/" + limbs.max()[part.ordinal()];
                g.drawString(font, value, right - 10 - font.width(value), y + 2, limbs.healing()[part.ordinal()] ? ZoneUi.GREEN : ZoneUi.DIM);
                y += ROW_H;
            }
        } else {
            g.drawWordWrap(font, Component.translatable("zoneraids.pda.no_firstaid"), x, y + 2, right - 10 - x, ZoneUi.DIM);
            y += 40;
        }

        y = Math.max(y, top + 26 + ZoneUi.bodyHeight(u) + 14);
        g.fill(left + 8, y - 2, right - 8, y - 1, 0x40D9A441);
        drawVitals(g, font, player, limbs, left + 10, y + 2, PdaTabs.PANEL_W - 20);
        drawKit(g, limbs, left, right, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** First-aid kit row: the healing items carried, the picked one outlined, and what to do next. */
    private void drawKit(GuiGraphics g, @Nullable FirstAidCompat.Snapshot limbs, int left, int right, int mouseX, int mouseY) {
        int ky = kitY();
        g.fill(left + 8, ky - 4, right - 8, ky - 3, 0x40D9A441);
        Component label = Component.translatable("zoneraids.pda.health.kit");
        g.drawString(font, label, left + 10, ky + 4, ZoneUi.AMBER);
        ItemStack hoveredStack = ItemStack.EMPTY;
        for (int i = 0; i < kits.size(); i++) {
            Kit kit = kits.get(i);
            int kx = kitX(left, i);
            ZoneUi.slotFrame(g, kx, ky);
            if (kit.stack().is(selected)) {
                ZoneUi.outline(g, kx - 1, ky - 1, kx + 17, ky + 17, ZoneUi.AMBER);
            }
            g.renderItem(kit.stack(), kx, ky);
            g.renderItemDecorations(font, kit.stack(), kx, ky, String.valueOf(kit.count()));
            if (mouseX >= kx - 1 && mouseX < kx + 17 && mouseY >= ky - 1 && mouseY < ky + 17) {
                hoveredStack = kit.stack();
            }
        }
        int tx = kitX(left, kits.size()) + 4;
        Component hint = kits.isEmpty() ? Component.translatable("zoneraids.pda.health.no_kit")
                : applying != null ? Component.translatable("zoneraids.pda.health.applying")
                : selected != null ? Component.translatable("zoneraids.pda.health.pick_part")
                : Component.translatable("zoneraids.pda.health.pick_item");
        g.drawString(font, ZoneUi.clip(font, hint.getString(), right - 10 - tx), tx, ky + 4, applying != null ? ZoneUi.AMBER : ZoneUi.DIM);
        if (!hoveredStack.isEmpty()) {
            g.renderTooltip(font, hoveredStack, mouseX, mouseY);
        }
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
            if (i >= MAX_EFFECTS) {
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
