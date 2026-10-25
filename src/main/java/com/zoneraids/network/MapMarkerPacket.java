package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: a point to put on the PDA map of the player's dimension (a stash from a flash drive,
 * a supply drop, an artifact after an emission, a contract target). {@code color} is an index into the
 * map's marker colours, {@code nameKey} a translation key for the marker's name.
 */
public record MapMarkerPacket(int x, int z, int color, String nameKey) {
    /** Indexes into com.zoneraids.client.WorldMap#COLORS. */
    public static final int DROP = 1;
    public static final int STASH = 2;
    public static final int ARTIFACT = 3;
    public static final int CONTRACT = 5;
    private static final int MAX_KEY = 128;

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeVarInt(color);
        buf.writeUtf(nameKey, MAX_KEY);
    }

    public static MapMarkerPacket decode(FriendlyByteBuf buf) {
        return new MapMarkerPacket(buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readUtf(MAX_KEY));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.ClientPacketHandlers.addMapMarker(x, z, color, nameKey));
    }
}
