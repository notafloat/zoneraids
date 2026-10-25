package com.zoneraids.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A body kept past the vanilla death second (LivingEntityMixin) stops glowing red once it lies on the ground. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "getOverlayCoords", at = @At("HEAD"), cancellable = true)
    private static void zoneraids$getOverlayCoords(LivingEntity entity, float whiteOverlay, CallbackInfoReturnable<Integer> cir) {
        if (entity.deathTime > 20 && entity.hurtTime <= 0) {
            cir.setReturnValue(OverlayTexture.pack(OverlayTexture.u(whiteOverlay), false));
        }
    }
}
