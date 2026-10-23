package com.zoneraids.network;

import com.zoneraids.interact.Interactions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Client -> server: an action picked in the interaction menu, on an entity (id 0 or more), on a block, or on
 * the player themselves (neither). The server checks the action is on offer before running it.
 */
public record InteractPacket(Interactions.Action action, int entityId, @Nullable BlockPos pos, Direction face) {
    public static InteractPacket self(Interactions.Action action) {
        return new InteractPacket(action, -1, null, Direction.UP);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeVarInt(entityId + 1);
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeBlockPos(pos);
        }
        buf.writeEnum(face);
    }

    public static InteractPacket decode(FriendlyByteBuf buf) {
        Interactions.Action action = buf.readEnum(Interactions.Action.class);
        int entityId = buf.readVarInt() - 1;
        BlockPos pos = buf.readBoolean() ? buf.readBlockPos() : null;
        return new InteractPacket(action, entityId, pos, buf.readEnum(Direction.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null || !player.isAlive() || player.isSpectator()) {
            return;
        }
        if (entityId >= 0) {
            Interactions.performOnEntity(player, action, entityId);
        } else if (pos != null) {
            Interactions.performOnBlock(player, action, pos, face);
        } else {
            Interactions.performSelf(player, action);
        }
    }
}
