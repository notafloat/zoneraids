package com.zoneraids.world.gen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** A bare dead tree with a few crooked branches, or a fallen mossy trunk. Grows on dirt and grass only. */
public class DeadTreeFeature extends Feature<NoneFeatureConfiguration> {
    private static final Block[] LOGS = {Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LOG, Blocks.SPRUCE_LOG, Blocks.OAK_LOG, Blocks.BIRCH_LOG};

    public DeadTreeFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        if (!Prop.firmGround(level, origin) || !level.getBlockState(origin.below()).is(BlockTags.DIRT)) {
            return false;
        }
        Prop p = new Prop(level, origin, context.random());
        Block log = p.pick(LOGS);
        return p.chance(0.3F) ? fallen(p, log) : standing(p, log);
    }

    private static boolean fallen(Prop p, Block log) {
        int length = p.between(4, 7);
        int placed = 0;
        for (int a = 0; a < length; a++) {
            int g = p.ground(a, 0);
            if (Math.abs(g) > 1 || !p.free(a, g, 0)) {
                break;
            }
            p.put(a, g, 0, p.log(log, true));
            if (p.chance(0.35F)) {
                p.put(a, g + 1, 0, Blocks.MOSS_CARPET);
            } else if (p.chance(0.1F)) {
                p.put(a, g + 1, 0, Blocks.BROWN_MUSHROOM);
            }
            placed++;
        }
        return placed > 0;
    }

    private static boolean standing(Prop p, Block log) {
        int height = p.between(4, 8);
        int trunk = 0;
        while (trunk < height && p.free(0, trunk, 0)) {
            p.put(0, trunk, 0, log.defaultBlockState());
            trunk++;
        }
        if (trunk < 3) {
            return trunk > 0;
        }
        int branches = p.between(1, 3);
        for (int i = 0; i < branches; i++) {
            Direction dir = Direction.from2DDataValue(p.random.nextInt(4));
            int y = p.between(trunk / 2, trunk - 1);
            int length = p.between(1, 3);
            BlockPos pos = p.origin.above(y);
            for (int d = 1; d <= length; d++) {
                pos = pos.relative(dir);
                if (d > 1 && p.chance(0.4F)) {
                    pos = pos.above();
                }
                BlockState old = p.level.getBlockState(pos);
                if (!old.isAir() && !old.canBeReplaced()) {
                    break;
                }
                BlockState branch = log.defaultBlockState().setValue(BlockStateProperties.AXIS, dir.getAxis());
                p.level.setBlock(pos, branch, Block.UPDATE_CLIENTS);
            }
        }
        return true;
    }
}
