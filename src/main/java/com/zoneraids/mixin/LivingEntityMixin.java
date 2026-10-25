package com.zoneraids.mixin;

import com.zoneraids.combat.NpcHeadshots;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dead human NPCs lie on the ground for a while (npcCorpseSeconds) instead of vanishing after the vanilla second. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void zoneraids$tickDeath(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // Vanilla removes the body on the tick deathTime reaches 20; until the corpse time is up only count.
        if (self.deathTime >= 19 && !self.level().isClientSide() && self.deathTime + 1 < NpcHeadshots.corpseTicks(self)) {
            self.deathTime++;
            ci.cancel();
        }
    }
}
