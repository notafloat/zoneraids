package com.zoneraids.perk;

import com.zoneraids.data.PlayerRaidData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/** Buying perks and applying the ones that work through attributes. */
public final class PerkService {
    private static final UUID ATHLETE_SPEED = UUID.fromString("5d1f3f0e-6f43-4a39-9a43-2b2f8f0c7a11");

    private PerkService() {
    }

    public static int level(Player player, Perk perk) {
        return PlayerRaidData.getPerk(player, perk);
    }

    /** @return true if the perk was raised by one level. */
    public static boolean buy(ServerPlayer player, Perk perk) {
        int level = level(player, perk);
        if (level >= Perk.MAX_LEVEL) {
            player.displayClientMessage(Component.translatable("zoneraids.perk.maxed").withStyle(ChatFormatting.RED), true);
            return false;
        }
        int cost = Perk.cost(level + 1);
        int points = PlayerRaidData.getPerkPoints(player);
        if (points < cost) {
            player.displayClientMessage(Component.translatable("zoneraids.perk.no_points", cost).withStyle(ChatFormatting.RED), true);
            return false;
        }
        PlayerRaidData.setPerkPoints(player, points - cost);
        PlayerRaidData.setPerk(player, perk, level + 1);
        apply(player);
        player.sendSystemMessage(Component.translatable("zoneraids.perk.bought",
                Component.translatable("zoneraids.perk." + perk.id), level + 1).withStyle(ChatFormatting.GREEN));
        return true;
    }

    /** Re-applies attribute based perks. Transient modifiers, so this runs on login, respawn and purchase. */
    public static void apply(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(ATHLETE_SPEED);
        int athlete = level(player, Perk.ATHLETE);
        if (athlete > 0) {
            speed.addTransientModifier(new AttributeModifier(ATHLETE_SPEED, "zoneraids athlete", 0.02 * athlete,
                    AttributeModifier.Operation.MULTIPLY_BASE));
        }
    }

    public static void award(ServerPlayer player, int points, String reasonKey) {
        if (points <= 0) {
            return;
        }
        PlayerRaidData.addPerkPoints(player, points);
        player.sendSystemMessage(Component.translatable("zoneraids.perk.awarded", points,
                Component.translatable(reasonKey)).withStyle(ChatFormatting.AQUA));
    }

    public static void showList(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("zoneraids.perk.header", PlayerRaidData.getPerkPoints(player))
                .withStyle(ChatFormatting.GOLD));
        for (Perk perk : Perk.values()) {
            int level = level(player, perk);
            player.sendSystemMessage(Component.translatable("zoneraids.perk.line",
                    Component.translatable("zoneraids.perk." + perk.id), level, Perk.MAX_LEVEL,
                    Component.translatable("zoneraids.perk." + perk.id + ".desc"), perk.id).withStyle(ChatFormatting.GRAY));
        }
    }
}
