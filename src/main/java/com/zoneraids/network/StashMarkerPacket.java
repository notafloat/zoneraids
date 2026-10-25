package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: a flash drive was read, put the stash on the PDA map of the player's dimension. */
public record StashMarkerPacket(int x, int z) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
    }

    public static StashMarkerPacket decode(FriendlyByteBuf buf) {
        return new StashMarkerPacket(buf.readInt(), buf.readInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.ClientPacketHandlers.addStashMarker(x, z));
    }
}
