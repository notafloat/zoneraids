package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Server -> client: everything the raid terminal screen shows. */
public record TerminalDataPacket(boolean open, List<MapEntry> maps, String queuedMap, boolean inRaid, int[] stats,
                                 List<String> party, boolean partyLeader, String pendingInvite, boolean canStash,
                                 int perkPoints, int[] perks, List<WatchEntry> watchable, String watching,
                                 boolean canShop, Ready ready) {
    /** "Ready" state between rounds: {@code count} of {@code total} session members in the hub are ready. */
    public record Ready(boolean available, boolean mine, int count, int total) {
        public static final Ready NONE = new Ready(false, false, 0, 0);
    }

    public static final byte STATE_IDLE = 0;
    public static final byte STATE_COUNTDOWN = 1;
    public static final byte STATE_RUNNING = 2;
    public static final byte STATE_UNAVAILABLE = 3;

    private static final int MAX_MAPS = 256;
    private static final int MAX_PARTY = 32;
    private static final int MAX_WATCHABLE = 64;

    public record MapEntry(String id, String name, byte state, int queued, int inRaid, int min, int max, int seconds,
                           int duration) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(id);
            buf.writeUtf(name);
            buf.writeByte(state);
            buf.writeVarInt(queued);
            buf.writeVarInt(inRaid);
            buf.writeVarInt(min);
            buf.writeVarInt(max);
            buf.writeVarInt(seconds);
            buf.writeVarInt(duration);
        }

        static MapEntry decode(FriendlyByteBuf buf) {
            return new MapEntry(buf.readUtf(), buf.readUtf(), buf.readByte(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /** A raider the player may watch: {@code id} is the raider's UUID, {@code teammate} marks their own session. */
    public record WatchEntry(String id, String name, String map, int round, int rounds, int seconds, boolean teammate) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(id);
            buf.writeUtf(name);
            buf.writeUtf(map);
            buf.writeVarInt(round);
            buf.writeVarInt(rounds);
            buf.writeVarInt(seconds);
            buf.writeBoolean(teammate);
        }

        static WatchEntry decode(FriendlyByteBuf buf) {
            return new WatchEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeCollection(maps.subList(0, Math.min(maps.size(), MAX_MAPS)), (b, e) -> e.encode(b));
        buf.writeUtf(queuedMap);
        buf.writeBoolean(inRaid);
        buf.writeVarIntArray(stats);
        buf.writeCollection(party.subList(0, Math.min(party.size(), MAX_PARTY)), FriendlyByteBuf::writeUtf);
        buf.writeBoolean(partyLeader);
        buf.writeUtf(pendingInvite);
        buf.writeBoolean(canStash);
        buf.writeVarInt(perkPoints);
        buf.writeVarIntArray(perks);
        buf.writeCollection(watchable.subList(0, Math.min(watchable.size(), MAX_WATCHABLE)), (b, e) -> e.encode(b));
        buf.writeUtf(watching);
        buf.writeBoolean(canShop);
        buf.writeBoolean(ready.available());
        buf.writeBoolean(ready.mine());
        buf.writeVarInt(ready.count());
        buf.writeVarInt(ready.total());
    }

    public static TerminalDataPacket decode(FriendlyByteBuf buf) {
        return new TerminalDataPacket(buf.readBoolean(), buf.readList(MapEntry::decode), buf.readUtf(), buf.readBoolean(),
                buf.readVarIntArray(16), buf.readList(FriendlyByteBuf::readUtf), buf.readBoolean(), buf.readUtf(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarIntArray(32), buf.readList(WatchEntry::decode), buf.readUtf(),
                buf.readBoolean(), new Ready(buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.ClientPacketHandlers.handleTerminal(this));
    }
}
