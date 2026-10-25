package com.zoneraids.item;

import com.zoneraids.stash.StashDrives;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Flash drive off a dead bandit or soldier: reading it hides a stash nearby and marks it on the PDA map. */
public class StashDriveItem extends Item {
    public StashDriveItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (player instanceof ServerPlayer sp && StashDrives.reveal(sp)) {
            if (!sp.getAbilities().instabuild) {
                stack.shrink(1);
            }
            sp.getCooldowns().addCooldown(this, 20);
            return InteractionResultHolder.consume(stack);
        }
        return InteractionResultHolder.fail(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.zoneraids.stash_drive.desc").withStyle(ChatFormatting.GRAY));
    }
}
