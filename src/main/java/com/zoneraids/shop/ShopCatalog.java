package com.zoneraids.shop;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The trader's price lists, read from the {@code [shop]} config section. Parsed on every use (a few
 * dozen short lines, only when someone trades), so config edits apply without a restart.
 * Lines with items that are not installed are skipped silently, broken lines are logged once.
 */
public final class ShopCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> WARNED = new HashSet<>();

    /** {@code index} is the line's position in the config list, so a buy request names the line it was shown. */
    public record Offer(int index, String category, int price, ItemStack stack) {
    }

    public record SellPrice(Item item, String id, int price) {
    }

    private ShopCatalog() {
    }

    public static List<Offer> offers() {
        List<Offer> offers = new ArrayList<>();
        List<? extends String> lines = RaidConfig.SHOP_BUY.get();
        for (int i = 0; i < lines.size(); i++) {
            Offer offer = parseOffer(i, lines.get(i));
            if (offer != null) {
                offers.add(offer);
            }
        }
        return offers;
    }

    @Nullable
    public static Offer offer(int index) {
        List<? extends String> lines = RaidConfig.SHOP_BUY.get();
        return index < 0 || index >= lines.size() ? null : parseOffer(index, lines.get(index));
    }

    public static List<SellPrice> sellPrices() {
        List<SellPrice> prices = new ArrayList<>();
        for (String line : RaidConfig.SHOP_SELL.get()) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) {
                warn(line);
                continue;
            }
            Item item = item(parts[0]);
            int price = price(parts[1]);
            if (price < 0) {
                warn(line);
            } else if (item != null) {
                prices.add(new SellPrice(item, parts[0], price));
            }
        }
        return prices;
    }

    @Nullable
    public static SellPrice sellPrice(String id) {
        for (SellPrice price : sellPrices()) {
            if (price.id().equals(id)) {
                return price;
            }
        }
        return null;
    }

    /** Item ids in the price lists that are not installed (those lines are skipped in the shop). Logs each one. */
    public static List<String> unknownIds() {
        List<String> ids = new ArrayList<>();
        List<String> specs = new ArrayList<>();
        for (String line : RaidConfig.SHOP_SELL.get()) {
            specs.add(line.trim().split("\\s+")[0]);
        }
        for (String line : RaidConfig.SHOP_BUY.get()) {
            String[] parts = line.trim().split("\\s+", 4);
            if (parts.length == 4) {
                int brace = parts[3].indexOf('{');
                specs.add(brace < 0 ? parts[3] : parts[3].substring(0, brace));
            }
        }
        for (String id : specs) {
            if (item(id) == null && !ids.contains(id)) {
                ids.add(id);
                LOGGER.warn("Zone Raids shop: unknown item {}", id);
            }
        }
        return ids;
    }

    /** {@code category price count item_id{nbt}} */
    @Nullable
    private static Offer parseOffer(int index, String line) {
        String[] parts = line.trim().split("\\s+", 4);
        if (parts.length != 4) {
            warn(line);
            return null;
        }
        int price = price(parts[1]);
        int count = price(parts[2]);
        if (price < 0 || count < 1) {
            warn(line);
            return null;
        }
        String spec = parts[3];
        int brace = spec.indexOf('{');
        Item item = item(brace < 0 ? spec : spec.substring(0, brace));
        if (item == null) {
            return null;
        }
        ItemStack stack = new ItemStack(item, Math.min(count, 64));
        if (brace >= 0) {
            try {
                CompoundTag tag = TagParser.parseTag(spec.substring(brace));
                stack.setTag(tag);
            } catch (CommandSyntaxException e) {
                warn(line);
                return null;
            }
        }
        return new Offer(index, parts[0].toLowerCase(), price, stack);
    }

    @Nullable
    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !ForgeRegistries.ITEMS.containsKey(key)) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == Items.AIR ? null : item;
    }

    private static int price(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void warn(String line) {
        synchronized (WARNED) {
            if (WARNED.add(line)) {
                LOGGER.warn("Zone Raids shop: cannot read config line \"{}\"", line);
            }
        }
    }
}
