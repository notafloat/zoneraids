package com.zoneraids.registry;

import com.zoneraids.ZoneRaids;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Zone ambience (client/ZoneAmbience): a wind loop and far-off sounds of the Zone, see sounds.json. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ZoneRaids.MODID);

    public static final RegistryObject<SoundEvent> WIND = sound("ambient.wind");
    public static final RegistryObject<SoundEvent> FAR_HOWL = sound("ambient.far_howl");
    public static final RegistryObject<SoundEvent> FAR_SHOT = sound("ambient.far_shot");
    public static final RegistryObject<SoundEvent> FAR_EXPLOSION = sound("ambient.far_explosion");

    private ModSounds() {
    }

    private static RegistryObject<SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ZoneRaids.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
