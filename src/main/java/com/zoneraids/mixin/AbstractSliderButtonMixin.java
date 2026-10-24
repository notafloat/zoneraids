package com.zoneraids.mixin;

import com.zoneraids.client.VanillaStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's sliders (settings: volume, view distance, FOV...) drawn as PDA sliders on its own screens. */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonMixin {
    @Shadow
    protected double value;

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void zoneraids$renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VanillaStyle.restyles()) {
            VanillaStyle.slider(g, (AbstractWidget) (Object) this, value);
            ci.cancel();
        }
    }
}
