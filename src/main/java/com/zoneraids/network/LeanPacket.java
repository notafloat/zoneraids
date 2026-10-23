package com.zoneraids.network;

import com.zoneraids.lean.Lean;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: how far the player leans, in percent of a full lean (-100 left, 0 none, 100 right). */
public record LeanPacket(int amount) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(amount);
    }

    public static LeanPacket decode(FriendlyByteBuf buf) {
        return new LeanPacket(buf.readByte());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            Lean.set(player, amount);
        }
    }
}
