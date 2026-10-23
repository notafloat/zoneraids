package com.zoneraids.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client: the trader's goods and prices, the player's money and the artifacts they carry.
 * {@code open} asks the client to open the trader screen; refreshes after a deal leave it false.
 */
public record ShopDataPacket(boolean open, long money, boolean canTrade, List<Offer> offers, List<SellEntry> sell) {
    private static final int MAX_OFFERS = 256;

    /** Something the trader sells: {@code index} names it in a buy request, the stack carries the count. */
    public record Offer(int index, String category, int price, ItemStack stack) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(index);
            buf.writeUtf(category);
            buf.writeVarInt(price);
            buf.writeItem(stack);
        }

        static Offer decode(FriendlyByteBuf buf) {
            return new Offer(buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readItem());
        }
    }

    /** Something the trader buys, with how many of it the player carries. */
    public record SellEntry(String itemId, int price, int owned, ItemStack icon) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(itemId);
            buf.writeVarInt(price);
            buf.writeVarInt(owned);
            buf.writeItem(icon);
        }

        static SellEntry decode(FriendlyByteBuf buf) {
            return new SellEntry(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readItem());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeVarLong(money);
        buf.writeBoolean(canTrade);
        buf.writeCollection(offers.subList(0, Math.min(offers.size(), MAX_OFFERS)), (b, o) -> o.encode(b));
        buf.writeCollection(sell.subList(0, Math.min(sell.size(), MAX_OFFERS)), (b, e) -> e.encode(b));
    }

    public static ShopDataPacket decode(FriendlyByteBuf buf) {
        return new ShopDataPacket(buf.readBoolean(), buf.readVarLong(), buf.readBoolean(), buf.readList(Offer::decode),
                buf.readList(SellEntry::decode));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.zoneraids.client.ClientPacketHandlers.handleShop(this));
    }
}
