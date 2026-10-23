package com.zoneraids.client.vehicle;

import com.zoneraids.vehicle.PitBike;
import com.zoneraids.vehicle.Vehicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/** Engine loop that follows a bike while someone drives it; pitch rises with speed. */
public class PitBikeSound extends AbstractTickableSoundInstance {
    private final PitBike bike;

    private PitBikeSound(PitBike bike) {
        super(Vehicles.PIT_BIKE_ENGINE.get(), SoundSource.NEUTRAL, bike.level().getRandom());
        this.bike = bike;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.5F;
        this.pitch = 0.7F;
        this.x = bike.getX();
        this.y = bike.getY();
        this.z = bike.getZ();
    }

    public static void start(PitBike bike) {
        Minecraft.getInstance().getSoundManager().play(new PitBikeSound(bike));
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (bike.isRemoved() || bike.getControllingPassenger() == null) {
            stop();
            return;
        }
        x = bike.getX();
        y = bike.getY();
        z = bike.getZ();
        float load = Mth.clamp(Math.abs(bike.clientSpeed) / 0.5F, 0.0F, 1.0F);
        pitch = Mth.lerp(0.25F, pitch, 0.7F + load * 0.9F);
        volume = Mth.lerp(0.25F, volume, 0.45F + load * 0.4F);
    }
}
