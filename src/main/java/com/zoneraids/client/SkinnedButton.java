package com.zoneraids.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;

/**
 * A vanilla button drawn as a PDA button ({@link VanillaStyle}). It stands in for the original in the
 * screen's widget list and hands every click and key to it, so the screen still owns, updates and moves the
 * original: this one copies its position, text and state each frame and only draws.
 */
final class SkinnedButton extends AbstractWidget {
    private final AbstractButton source;

    SkinnedButton(AbstractButton source) {
        super(source.getX(), source.getY(), source.getWidth(), source.getHeight(), source.getMessage());
        this.source = source;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        setX(source.getX());
        setY(source.getY());
        width = source.getWidth();
        height = source.getHeight();
        active = source.active;
        setMessage(source.getMessage());
        // The original draws into an empty scissor: nothing shows, but it keeps its hover state and tooltip.
        g.enableScissor(0, 0, 0, 0);
        source.render(g, mouseX, mouseY, partialTick);
        g.disableScissor();
        if (source.visible) {
            ZoneButton.draw(g, this, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return source.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return source.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return source.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return source.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return source.isMouseOver(mouseX, mouseY);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        source.setFocused(focused);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        source.updateNarration(output);
    }
}
