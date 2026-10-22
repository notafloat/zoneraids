package com.zoneraids.client;

import com.zoneraids.network.RaidHudPacket;
import com.zoneraids.network.TerminalDataPacket;
import org.jetbrains.annotations.Nullable;

/** Last data received from the server. Reset on disconnect so nothing outlives the connection. */
public final class ClientRaidState {
    @Nullable
    public static TerminalDataPacket terminal;
    @Nullable
    public static RaidHudPacket hud;
    public static long hudReceivedAt;

    private ClientRaidState() {
    }

    public static void reset() {
        terminal = null;
        hud = null;
        hudReceivedAt = 0;
    }
}
