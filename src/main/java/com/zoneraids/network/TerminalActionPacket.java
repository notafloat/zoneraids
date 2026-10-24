package com.zoneraids.network;

import com.zoneraids.raid.RaidManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: a button press in the PDA. */
public record TerminalActionPacket(Action action, String arg) {
    public enum Action {
        OPEN, REFRESH, JOIN, LEAVE_QUEUE, STASH, LEAVE_PARTY, ACCEPT_INVITE, BUY_PERK, WATCH, STOP_WATCH,
        SHOP, SHOP_BUY, SHOP_SELL, SHOP_SELL_ALL,
        /** Artifact slot of the worn container clicked in the PDA inventory: {@code "slot"}, or {@code "slot shift"}. */
        ARTIFACT,
        /** Shift-click on an artifact in the inventory: {@code "menuSlot"}, into the worn container. */
        ARTIFACT_STORE,
        /** Toggle "ready" for the next round of the player's session. */
        READY,
        /** First Aid healing item from an inventory slot on a body part: {@code "slot part"}. */
        HEAL
    }

    private static final int MAX_ARG = 64;

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(arg, MAX_ARG);
    }

    public static TerminalActionPacket decode(FriendlyByteBuf buf) {
        return new TerminalActionPacket(buf.readEnum(Action.class), buf.readUtf(MAX_ARG));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        RaidManager manager = RaidManager.get();
        if (player != null && manager != null) {
            manager.handleAction(player, action, arg);
        }
    }
}
