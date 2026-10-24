package com.zoneraids.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat PDA-style button: dark plate, olive edge, amber edge and text on hover. A selected button (a PDA tab) stays amber. */
public class ZoneButton extends Button {
    public boolean selected;

    public ZoneButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        draw(g, this, selected);
    }

    /** Draws any widget as a PDA button (also vanilla's buttons, see {@link SkinnedButton}). */
    static void draw(GuiGraphics g, AbstractWidget widget, boolean selected) {
        Font font = Minecraft.getInstance().font;
        int x0 = widget.getX();
        int y0 = widget.getY();
        int x1 = x0 + widget.getWidth();
        int y1 = y0 + widget.getHeight();
        boolean active = widget.active;
        boolean hot = selected || active && widget.isHoveredOrFocused();
        g.fill(x0, y0, x1, y1, !active ? 0xC0181812 : hot ? 0xE0363824 : 0xE0262619);
        ZoneUi.outline(g, x0, y0, x1, y1, hot ? ZoneUi.AMBER : ZoneUi.EDGE);
        if (hot) {
            g.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, 0x80D9A441);
        }
        int color = !active ? ZoneUi.GREY : hot ? ZoneUi.AMBER : ZoneUi.TEXT;
        String text = ZoneUi.clip(font, widget.getMessage().getString(), widget.getWidth() - 6);
        g.drawCenteredString(font, text, (x0 + x1) / 2, y0 + (widget.getHeight() - 8) / 2, color);
    }
}
