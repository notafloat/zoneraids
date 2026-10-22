package com.zoneraids.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/** Weighted set of block states, used to give walls and ground a worn, uneven look. */
final class Palette {
    private final BlockState[] states;
    private final double[] cumulative;

    private Palette(BlockState[] states, double[] cumulative) {
        this.states = states;
        this.cumulative = cumulative;
    }

    /** Pairs of (Block or BlockState, weight). */
    static Palette of(Object... entries) {
        int n = entries.length / 2;
        BlockState[] states = new BlockState[n];
        double[] cumulative = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            Object o = entries[i * 2];
            states[i] = o instanceof Block block ? block.defaultBlockState() : (BlockState) o;
            total += ((Number) entries[i * 2 + 1]).doubleValue();
            cumulative[i] = total;
        }
        for (int i = 0; i < n; i++) {
            cumulative[i] /= total;
        }
        return new Palette(states, cumulative);
    }

    BlockState pick(Random random) {
        return pick(random.nextDouble());
    }

    /** @param roll value in [0, 1) */
    BlockState pick(double roll) {
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return states[i];
            }
        }
        return states[states.length - 1];
    }
}
