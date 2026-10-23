package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The PDA's tabs: raids (the raid terminal, its data comes from the server), health and inventory
 * (both purely client-side). Every tab screen shares the panel size and position, with the tab row on top.
 */
public final class PdaTabs {
    public static final int PANEL_W = 380;
    public static final int PANEL_H = 236;
    private static final int TAB_W = 92;
    private static final int TAB_H = 14;

    public enum Tab {
        RAIDS, HEALTH, INVENTORY;

        final String key = "zoneraids.pda.tab." + name().toLowerCase();
    }

    private PdaTabs() {
    }

    public static int left(int width) {
        return (width - PANEL_W) / 2;
    }

    public static int top(int height) {
        return Math.max(TAB_H + 1, (height - PANEL_H + TAB_H) / 2);
    }

    /** Tab buttons above the panel at {@code left, top}; the screen adds them as widgets. */
    public static List<ZoneButton> buttons(int left, int top, Tab current) {
        List<ZoneButton> list = new ArrayList<>();
        for (Tab tab : Tab.values()) {
            ZoneButton button = new ZoneButton(left + 4 + tab.ordinal() * (TAB_W + 2), top - TAB_H, TAB_W, TAB_H + 1,
                    Component.translatable(tab.key), b -> open(tab, current));
            button.selected = tab == current;
            list.add(button);
        }
        return list;
    }

    private static void open(Tab tab, Tab current) {
        Minecraft mc = Minecraft.getInstance();
        if (tab == current || mc.player == null) {
            return;
        }
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            // Closing the inventory properly returns what lies in the crafting grid, as vanilla does.
            mc.player.closeContainer();
        }
        switch (tab) {
            case RAIDS -> RaidNetwork.sendToServer(new TerminalActionPacket(TerminalActionPacket.Action.OPEN, ""));
            case HEALTH -> mc.setScreen(new HealthScreen());
            case INVENTORY -> mc.setScreen(new PdaInventoryScreen(mc.player));
        }
    }
}
