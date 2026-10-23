package com.zoneraids.client;

import com.zoneraids.ZoneRaids;
import com.zoneraids.interact.Interactions;
import com.zoneraids.interact.Interactions.Action;
import com.zoneraids.network.InteractPacket;
import com.zoneraids.network.RaidNetwork;
import com.zoneraids.network.TerminalDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Arma 3's action menu. Looking at a door, crate, stash, trader, bike or player shows its default action under
 * the crosshair with the interaction key (F) to do it. The mouse wheel opens the list of all its actions to the
 * right of the crosshair and scrolls it, F does the highlighted one; the list closes after a few seconds, or
 * when the view leaves the target. F with nothing to act on in view opens the self menu (health, gear, map, PDA, off
 * hand, getting off the bike). Alt and Ctrl with the wheel stay lean and stance (LeanClient).
 */
@Mod.EventBusSubscriber(modid = ZoneRaids.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class InteractionMenu implements IGuiOverlay {
    private static final int CLOSE_TICKS = 100;
    /** How long a party invite from chat is offered as "accept", if the server's (60 s by default) is longer. */
    private static final long INVITE_MILLIS = 120_000L;
    private static final int ROW_H = 11;

    /** What the crosshair is on: a block (pos, face) or an entity (id). */
    private record Target(@Nullable BlockPos pos, Direction face, int entityId) {
    }

    private record Entry(Component label, Runnable run) {
    }

    @Nullable
    private static Target target;
    private static Component title = Component.empty();
    private static List<Entry> entries = List.of();
    private static boolean open;
    /** The open list is the self menu (F with nothing to act on in view). */
    private static boolean self;
    private static int selected;
    private static int idle;
    private static String inviterName = "";
    private static long invitedAt;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null || !player.isAlive() || player.isSpectator()) {
            reset();
            while (ClientSetup.INTERACT.consumeClick()) {
                // Dropped: a press that opened nothing must not fire once the screen closes.
            }
            return;
        }
        Target now = pick(mc);
        if (!Objects.equals(now, target)) {
            target = now;
            if (!self) {
                open = false;
                selected = 0;
            }
        }
        refresh(mc, player);
        if (open && ++idle > CLOSE_TICKS) {
            open = false;
            self = false;
        }
        while (ClientSetup.INTERACT.consumeClick()) {
            press(mc, player);
        }
    }

    @Nullable
    private static Target pick(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult e) {
            return new Target(null, Direction.UP, e.getEntity().getId());
        } else if (hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            return new Target(b.getBlockPos(), b.getDirection(), -1);
        }
        return null;
    }

    /** Lists the target's actions again every tick: a door's "open" turns to "close" as soon as it opens. */
    private static void refresh(Minecraft mc, LocalPlayer player) {
        List<Entry> list = new ArrayList<>();
        if (target != null && target.pos() != null) {
            for (Interactions.Option o : Interactions.forBlock(mc.level, target.pos())) {
                InteractPacket packet = new InteractPacket(o.action(), -1, target.pos(), target.face());
                list.add(new Entry(o.label(), () -> RaidNetwork.sendToServer(packet)));
            }
            title = mc.level.getBlockState(target.pos()).getBlock().getName();
        } else if (target != null) {
            Entity entity = mc.level.getEntity(target.entityId());
            if (entity != null) {
                String name = entity.getName().getString();
                for (Interactions.Option o : Interactions.forEntity(player, entity, invitedBy(name), partyMate(name))) {
                    InteractPacket packet = new InteractPacket(o.action(), entity.getId(), null, Direction.UP);
                    list.add(new Entry(o.label(), () -> RaidNetwork.sendToServer(packet)));
                }
                title = entity.getDisplayName();
            }
        }
        if (!list.isEmpty() && self) {
            // Something to act on came into view: its actions replace the self menu.
            self = false;
            open = false;
            selected = 0;
        } else if (list.isEmpty() && self) {
            list = selfEntries(mc, player);
            title = Component.translatable("zoneraids.interact.self");
        }
        entries = list;
        if (entries.isEmpty()) {
            open = false;
            self = false;
        }
        selected = entries.isEmpty() ? 0 : Mth.clamp(selected, 0, entries.size() - 1);
    }

    private static List<Entry> selfEntries(Minecraft mc, LocalPlayer player) {
        List<Entry> list = new ArrayList<>();
        if (player.isPassenger()) {
            list.add(new Entry(Component.translatable("zoneraids.interact.dismount"),
                    () -> RaidNetwork.sendToServer(InteractPacket.self(Action.DISMOUNT))));
        }
        list.add(new Entry(Component.translatable("zoneraids.interact.self_health"), () -> PdaTabs.open(PdaTabs.Tab.HEALTH, null)));
        list.add(new Entry(Component.translatable("zoneraids.interact.self_gear"), PdaTabs::openCurios));
        list.add(new Entry(Component.translatable("zoneraids.interact.self_map"), () -> PdaTabs.open(PdaTabs.Tab.MAP, null)));
        list.add(new Entry(Component.translatable("zoneraids.interact.self_pda"), () -> PdaTabs.open(PdaTabs.Tab.RAIDS, null)));
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            list.add(new Entry(Component.translatable("zoneraids.interact.self_offhand"), () -> {
                if (mc.getConnection() != null) {
                    // What the vanilla swap key sends; that key is unbound in KeyLayout to free F.
                    mc.getConnection().send(new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                }
            }));
        }
        return list;
    }

    private static void press(Minecraft mc, LocalPlayer player) {
        if (entries.isEmpty()) {
            open = true;
            self = true;
            idle = 0;
            selected = 0;
            refresh(mc, player);
            return;
        }
        if (!open && entries.size() > 1 && !ClientSettings.scrollMenu()) {
            // Without the wheel, the first press opens the list and the wheel (or the next press) picks.
            open = true;
            idle = 0;
            selected = 0;
            return;
        }
        Entry entry = entries.get(open ? selected : 0);
        open = false;
        self = false;
        selected = 0;
        entry.run().run();
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || event.getScrollDelta() == 0 || entries.isEmpty()
                || (!open && !ClientSettings.scrollMenu())
                || LeanClient.held(ClientSetup.LEAN_ADJUST) || LeanClient.held(ClientSetup.STANCE_ADJUST)) {
            return;
        }
        event.setCanceled(true);
        idle = 0;
        if (!open) {
            // The first turn of the wheel only opens the list, on its first line, as in Arma.
            open = true;
            selected = 0;
        } else {
            selected = Math.floorMod(selected + (event.getScrollDelta() < 0 ? 1 : -1), entries.size());
        }
    }

    /** Party invites arrive as chat lines; the inviter is then offered "accept" instead of "invite". */
    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        if (event.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("zoneraids.party.invite_received") && t.getArgs().length > 0) {
            Object who = t.getArgs()[0];
            inviterName = who instanceof Component c ? c.getString() : String.valueOf(who);
            invitedAt = System.currentTimeMillis();
        } else if (event.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("zoneraids.party.joined")) {
            inviterName = "";
        }
    }

    private static boolean invitedBy(String name) {
        return !inviterName.isEmpty() && System.currentTimeMillis() - invitedAt < INVITE_MILLIS
                && inviterName.equals(name);
    }

    /** From the last terminal data (opened PDA); the server checks for real. */
    private static boolean partyMate(String name) {
        TerminalDataPacket data = ClientRaidState.terminal;
        return data != null && (data.party().contains(name) || data.party().contains("★ " + name));
    }

    private static void reset() {
        target = null;
        entries = List.of();
        open = false;
        self = false;
        selected = 0;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        inviterName = "";
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || entries.isEmpty()) {
            return;
        }
        Font font = mc.font;
        int cx = width / 2;
        int cy = height / 2;
        Component key = Component.literal("[").append(ClientSetup.INTERACT.getTranslatedKeyMessage()).append("] ");
        if (!open) {
            // Arma's default action in the middle of the screen, just under the crosshair.
            Component label = entries.get(0).label();
            int w = font.width(key) + font.width(label);
            int x = cx - w / 2;
            int y = cy + 12;
            g.fill(x - 4, y - 2, x + w + 4, y + 10, ZoneUi.BG_HUD);
            g.drawString(font, key, x, y, ZoneUi.AMBER);
            g.drawString(font, label, x + font.width(key), y, ZoneUi.TEXT);
            if (entries.size() > 1) {
                Component more = Component.translatable("zoneraids.interact.more", entries.size() - 1);
                g.drawString(font, more, cx - font.width(more) / 2, y + 12, ZoneUi.DIM);
            }
            return;
        }
        // The open list, to the right of the crosshair like Arma's.
        Component hint = Component.translatable("zoneraids.interact.hint", ClientSetup.INTERACT.getTranslatedKeyMessage());
        int w = Math.max(font.width(title), font.width(hint));
        for (Entry entry : entries) {
            w = Math.max(w, font.width(entry.label()) + 10);
        }
        w += 12;
        int h = 16 + entries.size() * ROW_H + 13;
        int x0 = cx + 14;
        int y0 = Mth.clamp(cy - 16 - selected * ROW_H - ROW_H / 2, 4, Math.max(4, height - h - 4));
        ZoneUi.panel(g, x0, y0, x0 + w, y0 + h, ZoneUi.BG_HUD);
        g.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + 14, ZoneUi.HEADER);
        g.drawString(font, ZoneUi.clip(font, title.getString(), w - 12), x0 + 6, y0 + 4, ZoneUi.AMBER);
        for (int i = 0; i < entries.size(); i++) {
            int y = y0 + 16 + i * ROW_H;
            boolean sel = i == selected;
            if (sel) {
                g.fill(x0 + 2, y - 1, x0 + w - 2, y + ROW_H - 1, ZoneUi.ROW_HOVER);
                g.drawString(font, "›", x0 + 5, y + 1, ZoneUi.AMBER);
            }
            g.drawString(font, entries.get(i).label(), x0 + 13, y + 1, sel ? ZoneUi.AMBER : ZoneUi.TEXT);
        }
        g.drawString(font, hint, x0 + 6, y0 + h - 11, ZoneUi.DIM);
    }
}
