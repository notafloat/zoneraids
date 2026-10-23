package com.zoneraids.network;

import com.zoneraids.ZoneRaids;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class RaidNetwork {
    private static final String PROTOCOL = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ZoneRaids.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private RaidNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(TerminalActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TerminalActionPacket::encode).decoder(TerminalActionPacket::decode)
                .consumerMainThread(TerminalActionPacket::handle).add();
        CHANNEL.messageBuilder(TerminalDataPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TerminalDataPacket::encode).decoder(TerminalDataPacket::decode)
                .consumerMainThread(TerminalDataPacket::handle).add();
        CHANNEL.messageBuilder(RaidHudPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RaidHudPacket::encode).decoder(RaidHudPacket::decode)
                .consumerMainThread(RaidHudPacket::handle).add();
        CHANNEL.messageBuilder(LeanPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(LeanPacket::encode).decoder(LeanPacket::decode)
                .consumerMainThread(LeanPacket::handle).add();
        CHANNEL.messageBuilder(LeanSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LeanSyncPacket::encode).decoder(LeanSyncPacket::decode)
                .consumerMainThread(LeanSyncPacket::handle).add();
    }

    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
