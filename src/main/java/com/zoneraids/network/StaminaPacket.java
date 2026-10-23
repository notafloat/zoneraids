package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: the player's stamina (0-100), whether they are exhausted, and their load in tenths of a kg. */
public record StaminaPacket(int stamina, boolean exhausted, int load) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(stamina);
        buf.writeBoolean(exhausted);
        buf.writeVarInt(load);
    }

    public static StaminaPacket decode(FriendlyByteBuf buf) {
        return new StaminaPacket(buf.readUnsignedByte(), buf.readBoolean(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.MovementClient.set(stamina, exhausted, load));
    }
}
