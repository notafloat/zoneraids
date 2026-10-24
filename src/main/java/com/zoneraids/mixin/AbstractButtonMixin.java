package com.zoneraids.mixin;

import com.zoneraids.client.VanillaStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's plain and cycling buttons (and any that keep its look) drawn as PDA buttons on its own screens. */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void zoneraids$renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VanillaStyle.restyles()) {
            VanillaStyle.button(g, (AbstractWidget) (Object) this);
            ci.cancel();
        }
    }
}
