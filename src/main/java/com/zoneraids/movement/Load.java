package com.zoneraids.movement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * What the player carries, in kilograms, as in Arma 3's load. Rough weights by kind of item: worn armor by its
 * protection, TaCZ guns, ammo and attachments by type, tools and weapons, then everything else per item.
 * Items inside backpacks and Curios slots are not counted.
 */
public final class Load {
    private static final float GUN = 4.0F;
    private static final float ATTACHMENT = 0.3F;
    private static final float ROUND = 0.015F;
    private static final float TOOL = 1.5F;
    private static final float ITEM = 0.1F;

    private Load() {
    }

    public static float of(Player player) {
        float kg = 0.0F;
        for (ItemStack stack : player.getInventory().armor) {
            if (stack.getItem() instanceof ArmorItem armor) {
                kg += 1.0F + armor.getDefense() * 1.2F + armor.getToughness();
            } else if (!stack.isEmpty()) {
                kg += 1.0F;
            }
        }
        for (ItemStack stack : player.getInventory().items) {
            kg += carried(stack);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            kg += carried(stack);
        }
        return kg;
    }

    private static float carried(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0F;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && id.getNamespace().equals("tacz")) {
            if (stack.hasTag() && stack.getTag().contains("GunId")) {
                return GUN;
            }
            if (id.getPath().contains("ammo")) {
                return ROUND * stack.getCount();
            }
            if (id.getPath().contains("attachment")) {
                return ATTACHMENT * stack.getCount();
            }
        }
        if (stack.isDamageableItem()) {
            return TOOL * stack.getCount();
        }
        return ITEM * stack.getCount();
    }
}
