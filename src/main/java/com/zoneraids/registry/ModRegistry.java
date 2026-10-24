package com.zoneraids.registry;

import com.zoneraids.ZoneRaids;
import com.zoneraids.block.HiddenStashBlock;
import com.zoneraids.block.HiddenStashBlockEntity;
import com.zoneraids.block.LootCrateBlock;
import com.zoneraids.block.LootCrateBlockEntity;
import com.zoneraids.block.RaidTerminalBlock;
import com.zoneraids.block.StashBlock;
import com.zoneraids.block.TraderBlock;
import com.zoneraids.item.CordonMapItem;
import com.zoneraids.item.StashDriveItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public final class ModRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ZoneRaids.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ZoneRaids.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ZoneRaids.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ZoneRaids.MODID);

    public static final TagKey<Block> RAID_BREAKABLE = BlockTags.create(new ResourceLocation(ZoneRaids.MODID, "raid_breakable"));

    public static final RegistryObject<Block> RAID_TERMINAL = block("raid_terminal", () -> new RaidTerminalBlock(adminProps(MapColor.METAL, SoundType.METAL)));
    public static final RegistryObject<Block> STASH = block("stash", () -> new StashBlock(adminProps(MapColor.METAL, SoundType.METAL)));
    public static final RegistryObject<Block> TRADER = block("trader", () -> new TraderBlock(adminProps(MapColor.WOOD, SoundType.WOOD)));
    public static final RegistryObject<Block> COMMON_CRATE = block("common_crate",
            () -> new LootCrateBlock(adminProps(MapColor.WOOD, SoundType.WOOD), crateLoot("common")));
    public static final RegistryObject<Block> MILITARY_CRATE = block("military_crate",
            () -> new LootCrateBlock(adminProps(MapColor.COLOR_GREEN, SoundType.WOOD), crateLoot("military")));
    public static final RegistryObject<Block> MEDICAL_CRATE = block("medical_crate",
            () -> new LootCrateBlock(adminProps(MapColor.SNOW, SoundType.METAL), crateLoot("medical")));
    public static final RegistryObject<Block> WEAPON_CRATE = block("weapon_crate",
            () -> new LootCrateBlock(adminProps(MapColor.COLOR_GRAY, SoundType.WOOD), crateLoot("weapons")));

    public static final RegistryObject<Block> HIDDEN_STASH = block("hidden_stash",
            () -> new HiddenStashBlock(adminProps(MapColor.COLOR_GREEN, SoundType.WOOD).noOcclusion()));

    public static final RegistryObject<Item> CORDON_MAP = ITEMS.register("cordon_map", () -> new CordonMapItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> STASH_DRIVE = ITEMS.register("stash_drive", () -> new StashDriveItem(new Item.Properties().stacksTo(16)));

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<LootCrateBlockEntity>> LOOT_CRATE_BE = BLOCK_ENTITIES.register("loot_crate",
            () -> BlockEntityType.Builder.of(LootCrateBlockEntity::new,
                    COMMON_CRATE.get(), MILITARY_CRATE.get(), MEDICAL_CRATE.get(), WEAPON_CRATE.get()).build(null));
    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<HiddenStashBlockEntity>> HIDDEN_STASH_BE = BLOCK_ENTITIES.register("hidden_stash",
            () -> BlockEntityType.Builder.of(HiddenStashBlockEntity::new, HIDDEN_STASH.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.zoneraids"))
            .icon(() -> new ItemStack(RAID_TERMINAL.get()))
            .displayItems((params, output) -> {
                output.accept(RAID_TERMINAL.get());
                output.accept(STASH.get());
                output.accept(TRADER.get());
                output.accept(COMMON_CRATE.get());
                output.accept(MILITARY_CRATE.get());
                output.accept(MEDICAL_CRATE.get());
                output.accept(WEAPON_CRATE.get());
                output.accept(HIDDEN_STASH.get());
                output.accept(CORDON_MAP.get());
                output.accept(STASH_DRIVE.get());
            })
            .build());

    private ModRegistry() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }

    /** Map furniture: unbreakable in survival, removable in creative. */
    private static BlockBehaviour.Properties adminProps(MapColor color, SoundType sound) {
        return BlockBehaviour.Properties.of().mapColor(color).sound(sound).strength(-1.0F, 3600000.0F).noLootTable();
    }

    private static ResourceLocation crateLoot(String name) {
        return new ResourceLocation(ZoneRaids.MODID, "crates/" + name);
    }

    private static RegistryObject<Block> block(String name, Supplier<Block> factory) {
        RegistryObject<Block> block = BLOCKS.register(name, factory);
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }
}
