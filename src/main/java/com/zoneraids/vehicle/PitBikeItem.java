package com.zoneraids.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** Puts a free (non-raid) pit bike on the clicked block, facing where the player looks. */
public class PitBikeItem extends Item {
    public PitBikeItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        Player player = context.getPlayer();
        float yaw = player != null ? player.getYRot() : 0.0F;
        Vec3 at = Vec3.atBottomCenterOf(pos);
        PitBike bike = new PitBike(level, at.x, at.y, at.z, yaw);
        if (!level.noCollision(bike, bike.getBoundingBox())) {
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide) {
            level.addFreshEntity(bike);
            level.gameEvent(player, GameEvent.ENTITY_PLACE, pos);
            if (player == null || !player.getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
