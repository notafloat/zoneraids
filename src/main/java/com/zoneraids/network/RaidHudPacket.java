package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Server -> client: raid HUD state. Sent about once per second while in a raid. */
public record RaidHudPacket(boolean active, String mapName, int secondsLeft, List<ExtractEntry> extracts,
                            String extracting, float progress) {
    private static final int MAX_EXTRACTS = 16;

    public record ExtractEntry(String name, boolean hasPos, double x, double y, double z) {
    }

    public static RaidHudPacket inactive() {
        return new RaidHudPacket(false, "", 0, List.of(), "", -1.0F);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        if (!active) {
            return;
        }
        buf.writeUtf(mapName);
        buf.writeVarInt(secondsLeft);
        buf.writeCollection(extracts.subList(0, Math.min(extracts.size(), MAX_EXTRACTS)), (b, e) -> {
            b.writeUtf(e.name());
            b.writeBoolean(e.hasPos());
            if (e.hasPos()) {
                b.writeDouble(e.x());
                b.writeDouble(e.y());
                b.writeDouble(e.z());
            }
        });
        buf.writeUtf(extracting);
        buf.writeFloat(progress);
    }

    public static RaidHudPacket decode(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return inactive();
        }
        String map = buf.readUtf();
        int seconds = buf.readVarInt();
        List<ExtractEntry> extracts = buf.readList(b -> {
            String name = b.readUtf();
            boolean hasPos = b.readBoolean();
            return hasPos ? new ExtractEntry(name, true, b.readDouble(), b.readDouble(), b.readDouble())
                    : new ExtractEntry(name, false, 0, 0, 0);
        });
        return new RaidHudPacket(true, map, seconds, extracts, buf.readUtf(), buf.readFloat());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.ClientPacketHandlers.handleHud(this));
    }
}
