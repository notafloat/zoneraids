package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: how a player the client can see is leaning, so the body is drawn tilted. */
public record LeanSyncPacket(int entityId, int amount) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeByte(amount);
    }

    public static LeanSyncPacket decode(FriendlyByteBuf buf) {
        return new LeanSyncPacket(buf.readVarInt(), buf.readByte());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.LeanClient.setAmount(entityId, amount));
    }
}
