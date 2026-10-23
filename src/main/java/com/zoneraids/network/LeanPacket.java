package com.zoneraids.network;

import com.zoneraids.lean.Lean;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: the player started or stopped leaning (-1 left, 0 none, 1 right). */
public record LeanPacket(int direction) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(direction);
    }

    public static LeanPacket decode(FriendlyByteBuf buf) {
        return new LeanPacket(buf.readByte());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            Lean.set(player, direction);
        }
    }
}
