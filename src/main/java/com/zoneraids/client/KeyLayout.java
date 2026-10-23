package com.zoneraids.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.zoneraids.ZoneRaids;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The pack's key layout. The mods' own defaults put up to five bindings on one key (V: melee, scope zoom,
 * mantle, voice chat menu, detector mode; R: reload and Oculus shader reload; K: raid terminal and shader
 * toggle; Shift: sneak and tactical sprint). On the first start of a client with this layout, every binding
 * listed here that is still on its mod's default is moved, once, so a player's own choice is never touched.
 * The defaults were read from the jars by modpack/tools/key_defaults.py (report/key-defaults.txt in CI).
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KeyLayout {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Bump the number when the table changes, so clients apply the new entries once more. */
    private static final String MARKER = "zoneraids-keys-2.done";
    private static final Map<String, InputConstants.Key> LAYOUT = new HashMap<>();
    private static boolean checked;

    static {
        // Tarkov-style: lean on Q/E, inventory on Tab.
        key("key.inventory", GLFW.GLFW_KEY_TAB);
        key("key.playerlist", GLFW.GLFW_KEY_GRAVE_ACCENT);
        key("key.drop", GLFW.GLFW_KEY_BACKSPACE);
        // TaCZ: the scope zoom moves off V (melee stays there) to the wheel click; its crawl duplicates prone.
        mouse("key.tacz.zoom.desc", GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        unbind("key.pickItem");
        unbind("key.tacz.crawl.desc");
        // TACtical Movement: the aim animation must share the aim button, or others never see it.
        mouse("key.tacmove.aim_animation", GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        key("key.tacmove.tac_sprint", GLFW.GLFW_KEY_LEFT_ALT);
        unbind("key.tacmove.mantle"); // Moves Like Mafuyu climbs on jump
        // Lean is Zone Raids' own (key.zoneraids.lean_left/right on Q/E, smooth and visible to others).
        unbind("key.tacmove.lean_left");
        unbind("key.tacmove.lean_right");
        unbind("key.tacmove.high_ready");
        unbind("key.tacmove.low_ready");
        // Moves Like Mafuyu shortcuts are off in its config, but their keys would still sit on Shift and Ctrl.
        unbind("key.moveslikemafuyu.roll");
        unbind("key.moveslikemafuyu.slide");
        // Gear, health and artifacts.
        key("keybinds.show_wounds", GLFW.GLFW_KEY_L);
        unbind("key.advancements");
        key("key.curios.open.desc", GLFW.GLFW_KEY_I);
        key("key.warborn.toggle_helmet_top", GLFW.GLFW_KEY_Y);
        key("key.anomalyzone.toggle_detector_mode", GLFW.GLFW_KEY_J);
        // Simple Voice Chat: the menu goes to P (its groups are a button there), mute stays on M.
        key("key.voice_chat", GLFW.GLFW_KEY_P);
        unbind("key.socialInteractions");
        unbind("key.voice_chat_group");
        unbind("key.hide_icons");
        unbind("key.disable_voice_chat");
        // Oculus: shader reload would fire on every TaCZ reload.
        unbind("iris.keybind.reload");
        key("iris.keybind.toggleShaders", GLFW.GLFW_KEY_F7);
        key("iris.keybind.shaderPackSelection", GLFW.GLFW_KEY_F8);
        // Creative hotbar save/load, of no use on a raid server.
        unbind("key.saveToolbarActivator");
        unbind("key.loadToolbarActivator");
    }

    private KeyLayout() {
    }

    private static void key(String name, int code) {
        LAYOUT.put(name, InputConstants.Type.KEYSYM.getOrCreate(code));
    }

    private static void mouse(String name, int button) {
        LAYOUT.put(name, InputConstants.Type.MOUSE.getOrCreate(button));
    }

    private static void unbind(String name) {
        LAYOUT.put(name, InputConstants.UNKNOWN);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (checked || event.phase != TickEvent.Phase.END) {
            return;
        }
        checked = true;
        Path marker = FMLPaths.CONFIGDIR.get().resolve(MARKER);
        if (Files.exists(marker)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int moved = 0;
        for (KeyMapping key : mc.options.keyMappings) {
            InputConstants.Key target = LAYOUT.get(key.getName());
            if (target == null || !key.isDefault() || key.getKey().equals(target)) {
                continue;
            }
            key.setKeyModifierAndCode(KeyModifier.NONE, target);
            moved++;
        }
        if (moved > 0) {
            KeyMapping.resetMapping();
            mc.options.save();
            LOGGER.info("Zone Raids: moved {} key bindings to the pack layout", moved);
        }
        try {
            Files.writeString(marker, "Zone Raids key layout applied; delete to apply it again to keys still on their defaults.\n");
        } catch (IOException e) {
            LOGGER.warn("Zone Raids: could not write {}", marker, e);
        }
    }
}
