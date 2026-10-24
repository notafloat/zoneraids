package com.zoneraids.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zoneraids.ZoneRaids;
import com.zoneraids.entity.Pseudogiant;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Pseudogiant: a hunched mound of flesh on two stumpy legs, a small head sunk into the front of the
 * belly and two withered arms of different length. Rears up and gapes before a stamp.
 * The boxes match modpack/tools/boss_art.py, which paints the texture.
 */
public class PseudogiantModel extends EntityModel<Pseudogiant> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(ZoneRaids.MODID, "pseudogiant"), "main");

    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart jaw;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    public PseudogiantModel(ModelPart root) {
        body = root.getChild("body");
        head = body.getChild("head");
        jaw = head.getChild("jaw");
        leftArm = body.getChild("arm_left");
        rightArm = body.getChild("arm_right");
        leftLeg = root.getChild("leg_left");
        rightLeg = root.getChild("leg_right");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-13.0F, -9.0F, -11.0F, 26, 18, 22)   // belly
                        .texOffs(0, 40).addBox(-10.0F, -15.0F, -7.0F, 20, 6, 16),  // hump
                PartPose.offset(0.0F, 6.0F, 0.0F));
        PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(72, 40).addBox(-5.0F, -4.0F, -7.0F, 10, 8, 7),
                PartPose.offsetAndRotation(0.0F, 1.0F, -11.0F, 0.15F, 0.0F, 0.0F));
        head.addOrReplaceChild("jaw", CubeListBuilder.create()
                        .texOffs(72, 62).addBox(-4.5F, 0.0F, -6.0F, 9, 3, 6),
                PartPose.offsetAndRotation(0.0F, 2.0F, -1.0F, 0.1F, 0.0F, 0.0F));
        body.addOrReplaceChild("arm_left", CubeListBuilder.create()
                        .texOffs(40, 62).addBox(-3.0F, -1.0F, -3.0F, 5, 11, 5),
                PartPose.offsetAndRotation(-12.0F, -2.0F, -7.0F, -0.3F, 0.0F, 0.2F));
        body.addOrReplaceChild("arm_right", CubeListBuilder.create()
                        .texOffs(64, 72).addBox(-2.0F, -1.0F, -2.0F, 4, 7, 4),
                PartPose.offsetAndRotation(12.0F, -1.0F, -6.0F, -0.2F, 0.0F, -0.25F));
        root.addOrReplaceChild("leg_left", leg(false), PartPose.offset(-7.0F, 14.0F, 2.0F));
        root.addOrReplaceChild("leg_right", leg(true), PartPose.offset(7.0F, 14.0F, 2.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder leg(boolean mirror) {
        return CubeListBuilder.create().mirror(mirror)
                .texOffs(0, 62).addBox(-4.5F, 0.0F, -4.5F, 9, 10, 9)
                .texOffs(0, 81).addBox(-4.5F, 8.0F, -6.5F, 9, 2, 2);  // toes
    }

    @Override
    public void setupAnim(Pseudogiant entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        float walk = Mth.cos(limbSwing * 0.5F);
        leftLeg.xRot = walk * 0.9F * limbSwingAmount;
        rightLeg.xRot = -walk * 0.9F * limbSwingAmount;
        float breath = Mth.sin(ageInTicks * 0.08F);
        // A heavy waddle: the body rolls from leg to leg and bobs with each step.
        body.zRot = walk * 0.08F * limbSwingAmount;
        body.y = 6.0F + Math.abs(walk) * limbSwingAmount + breath * 0.3F;
        body.xRot = 0.0F;
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD * 0.5F;
        head.xRot = 0.15F + headPitch * Mth.DEG_TO_RAD * 0.3F;
        jaw.xRot = 0.1F + Math.max(0.0F, breath) * 0.1F;
        leftArm.xRot = -0.3F - walk * 0.4F * limbSwingAmount;
        rightArm.xRot = -0.2F + walk * 0.5F * limbSwingAmount + Mth.sin(ageInTicks * 0.3F) * 0.1F;
        leftArm.zRot = 0.2F;
        rightArm.zRot = -0.25F;
        if (entity.getAttackAnimationRemainingTicks() > 0) {
            float swing = entity.getAttackAnimationRemainingTicks() / 10.0F;
            head.xRot -= swing * 0.6F;
            jaw.xRot += swing * 0.6F;
            leftArm.xRot -= swing * 1.2F;
        }
        if (entity.isWindingUp()) {
            // Rears back with the arms up and the mouth wide open, then the stamp comes down.
            float shake = Mth.sin(ageInTicks * 2.5F) * 0.03F;
            body.xRot = -0.3F + shake;
            body.y -= 2.0F;
            jaw.xRot = 0.8F;
            head.xRot = -0.3F;
            leftArm.xRot = -2.2F;
            rightArm.xRot = -2.0F;
            leftArm.zRot = 0.5F;
            rightArm.zRot = -0.5F;
        }
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, float r, float g, float b, float a) {
        body.render(pose, buffer, light, overlay, r, g, b, a);
        leftLeg.render(pose, buffer, light, overlay, r, g, b, a);
        rightLeg.render(pose, buffer, light, overlay, r, g, b, a);
    }
}
