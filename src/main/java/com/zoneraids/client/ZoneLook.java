package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The Zone look without a texture pack: biome colours (grass, leaves, water, sky, fog) and clouds are
 * muted towards an overcast grey and a dry olive (mixin/BiomeMixin, mixin/ClientLevelMixin), the haze
 * comes closer, and Minecraft's background music is silenced in the world. Works with and without the
 * shader pack, which tints the same block colours.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ZoneLook {
    /** Read on the chunk meshing threads too, so the config value is copied here once a tick. */
    private static volatile float strength = 1.0F;

    private ZoneLook() {
    }

    public static int grass(int rgb) {
        return grade(rgb, 0.45F, 0x7D7A52, 0.3F, 0.88F);
    }

    public static int foliage(int rgb) {
        return grade(rgb, 0.45F, 0x6A6B45, 0.3F, 0.85F);
    }

    public static int water(int rgb) {
        return grade(rgb, 0.35F, 0x3F4D3C, 0.55F, 0.9F);
    }

    public static int waterFog(int rgb) {
        return grade(rgb, 0.3F, 0x1F2A1E, 0.6F, 1.0F);
    }

    public static int sky(int rgb) {
        return grade(rgb, 0.25F, 0x8D9396, 0.55F, 0.92F);
    }

    public static int fog(int rgb) {
        return grade(rgb, 0.3F, 0xA3A89F, 0.5F, 0.95F);
    }

    public static Vec3 clouds(Vec3 color) {
        float s = strength;
        return new Vec3(color.x * (1.0F - 0.24F * s), color.y * (1.0F - 0.22F * s), color.z * (1.0F - 0.21F * s));
    }

    /**
     * Desaturates a colour to {@code saturation}, mixes it with {@code tint} and scales its brightness,
     * all scaled by the configured strength.
     */
    static int grade(int rgb, float saturation, int tint, float tintMix, float brightness) {
        float s = strength;
        if (s <= 0.0F) {
            return rgb;
        }
        float r = (rgb >> 16 & 0xFF) / 255.0F;
        float g = (rgb >> 8 & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float luma = 0.299F * r + 0.587F * g + 0.114F * b;
        float sat = 1.0F - s * (1.0F - saturation);
        float mix = s * tintMix;
        float bright = 1.0F - s * (1.0F - brightness);
        r = ((luma + (r - luma) * sat) * (1.0F - mix) + (tint >> 16 & 0xFF) / 255.0F * mix) * bright;
        g = ((luma + (g - luma) * sat) * (1.0F - mix) + (tint >> 8 & 0xFF) / 255.0F * mix) * bright;
        b = ((luma + (b - luma) * sat) * (1.0F - mix) + (tint & 0xFF) / 255.0F * mix) * bright;
        return (rgb & 0xFF000000) | channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0F)));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            strength = ClientSettings.lookStrength();
        }
    }

    /** The far haze starts close and ends before the render distance, thicker in rain. Only above ground in skies like the overworld's. */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || strength <= 0.0F || !ClientSettings.lookFog() || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN
                || event.getType() != FogType.NONE || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        float far = event.getFarPlaneDistance();
        float rain = level.getRainLevel((float) event.getPartialTick());
        float end = far * (1.0F - strength * (0.2F + 0.15F * rain));
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), end * (1.0F - strength * 0.85F)));
        event.setFarPlaneDistance(end);
        // Forge applies the new distances only to a cancelled event.
        event.setCanceled(true);
    }

    /** No C418 in the world: vanilla game, creative, underwater and biome music. The menu and the End keep theirs. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null || sound.getSource() != SoundSource.MUSIC || Minecraft.getInstance().level == null
                || ClientSettings.vanillaMusic()) {
            return;
        }
        var id = sound.getLocation();
        String path = id.getPath();
        if (id.getNamespace().equals("minecraft") && path.startsWith("music.") && !path.equals("music.menu")
                && !path.equals("music.credits") && !path.equals("music.end") && !path.equals("music.dragon")) {
            event.setSound(null);
        }
    }
}
