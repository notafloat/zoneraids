package com.zoneraids.client;

import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.network.TerminalDataPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    public static void handleTerminal(TerminalDataPacket packet) {
        ClientRaidState.terminal = packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RaidTerminalScreen screen) {
            screen.onDataUpdated();
        } else if (packet.open()) {
            mc.setScreen(new RaidTerminalScreen());
        }
    }

    public static void openCordonMap() {
        Minecraft.getInstance().setScreen(new CordonMapScreen());
    }

    public static void handleHud(RaidHudPacket packet) {
        ClientRaidState.hud = packet.active() ? packet : null;
        ClientRaidState.hudReceivedAt = Util.getMillis();
    }
}
