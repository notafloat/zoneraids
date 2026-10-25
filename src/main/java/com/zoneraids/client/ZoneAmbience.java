package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import com.zoneraids.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Sounds of the Zone in place of Minecraft's music: a wind loop that grows in the open, on hills and in
 * rain, and in the overworld (the survival world) far-off dogs howling, gunfire and explosions from a
 * random direction. Raid maps and the hub get only the wind, so a fake shot is never taken for an enemy.
 * Everything plays on the Ambient/Environment volume slider.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ZoneAmbience {
    private static final RandomSource RANDOM = RandomSource.create();
    private static WindSound wind;
    /** Ticks until the wind may be started again (it does not start while the ambient volume is 0). */
    private static int windRetry;
    private static ClientLevel lastLevel;
    private static int howlIn;
    private static int shotsIn;
    private static int explosionIn;

    private ZoneAmbience() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || mc.player == null || mc.level == null || mc.isPaused()) {
            return;
        }
        if (!ClientSettings.ambience()) {
            if (wind != null) {
                wind.finish();
                wind = null;
            }
            return;
        }
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            howlIn = 1200 + RANDOM.nextInt(2400);
            shotsIn = 1200 + RANDOM.nextInt(3600);
            explosionIn = 6000 + RANDOM.nextInt(6000);
        }
        SoundManager sounds = mc.getSoundManager();
        if ((wind == null || wind.isStopped() || !sounds.isActive(wind)) && --windRetry <= 0) {
            windRetry = 100;
            wind = new WindSound();
            sounds.play(wind);
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (level.dimension() != Level.OVERWORLD || level.getBrightness(LightLayer.SKY, BlockPos.containing(player.getEyePosition())) < 6) {
            return;
        }
        long time = level.getDayTime() % 24000L;
        boolean night = time > 13000L && time < 23000L;
        if (--howlIn <= 0) {
            howlIn = night ? 1600 + RANDOM.nextInt(2400) : 4800 + RANDOM.nextInt(4800);
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            int howls = 1 + RANDOM.nextInt(3);
            for (int i = 0; i < howls; i++) {
                // A pack: the next dogs answer from close by, a moment later.
                far(sounds, player, ModSounds.FAR_HOWL.get(), 0.7F, 0.7F + RANDOM.nextFloat() * 0.2F,
                        angle + (RANDOM.nextDouble() - 0.5) * 0.6, 60 + RANDOM.nextInt(40), i * (25 + RANDOM.nextInt(30)));
            }
        }
        if (--shotsIn <= 0) {
            shotsIn = 2400 + RANDOM.nextInt(4800);
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            int delay = burst(sounds, player, angle, 0);
            if (RANDOM.nextFloat() < 0.4F) {
                // Someone answers from another direction.
                burst(sounds, player, angle + Math.PI * (0.5 + RANDOM.nextDouble()), delay + 10 + RANDOM.nextInt(30));
            }
        }
        if (--explosionIn <= 0) {
            explosionIn = 9600 + RANDOM.nextInt(9600);
            far(sounds, player, ModSounds.FAR_EXPLOSION.get(), 1.0F, 0.5F + RANDOM.nextFloat() * 0.2F,
                    RANDOM.nextDouble() * Math.PI * 2.0, 110 + RANDOM.nextInt(50), 0);
        }
    }

    /** A burst of automatic fire or a few single shots; returns the delay of the last shot. */
    private static int burst(SoundManager sounds, LocalPlayer player, double angle, int delay) {
        boolean automatic = RANDOM.nextFloat() < 0.6F;
        int shots = automatic ? 3 + RANDOM.nextInt(6) : 1 + RANDOM.nextInt(3);
        int distance = 70 + RANDOM.nextInt(60);
        for (int i = 0; i < shots; i++) {
            far(sounds, player, ModSounds.FAR_SHOT.get(), 0.8F, 0.9F + RANDOM.nextFloat() * 0.2F, angle, distance, delay);
            delay += automatic ? 2 + RANDOM.nextInt(2) : 12 + RANDOM.nextInt(20);
        }
        return delay;
    }

    private static void far(SoundManager sounds, LocalPlayer player, SoundEvent event, float volume, float pitch,
                            double angle, int distance, int delay) {
        double x = player.getX() + Math.cos(angle) * distance;
        double z = player.getZ() + Math.sin(angle) * distance;
        SimpleSoundInstance sound = new SimpleSoundInstance(event, SoundSource.AMBIENT, volume, pitch,
                RandomSource.create(RANDOM.nextLong()), x, player.getEyeY() + 12.0, z);
        if (delay > 0) {
            sounds.playDelayed(sound, delay);
        } else {
            sounds.play(sound);
        }
    }

    /** The wind, heard everywhere (not placed in the world), louder where the sky is open, higher up and in rain. */
    private static final class WindSound extends AbstractTickableSoundInstance {
        WindSound() {
            super(ModSounds.WIND.get(), SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        void finish() {
            stop();
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null || player.isRemoved()) {
                stop();
                return;
            }
            float target = 0.0F;
            if (level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()) {
                float open = level.getBrightness(LightLayer.SKY, BlockPos.containing(player.getEyePosition())) / 15.0F;
                float height = Mth.clamp((float) (player.getY() - 62.0) / 80.0F, 0.0F, 1.0F);
                target = open * open * (0.3F + 0.3F * height + 0.25F * level.getRainLevel(1.0F));
                if (player.isUnderWater()) {
                    target *= 0.15F;
                }
            }
            volume = Mth.lerp(0.03F, volume, Math.max(0.01F, target));
        }
    }
}
