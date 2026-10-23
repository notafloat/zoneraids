package com.zoneraids.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings of the Tarkov-style lean and the Arma-style interaction menu, config/zoneraids-client.toml. */
public final class ClientSettings {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TOGGLE;
    public static final ForgeConfigSpec.BooleanValue SCROLL_MENU;

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

    private static boolean get(ForgeConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return value.getDefault();
        }
    }
}
