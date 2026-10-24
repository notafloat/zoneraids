package com.zoneraids.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zoneraids.ZoneRaids;
import com.zoneraids.entity.BlindDog;
import com.zoneraids.entity.Controller;
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

/**
 * Mutant renderers. The common mutants reuse vanilla models and textures; the lair bosses have models
 * and textures of their own (PseudogiantModel, ControllerModel, painted by modpack/tools/boss_art.py).
 */
public final class MutantRenderers {
    private static final ResourceLocation DOG = new ResourceLocation("textures/entity/wolf/wolf_angry.png");
    private static final ResourceLocation FLESH = new ResourceLocation("textures/entity/hoglin/zoglin.png");
    private static final ResourceLocation SNORK = new ResourceLocation("textures/entity/zombie/husk.png");
    private static final ResourceLocation BLOODSUCKER = new ResourceLocation("textures/entity/zombie/drowned.png");
    private static final ResourceLocation PSEUDOGIANT = new ResourceLocation(ZoneRaids.MODID, "textures/entity/pseudogiant.png");
    private static final ResourceLocation CONTROLLER = new ResourceLocation(ZoneRaids.MODID, "textures/entity/controller.png");

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

    public static class PseudogiantRenderer extends MobRenderer<Pseudogiant, PseudogiantModel> {
        public PseudogiantRenderer(EntityRendererProvider.Context ctx) {
            super(ctx, new PseudogiantModel(ctx.bakeLayer(PseudogiantModel.LAYER)), 1.3F);
        }

        @Override
        public ResourceLocation getTextureLocation(Pseudogiant entity) {
            return PSEUDOGIANT;
        }

        @Override
        protected void scale(Pseudogiant entity, PoseStack pose, float partialTick) {
            pose.scale(1.1F, 1.1F, 1.1F);
        }
    }

    public static class ControllerRenderer extends MobRenderer<Controller, ControllerModel> {
        public ControllerRenderer(EntityRendererProvider.Context ctx) {
            super(ctx, new ControllerModel(ctx.bakeLayer(ControllerModel.LAYER)), 0.5F);
        }

        @Override
        public ResourceLocation getTextureLocation(Controller entity) {
            return CONTROLLER;
        }
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
