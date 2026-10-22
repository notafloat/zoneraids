package com.zoneraids.world;

import com.mojang.logging.LogUtils;
import com.zoneraids.RaidConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs map builds a few milliseconds per server tick so a fresh server stays responsive while the
 * hub and maps are generated. Each job is a list of small steps (one chunk of terrain, one
 * building...). Nothing is kept after a job finishes, and everything is dropped on server stop.
 */
public final class WorldBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** One build: a dimension, its steps and what to do once all steps ran. */
    public record Job(String name, ResourceKey<Level> dimension, List<Consumer<Painter>> steps, Consumer<MinecraftServer> onDone) {
    }

    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    @Nullable
    private static Job current;
    @Nullable
    private static Deque<Consumer<Painter>> steps;
    @Nullable
    private static Painter painter;
    private static int total;
    private static int lastReported;

    private WorldBuilder() {
    }

    public static void enqueue(Job job) {
        QUEUE.add(job);
    }

    public static boolean isBusy() {
        return current != null || !QUEUE.isEmpty();
    }

    public static boolean isBuilding(String name) {
        return (current != null && current.name().equals(name)) || QUEUE.stream().anyMatch(j -> j.name().equals(name));
    }

    @Nullable
    public static String status() {
        if (current == null || steps == null) {
            return null;
        }
        return current.name() + " " + progress() + "%";
    }

    private static int progress() {
        return total == 0 || steps == null ? 100 : (int) ((total - steps.size()) * 100L / total);
    }

    public static void clear() {
        QUEUE.clear();
        current = null;
        steps = null;
        painter = null;
    }

    public static void tick(@Nullable MinecraftServer server) {
        if (server == null || (current == null && QUEUE.isEmpty())) {
            return;
        }
        if (current == null && !begin(server)) {
            return;
        }
        long deadline = System.nanoTime() + RaidConfig.BUILD_MILLIS_PER_TICK.get() * 1_000_000L;
        while (steps != null && !steps.isEmpty() && System.nanoTime() < deadline) {
            Consumer<Painter> step = steps.poll();
            try {
                step.accept(painter);
            } catch (RuntimeException e) {
                LOGGER.error("Zone Raids: build step of {} failed", current.name(), e);
            }
        }
        int progress = progress();
        if (progress >= lastReported + 10 && steps != null && !steps.isEmpty()) {
            lastReported = progress - progress % 10;
            notifyOps(server, Component.translatable("zoneraids.build.progress", current.name(), lastReported));
        }
        if (steps != null && steps.isEmpty()) {
            Job done = current;
            current = null;
            steps = null;
            painter = null;
            LOGGER.info("Zone Raids: finished building {}", done.name());
            done.onDone().accept(server);
            notifyOps(server, Component.translatable("zoneraids.build.done", done.name()).withStyle(ChatFormatting.GREEN));
        }
    }

    private static boolean begin(MinecraftServer server) {
        while (!QUEUE.isEmpty()) {
            Job job = QUEUE.poll();
            ServerLevel level = server.getLevel(job.dimension());
            if (level == null) {
                LOGGER.error("Zone Raids: cannot build {}, dimension {} is missing", job.name(), job.dimension().location());
                continue;
            }
            current = job;
            steps = new ArrayDeque<>(job.steps());
            painter = new Painter(level);
            total = steps.size();
            lastReported = 0;
            LOGGER.info("Zone Raids: building {} in {} ({} steps)", job.name(), job.dimension().location(), total);
            notifyOps(server, Component.translatable("zoneraids.build.started", job.name()).withStyle(ChatFormatting.YELLOW));
            return true;
        }
        return false;
    }

    private static void notifyOps(MinecraftServer server, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(message);
            }
        }
    }
}
