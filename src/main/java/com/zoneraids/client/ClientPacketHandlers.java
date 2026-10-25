package com.zoneraids.client;

import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.network.ShopDataPacket;
import com.zoneraids.network.TerminalDataPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    public static void handleTerminal(TerminalDataPacket packet) {
        ClientRaidState.terminal = packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RaidTerminalScreen screen) {
            screen.onDataUpdated();
        } else if (mc.screen instanceof PerksScreen screen) {
            screen.onDataUpdated();
        } else if (mc.screen instanceof WatchScreen screen) {
            screen.onDataUpdated();
        } else if (packet.open()) {
            mc.setScreen(PdaTabs.terminalScreen(PdaTabs.pending));
        }
        PdaTabs.pending = PdaTabs.Tab.RAIDS;
    }

    public static void handleShop(ShopDataPacket packet) {
        ClientRaidState.shop = packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ShopScreen screen) {
            screen.onDataUpdated();
        } else if (packet.open()) {
            mc.setScreen(new ShopScreen());
        }
    }

    public static void openCordonMap() {
        Minecraft.getInstance().setScreen(new CordonMapScreen());
    }

    public static void addStashMarker(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            WorldMap.use(mc.level);
            WorldMap.addMarker(x, z, WorldMap.STASH_COLOR, Component.translatable("zoneraids.stash_drive.marker").getString());
        }
    }

    public static void handleHud(RaidHudPacket packet) {
        ClientRaidState.hud = packet.active() ? packet : null;
        ClientRaidState.hudReceivedAt = Util.getMillis();
    }
}
