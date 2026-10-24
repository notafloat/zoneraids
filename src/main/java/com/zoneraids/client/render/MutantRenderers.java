package com.zoneraids.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zoneraids.entity.BlindDog;
import com.zoneraids.entity.Flesh;
import com.zoneraids.entity.Pseudogiant;
import net.minecraft.client.model.HoglinModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.WolfRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Monster;

/** Mutant renderers reuse vanilla models and textures, so the mod ships no entity art. */
public final class MutantRenderers {
    private static final ResourceLocation DOG = new ResourceLocation("textures/entity/wolf/wolf_angry.png");
    private static final ResourceLocation FLESH = new ResourceLocation("textures/entity/hoglin/zoglin.png");
    private static final ResourceLocation SNORK = new ResourceLocation("textures/entity/zombie/husk.png");
    private static final ResourceLocation BLOODSUCKER = new ResourceLocation("textures/entity/zombie/drowned.png");
    private static final ResourceLocation CONTROLLER = new ResourceLocation("textures/entity/zombie/zombie.png");

    private MutantRenderers() {
    }

    public static class BlindDogRenderer extends WolfRenderer {
        public BlindDogRenderer(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(Wolf entity) {
            return DOG;
        }

        @Override
        protected void scale(Wolf entity, PoseStack pose, float partialTick) {
            if (entity instanceof BlindDog) {
                pose.scale(1.1F, 1.0F, 1.15F);
            }
        }
    }

    public static class FleshRenderer<T extends Flesh> extends MobRenderer<T, HoglinModel<T>> {
        private final float size;

        FleshRenderer(EntityRendererProvider.Context ctx, float size) {
            super(ctx, new HoglinModel<>(ctx.bakeLayer(ModelLayers.ZOGLIN)), 0.8F * size);
            this.size = size;
        }

        public FleshRenderer(EntityRendererProvider.Context ctx) {
            this(ctx, 0.75F);
        }

        @Override
        public ResourceLocation getTextureLocation(T entity) {
            return FLESH;
        }

        @Override
        protected void scale(T entity, PoseStack pose, float partialTick) {
            pose.scale(size, size, size);
        }
    }

    /** The pseudogiant is a flesh grown to twice a man's height. */
    public static FleshRenderer<Pseudogiant> pseudogiant(EntityRendererProvider.Context ctx) {
        return new FleshRenderer<>(ctx, 1.6F);
    }

    public static <T extends Monster> HumanoidMutantRenderer<T> controller(EntityRendererProvider.Context ctx) {
        return new HumanoidMutantRenderer<>(ctx, CONTROLLER, 1.05F);
    }

    public static class HumanoidMutantRenderer<T extends Monster> extends HumanoidMobRenderer<T, MutantHumanoidModel<T>> {
        private final ResourceLocation texture;
        private final float size;

        HumanoidMutantRenderer(EntityRendererProvider.Context ctx, ResourceLocation texture, float size) {
            super(ctx, new MutantHumanoidModel<>(ctx.bakeLayer(ModelLayers.ZOMBIE)), 0.5F * size);
            this.texture = texture;
            this.size = size;
        }

        @Override
        public ResourceLocation getTextureLocation(T entity) {
            return texture;
        }

        @Override
        protected void scale(T entity, PoseStack pose, float partialTick) {
            pose.scale(size, size, size);
        }
    }

    public static <T extends Monster> HumanoidMutantRenderer<T> snork(EntityRendererProvider.Context ctx) {
        return new HumanoidMutantRenderer<>(ctx, SNORK, 0.95F);
    }

    public static <T extends Monster> HumanoidMutantRenderer<T> bloodsucker(EntityRendererProvider.Context ctx) {
        return new HumanoidMutantRenderer<>(ctx, BLOODSUCKER, 1.08F);
    }
}
