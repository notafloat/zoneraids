package com.zoneraids.world.gen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** A ragged patch of trampled, dried-out grass: coarse dirt, podzol and rooted dirt, gravel where nothing grows on top. */
public class GroundWearFeature extends Feature<NoneFeatureConfiguration> {
    public GroundWearFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();
        int radius = 2 + random.nextInt(3);
        boolean placed = false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > (radius + random.nextFloat() - 0.5F) * (radius + random.nextFloat() - 0.5F)) {
                    continue;
                }
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockPos top = new BlockPos(x, Prop.surface(level, x, z) - 1, z);
                if (Math.abs(top.getY() + 1 - origin.getY()) > 2 || !level.getBlockState(top).is(Blocks.GRASS_BLOCK)) {
                    continue;
                }
                float f = random.nextFloat();
                Block block = f < 0.5F ? Blocks.COARSE_DIRT : f < 0.75F ? Blocks.PODZOL : f < 0.9F ? Blocks.ROOTED_DIRT : Blocks.GRAVEL;
                if (block == Blocks.GRAVEL && !level.getBlockState(top.above()).isAir()) {
                    // Gravel would leave the grass or flower on top floating.
                    block = Blocks.COARSE_DIRT;
                }
                level.setBlock(top, block.defaultBlockState(), Block.UPDATE_CLIENTS);
                placed = true;
            }
        }
        return placed;
    }
}
