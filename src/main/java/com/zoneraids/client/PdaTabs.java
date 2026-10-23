package com.zoneraids.client;

import com.mojang.logging.LogUtils;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalActionPacket;
import com.zoneraids.network.TerminalActionPacket.Action;
import com.zoneraids.network.TerminalDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The PDA: every window of the mod is one of its tabs. Raids, perks and watching read the terminal
 * data from the server; the trader and the stash are opened by the server; the map, health and
 * status and inventory are purely client-side. Curios' slots (backpack, rig, artifact container) and the
 * worn container open from the inventory tab as views inside it ({@link PdaCuriosScreen},
 * {@link PdaContainerScreen}). Every PDA tab shares the panel size and position, with the tab row on top.
 */
public final class PdaTabs {
    public static final int PANEL_W = 424;
    public static final int PANEL_H = 236;
    public static final int TAB_H = 14;
    private static final int TAB_PAD = 6;
    /** ZoneButton clips its label 3 pixels from each edge. */
    private static final int MIN_PAD = 3;
    private static final int TAB_GAP = 2;

    public enum Tab {
        RAIDS, PERKS, SHOP, WATCH, STASH, MAP, HEALTH, STATUS, INVENTORY;

        final String key = "zoneraids.pda.tab." + name().toLowerCase();
    }

    /** Tab to show when the terminal data the PDA asked for arrives. */
    static Tab pending = Tab.RAIDS;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean curiosWarned;

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
    public static List<ZoneButton> buttons(int left, int top, int panelW, @Nullable Tab current) {
        Font font = Minecraft.getInstance().font;
        Tab[] tabs = Tab.values();
        int[] text = new int[tabs.length];
        int textTotal = 0;
        for (Tab tab : tabs) {
            text[tab.ordinal()] = font.width(Component.translatable(tab.key));
            textTotal += text[tab.ordinal()];
        }
        // Padding shrinks first (down to what the label needs to show whole), then the whole row scales.
        int room = panelW - 8 - tabs.length * TAB_GAP;
        int pad = Math.max(MIN_PAD, Math.min(TAB_PAD, (room - textTotal) / (2 * tabs.length)));
        float scale = Math.min(1.0F, room / (float) (textTotal + 2 * pad * tabs.length));
        TerminalDataPacket data = ClientRaidState.terminal;
        List<ZoneButton> list = new ArrayList<>();
        int x = left + 4;
        for (Tab tab : tabs) {
            int w = (int) ((text[tab.ordinal()] + 2 * pad) * scale);
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

    public static List<ZoneButton> buttons(int left, int top, @Nullable Tab current) {
        return buttons(left, top, PANEL_W, current);
    }

    /**
     * Tab row for a view inside a tab (Curios' slots, the artifact container): the {@code parent} tab is lit
     * but still clickable, and takes the player back to it.
     */
    public static List<ZoneButton> subview(int left, int top, int panelW, Tab parent) {
        List<ZoneButton> list = buttons(left, top, panelW, null);
        list.get(parent.ordinal()).selected = true;
        return list;
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
            case STATUS -> mc.setScreen(new StatusScreen());
            case INVENTORY -> mc.setScreen(new PdaInventoryScreen(mc.player));
        }
    }

    /** Asks Curios' server side to open its screen, as its own key does (no compile-time dependency on Curios). */
    static void openCurios() {
        if (!ModList.get().isLoaded("curios")) {
            return;
        }
        try {
            Object channel = Class.forName("top.theillusivec4.curios.common.network.NetworkHandler").getField("INSTANCE").get(null);
            Object packet = Class.forName("top.theillusivec4.curios.common.network.client.CPacketOpenCurios")
                    .getConstructor(ItemStack.class).newInstance(ItemStack.EMPTY);
            ((SimpleChannel) channel).send(PacketDistributor.SERVER.noArg(), packet);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            if (!curiosWarned) {
                curiosWarned = true;
                LOGGER.warn("Zone Raids: could not open the Curios screen", e);
            }
        }
    }

    /** True for Curios' screens (its slots beside the inventory), which open as {@link PdaCuriosScreen}. */
    static boolean isCuriosScreen(Screen screen) {
        return screen.getClass().getName().startsWith("top.theillusivec4.curios.client.gui.CuriosScreen");
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
