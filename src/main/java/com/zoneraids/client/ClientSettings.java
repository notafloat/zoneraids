package com.zoneraids.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings of the Tarkov-style lean, the Arma-style interaction menu and the Zone look, config/zoneraids-client.toml. */
public final class ClientSettings {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TOGGLE;
    public static final ForgeConfigSpec.BooleanValue SCROLL_MENU;
    public static final ForgeConfigSpec.DoubleValue LOOK_STRENGTH;
    public static final ForgeConfigSpec.BooleanValue LOOK_FOG;
    public static final ForgeConfigSpec.BooleanValue VANILLA_MUSIC;
    public static final ForgeConfigSpec.BooleanValue AMBIENCE;

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
        b.push("zoneLook");
        LOOK_STRENGTH = b.comment("How far grass, leaves, water, sky, fog and clouds are muted towards the grey, olive Zone:",
                        "0 = Minecraft's own colours, 1 = full Zone look. Blocks already drawn keep their colour until F3+A.")
                .defineInRange("strength", 1.0, 0.0, 1.0);
        LOOK_FOG = b.comment("Thicker grey haze in the distance (the shader pack draws its own fog when it is on).")
                .define("fog", true);
        VANILLA_MUSIC = b.comment("Play Minecraft's background music in the world. Off: only the wind and the Zone are heard.")
                .define("vanillaMusic", false);
        AMBIENCE = b.comment("Zone ambience: wind outdoors, and in the survival world far-off dogs howling, gunfire and explosions.")
                .define("ambience", true);
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

    static float lookStrength() {
        try {
            return LOOK_STRENGTH.get().floatValue();
        } catch (IllegalStateException e) {
            return LOOK_STRENGTH.getDefault().floatValue();
        }
    }

    static boolean lookFog() {
        return get(LOOK_FOG);
    }

    static boolean vanillaMusic() {
        return get(VANILLA_MUSIC);
    }

    static boolean ambience() {
        return get(AMBIENCE);
    }

    private static boolean get(ForgeConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return value.getDefault();
        }
    }
}
