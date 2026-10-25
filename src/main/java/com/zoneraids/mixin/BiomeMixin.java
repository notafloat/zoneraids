package com.zoneraids.mixin;

import com.zoneraids.client.ZoneLook;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Biome colours muted towards the Zone (ZoneLook). Client only: the colours are read for rendering, and
 * hooking the biome itself catches Embeddium's colour blending and other mods' colour handlers too.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {
    @Inject(method = "getGrassColor(DD)I", at = @At("RETURN"), cancellable = true)
    private void zoneraids$grass(double x, double z, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ZoneLook.grass(cir.getReturnValue()));
    }

    @Inject(method = "getFoliageColor()I", at = @At("RETURN"), cancellable = true)
    private void zoneraids$foliage(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ZoneLook.foliage(cir.getReturnValue()));
    }

    @Inject(method = "getWaterColor()I", at = @At("RETURN"), cancellable = true)
    private void zoneraids$water(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ZoneLook.water(cir.getReturnValue()));
    }

    @Inject(method = "getWaterFogColor()I", at = @At("RETURN"), cancellable = true)
    private void zoneraids$waterFog(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ZoneLook.waterFog(cir.getReturnValue()));
    }

    @Inject(method = "getSkyColor()I", at = @At("RETURN"), cancellable = true)
    private void zoneraids$sky(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ZoneLook.sky(cir.getReturnValue()));
    }

    @Inject(method = "getFogColor()I", at = @At("RETURN"), cancellable = true)
    private void zoneraids$fog(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ZoneLook.fog(cir.getReturnValue()));
    }
}
