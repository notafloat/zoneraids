package com.zoneraids.client;

import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalActionPacket.Action;
import com.zoneraids.network.TerminalDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The PDA: every window of the mod is one of its tabs. Raids, perks and watching read the terminal
 * data from the server; the trader and the stash are opened by the server; the map, health and
 * inventory are purely client-side. Every tab shares the panel size and position, with the tab row on top.
 */
public final class PdaTabs {
    public static final int PANEL_W = 380;
    public static final int PANEL_H = 236;
    public static final int TAB_H = 14;
    private static final int TAB_PAD = 6;
    private static final int TAB_GAP = 2;

    public enum Tab {
        RAIDS, PERKS, SHOP, WATCH, STASH, MAP, HEALTH, INVENTORY;

        final String key = "zoneraids.pda.tab." + name().toLowerCase();
    }

    /** Tab to show when the terminal data the PDA asked for arrives. */
    static Tab pending = Tab.RAIDS;

    private PdaTabs() {
    }

    public static int left(int width) {
        return (width - PANEL_W) / 2;
    }

    public static int top(int height) {
        return Math.max(TAB_H + 1, (height - PANEL_H + TAB_H) / 2);
    }

    /**
     * Tab buttons above a panel whose top-left corner is {@code left, top} and width {@code panelW};
     * the screen adds them as widgets. Tabs the player cannot use right now are greyed out.
     */
    public static List<ZoneButton> buttons(int left, int top, int panelW, Tab current) {
        Font font = Minecraft.getInstance().font;
        Tab[] tabs = Tab.values();
        int[] widths = new int[tabs.length];
        int total = 0;
        for (Tab tab : tabs) {
            widths[tab.ordinal()] = font.width(Component.translatable(tab.key)) + 2 * TAB_PAD;
            total += widths[tab.ordinal()] + TAB_GAP;
        }
        float scale = Math.min(1.0F, (panelW - 8) / (float) total);
        TerminalDataPacket data = ClientRaidState.terminal;
        List<ZoneButton> list = new ArrayList<>();
        int x = left + 4;
        for (Tab tab : tabs) {
            int w = (int) (widths[tab.ordinal()] * scale);
            ZoneButton button = new ZoneButton(x, top - TAB_H, w, TAB_H + 1, Component.translatable(tab.key), b -> open(tab, current));
            button.selected = tab == current;
            if (data != null && tab != current) {
                button.active = switch (tab) {
                    case SHOP -> data.canShop();
                    case STASH -> data.canStash();
                    case WATCH -> !data.inRaid();
                    default -> true;
                };
            }
            list.add(button);
            x += w + TAB_GAP;
        }
        return list;
    }

    public static List<ZoneButton> buttons(int left, int top, Tab current) {
        return buttons(left, top, PANEL_W, current);
    }

    /** Opens a tab from outside the PDA or from another tab. */
    public static void open(Tab tab, @Nullable Tab current) {
        Minecraft mc = Minecraft.getInstance();
        if (tab == current || mc.player == null) {
            return;
        }
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            // Closing a menu properly (inventory crafting grid, stash) as vanilla does.
            mc.player.closeContainer();
        }
        switch (tab) {
            case RAIDS -> requestTerminal(Tab.RAIDS);
            case PERKS, WATCH -> {
                if (ClientRaidState.terminal != null) {
                    mc.setScreen(terminalScreen(tab));
                    send(Action.REFRESH);
                } else {
                    requestTerminal(tab);
                }
            }
            case SHOP -> send(Action.SHOP);
            case STASH -> send(Action.STASH);
            case MAP -> mc.setScreen(new CordonMapScreen());
            case HEALTH -> mc.setScreen(new HealthScreen());
            case INVENTORY -> mc.setScreen(new PdaInventoryScreen(mc.player));
        }
    }

    private static void requestTerminal(Tab tab) {
        pending = tab;
        send(Action.OPEN);
    }

    /** Screen for a tab that shows the terminal data. */
    static Screen terminalScreen(Tab tab) {
        return switch (tab) {
            case PERKS -> new PerksScreen();
            case WATCH -> new WatchScreen();
            default -> new RaidTerminalScreen();
        };
    }

    private static void send(Action action) {
        RaidNetwork.sendToServer(new TerminalActionPacket(action, ""));
    }
}
