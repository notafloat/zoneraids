package com.zoneraids.mixin;

import com.zoneraids.client.ZoneLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Grey clouds under the overcast Zone sky (ZoneLook). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "getCloudColor(F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
    private void zoneraids$clouds(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(ZoneLook.clouds(cir.getReturnValue()));
    }
}
