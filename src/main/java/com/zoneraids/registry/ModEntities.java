package com.zoneraids.registry;

import com.zoneraids.ZoneRaids;
import com.zoneraids.entity.BlindDog;
import com.zoneraids.entity.Bloodsucker;
import com.zoneraids.entity.Flesh;
import com.zoneraids.entity.Snork;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Zone mutants. They never spawn naturally; raids place them (see {@code EnemySpawner}). */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ZoneRaids.MODID);

    public static final RegistryObject<EntityType<BlindDog>> BLIND_DOG = ENTITIES.register("blind_dog",
            () -> EntityType.Builder.<BlindDog>of(BlindDog::new, MobCategory.MONSTER).sized(0.6F, 0.85F).clientTrackingRange(8).build("blind_dog"));
    public static final RegistryObject<EntityType<Flesh>> FLESH = ENTITIES.register("flesh",
            () -> EntityType.Builder.<Flesh>of(Flesh::new, MobCategory.MONSTER).sized(1.0F, 1.05F).clientTrackingRange(8).build("flesh"));
    public static final RegistryObject<EntityType<Snork>> SNORK = ENTITIES.register("snork",
            () -> EntityType.Builder.<Snork>of(Snork::new, MobCategory.MONSTER).sized(0.6F, 1.95F).clientTrackingRange(8).build("snork"));
    public static final RegistryObject<EntityType<Bloodsucker>> BLOODSUCKER = ENTITIES.register("bloodsucker",
            () -> EntityType.Builder.<Bloodsucker>of(Bloodsucker::new, MobCategory.MONSTER).sized(0.7F, 2.1F).clientTrackingRange(8).build("bloodsucker"));

    private ModEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(ModEntities::onAttributes);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(BLIND_DOG.get(), BlindDog.createAttributes().build());
        event.put(FLESH.get(), Flesh.createAttributes().build());
        event.put(SNORK.get(), Snork.createAttributes().build());
        event.put(BLOODSUCKER.get(), Bloodsucker.createAttributes().build());
    }
}
