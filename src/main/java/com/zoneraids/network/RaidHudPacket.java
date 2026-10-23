package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client: raid HUD state. Sent about once per second while in a raid, and to the players
 * of a session waiting in the hub between rounds.
 */
public record RaidHudPacket(boolean active, String mapName, int secondsLeft, List<ExtractEntry> extracts,
                            String extracting, float progress, Round round) {
    private static final int MAX_EXTRACTS = 16;

    public record ExtractEntry(String name, boolean hasPos, double x, double y, double z) {
    }

    /**
     * Round of the session. {@code intermission}: the player waits in the hub and {@code secondsLeft}
     * counts down to round {@code number}. The target (if any) is a point the client shows a distance to.
     */
    public record Round(int number, int count, String type, boolean intermission, boolean hasTarget, double tx, double tz,
                        Component targetLabel, Component status, float progress) {
        public static final Round NONE = new Round(0, 0, "", false, false, 0, 0, Component.empty(), Component.empty(), -1.0F);

        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(number);
            buf.writeVarInt(count);
            buf.writeUtf(type);
            buf.writeBoolean(intermission);
            buf.writeBoolean(hasTarget);
            if (hasTarget) {
                buf.writeDouble(tx);
                buf.writeDouble(tz);
            }
            buf.writeComponent(targetLabel);
            buf.writeComponent(status);
            buf.writeFloat(progress);
        }

        static Round decode(FriendlyByteBuf buf) {
            int number = buf.readVarInt();
            int count = buf.readVarInt();
            String type = buf.readUtf();
            boolean intermission = buf.readBoolean();
            boolean hasTarget = buf.readBoolean();
            double tx = hasTarget ? buf.readDouble() : 0;
            double tz = hasTarget ? buf.readDouble() : 0;
            return new Round(number, count, type, intermission, hasTarget, tx, tz, buf.readComponent(), buf.readComponent(), buf.readFloat());
        }
    }

    public static RaidHudPacket inactive() {
        return new RaidHudPacket(false, "", 0, List.of(), "", -1.0F, Round.NONE);
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
        round.encode(buf);
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
        return new RaidHudPacket(true, map, seconds, extracts, buf.readUtf(), buf.readFloat(), Round.decode(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.ClientPacketHandlers.handleHud(this));
    }
}
