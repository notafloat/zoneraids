package com.zoneraids.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.zoneraids.ZoneRaids;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Oculus binds shader reload to R and shader toggle to K by default, the same keys as TaCZ reload and the raid
 * terminal: every reload would recompile the shader pack. On the first start of a client this moves the Oculus
 * keys that clash with another binding to free keys, once, so a player's own choice later is kept.
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ShaderKeyFix {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER = "zoneraids-shader-keys.done";
    // Oculus key name -> where it goes when its default key is taken (UNKNOWN = unbound).
    private static final Map<String, InputConstants.Key> FALLBACK = Map.of(
            "iris.keybind.reload", InputConstants.UNKNOWN,
            "iris.keybind.toggleShaders", InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F7),
            "iris.keybind.shaderPackSelection", InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8));
    private static boolean checked;

    private ShaderKeyFix() {
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
        KeyMapping[] all = mc.options.keyMappings;
        boolean found = false;
        boolean changed = false;
        for (KeyMapping key : all) {
            InputConstants.Key fallback = FALLBACK.get(key.getName());
            if (fallback == null) {
                continue;
            }
            found = true;
            if (key.isUnbound() || !key.isDefault() || !clashes(key, all)) {
                continue;
            }
            LOGGER.info("Zone Raids: {} was on {} like another key, moved to {}",
                    key.getName(), key.getKey().getName(), fallback.getName());
            key.setKey(fallback);
            changed = true;
        }
        if (!found) {
            return; // no Oculus: try again on a later start, when it may be installed
        }
        if (changed) {
            KeyMapping.resetMapping();
            mc.options.save();
        }
        try {
            Files.writeString(marker, "Oculus keys checked by Zone Raids; delete to check again.\n");
        } catch (IOException e) {
            LOGGER.warn("Zone Raids: could not write {}", marker, e);
        }
    }

    private static boolean clashes(KeyMapping key, KeyMapping[] all) {
        for (KeyMapping other : all) {
            if (other != key && !FALLBACK.containsKey(other.getName()) && other.getKey().equals(key.getKey())) {
                return true;
            }
        }
        return false;
    }
}
