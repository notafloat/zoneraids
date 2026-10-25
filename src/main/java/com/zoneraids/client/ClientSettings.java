package com.zoneraids.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings of the Tarkov-style lean, the Arma-style interaction menu and the HUD compass, config/zoneraids-client.toml. */
public final class ClientSettings {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TOGGLE;
    public static final ForgeConfigSpec.BooleanValue SCROLL_MENU;
    public static final ForgeConfigSpec.BooleanValue COMPASS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("lean");
        TOGGLE = b.comment("true: a press of Q/E leans and the lean stays until pressed again (Escape from Tarkov's default).",
                        "false: the lean lasts while the key is held.")
                .define("toggle", true);
        b.pop();
        b.push("interaction");
        SCROLL_MENU = b.comment("true: looking at a door, crate, bike or player, the mouse wheel opens and scrolls its action list",
                        "(Arma 3's action menu) instead of switching the hotbar slot.",
                        "false: the wheel switches slots until the list is open; the interaction key opens it.")
                .define("scrollOpensMenu", true);
        b.pop();
        b.push("hud");
        COMPASS = b.comment("true: the compass strip with the azimuth and marker pins at the top of the screen.")
                .define("compass", true);
        b.pop();
        SPEC = b.build();
    }

    private ClientSettings() {
    }

    static boolean toggle() {
        return get(TOGGLE);
    }

    static boolean scrollMenu() {
        return get(SCROLL_MENU);
    }

    static boolean compass() {
        return get(COMPASS);
    }

    private static boolean get(ForgeConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return value.getDefault();
        }
    }
}
