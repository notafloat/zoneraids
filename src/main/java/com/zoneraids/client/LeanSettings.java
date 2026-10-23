package com.zoneraids.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings of the Tarkov-style lean, config/zoneraids-client.toml. */
public final class LeanSettings {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TOGGLE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("lean");
        TOGGLE = b.comment("true: a press of Q/E leans and the lean stays until pressed again (Escape from Tarkov's default).",
                        "false: the lean lasts while the key is held.")
                .define("toggle", true);
        b.pop();
        SPEC = b.build();
    }

    private LeanSettings() {
    }

    static boolean toggle() {
        try {
            return TOGGLE.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
