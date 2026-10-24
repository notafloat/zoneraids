package com.zoneraids.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zoneraids.ZoneRaids;
import com.zoneraids.entity.Controller;
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
 * Controller: a stooped man in rags with long dangling arms and a swollen bald skull, the brain
 * bulging through the crown. Raises its hands to the temples while gathering a psi wave.
 * The boxes match modpack/tools/boss_art.py, which paints the texture.
 */
public class ControllerModel extends EntityModel<Controller> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(ZoneRaids.MODID, "controller"), "main");

    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public ControllerModel(ModelPart root) {
        body = root.getChild("body");
        head = root.getChild("head");
        rightArm = root.getChild("arm_right");
        leftArm = root.getChild("arm_left");
        rightLeg = root.getChild("leg_right");
        leftLeg = root.getChild("leg_left");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 19).addBox(-4.0F, 0.0F, -2.0F, 8, 11, 4)
                        .texOffs(0, 37).addBox(-4.5F, 7.0F, -2.5F, 9, 5, 5),  // torn shirt tail
                PartPose.offsetAndRotation(0.0F, 2.0F, 1.0F, 0.35F, 0.0F, 0.0F));
        root.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-5.0F, -10.0F, -5.5F, 10, 10, 9)
                        .texOffs(38, 0).addBox(-3.5F, -12.5F, -3.5F, 7, 3, 6),  // brain
                PartPose.offsetAndRotation(0.0F, 2.5F, 0.0F, 0.2F, 0.0F, 0.0F));
        root.addOrReplaceChild("arm_right", arm(false), PartPose.offsetAndRotation(-5.5F, 4.0F, -1.0F, -0.4F, 0.0F, 0.1F));
        root.addOrReplaceChild("arm_left", arm(true), PartPose.offsetAndRotation(5.5F, 4.0F, -1.0F, -0.4F, 0.0F, -0.1F));
        root.addOrReplaceChild("leg_right", leg(false), PartPose.offset(-2.0F, 12.0F, 3.0F));
        root.addOrReplaceChild("leg_left", leg(true), PartPose.offset(2.0F, 12.0F, 3.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    private static CubeListBuilder arm(boolean mirror) {
        return CubeListBuilder.create().mirror(mirror).texOffs(24, 19).addBox(-1.5F, -1.0F, -1.5F, 3, 15, 3);
    }

    private static CubeListBuilder leg(boolean mirror) {
        return CubeListBuilder.create().mirror(mirror).texOffs(36, 19).addBox(-2.0F, 0.0F, -2.0F, 4, 12, 4);
    }

    @Override
    public void setupAnim(Controller entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        float walk = Mth.cos(limbSwing * 0.6662F);
        rightLeg.xRot = walk * 1.0F * limbSwingAmount;
        leftLeg.xRot = -walk * 1.0F * limbSwingAmount;
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
        head.xRot = 0.2F + headPitch * Mth.DEG_TO_RAD;
        head.zRot = Mth.sin(ageInTicks * 0.05F) * 0.06F;
        // Arms hang and sway loosely, barely following the stride.
        float sway = Mth.sin(ageInTicks * 0.067F) * 0.05F;
        rightArm.xRot = -0.4F - walk * 0.3F * limbSwingAmount + sway;
        leftArm.xRot = -0.4F + walk * 0.3F * limbSwingAmount - sway;
        rightArm.yRot = 0.0F;
        leftArm.yRot = 0.0F;
        rightArm.zRot = 0.1F + sway;
        leftArm.zRot = -0.1F - sway;
        if (attackTime > 0.0F) {
            float swing = Mth.sin(attackTime * Mth.PI);
            rightArm.xRot -= swing * 1.4F;
            leftArm.xRot -= swing * 1.2F;
        }
        if (entity.isCharging()) {
            // Hands to the temples, the head shaking and tipped back as the wave builds.
            rightArm.xRot = -2.7F;
            leftArm.xRot = -2.7F;
            rightArm.yRot = -0.5F;
            leftArm.yRot = 0.5F;
            rightArm.zRot = -0.2F;
            leftArm.zRot = 0.2F;
            head.xRot = -0.15F;
            head.zRot = Mth.sin(ageInTicks * 3.0F) * 0.08F;
        }
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, float r, float g, float b, float a) {
        body.render(pose, buffer, light, overlay, r, g, b, a);
        head.render(pose, buffer, light, overlay, r, g, b, a);
        rightArm.render(pose, buffer, light, overlay, r, g, b, a);
        leftArm.render(pose, buffer, light, overlay, r, g, b, a);
        rightLeg.render(pose, buffer, light, overlay, r, g, b, a);
        leftLeg.render(pose, buffer, light, overlay, r, g, b, a);
    }
}
