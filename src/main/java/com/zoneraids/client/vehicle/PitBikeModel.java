package com.zoneraids.client.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zoneraids.ZoneRaids;
import com.zoneraids.vehicle.PitBike;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/** Box model of a small dirt bike, front towards -Z, wheels touching y = 24 (the ground). */
public class PitBikeModel extends EntityModel<PitBike> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(ZoneRaids.MODID, "pit_bike"), "main");

    private final ModelPart root;
    private final ModelPart frontWheel;
    private final ModelPart rearWheel;

    public PitBikeModel(ModelPart root) {
        this.root = root;
        this.frontWheel = root.getChild("fork").getChild("front_wheel");
        this.rearWheel = root.getChild("rear_wheel");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition parts = mesh.getRoot();

        // A wheel is two crossed boxes, which reads as round enough at this size.
        CubeListBuilder wheel = CubeListBuilder.create()
                .texOffs(0, 0).addBox(-1.0F, -3.0F, -5.0F, 2, 6, 10)
                .texOffs(0, 16).addBox(-1.0F, -5.0F, -3.0F, 2, 10, 6);
        parts.addOrReplaceChild("rear_wheel", wheel, PartPose.offset(0.0F, 19.0F, 10.0F));

        // Fork, handlebar, headlight and front fender turn with the raked steering column.
        PartDefinition fork = parts.addOrReplaceChild("fork", CubeListBuilder.create()
                        .texOffs(48, 0).addBox(-2.0F, -10.0F, -0.5F, 1, 10, 1)
                        .texOffs(48, 0).addBox(1.0F, -10.0F, -0.5F, 1, 10, 1)
                        .texOffs(0, 48).addBox(-5.0F, -11.0F, -0.5F, 10, 1, 1)
                        .texOffs(24, 48).addBox(-1.5F, -9.5F, -1.5F, 3, 2, 1)
                        .texOffs(28, 40).addBox(-1.5F, -6.0F, -3.0F, 3, 1, 6),
                PartPose.offsetAndRotation(0.0F, 19.0F, -10.0F, -0.35F, 0.0F, 0.0F));
        fork.addOrReplaceChild("front_wheel", wheel, PartPose.rotation(0.35F, 0.0F, 0.0F));

        parts.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(24, 0).addBox(-2.5F, 13.0F, -4.0F, 5, 5, 7)     // engine
                        .texOffs(24, 12).addBox(-2.5F, 10.0F, -6.0F, 5, 3, 6)    // tank
                        .texOffs(0, 32).addBox(-2.0F, 9.0F, -1.0F, 4, 2, 10)     // seat
                        .texOffs(28, 32).addBox(-1.5F, 12.0F, 7.0F, 3, 1, 7)     // rear fender
                        .texOffs(0, 52).addBox(-2.0F, 18.0F, 2.0F, 1, 1, 10)     // swingarm
                        .texOffs(0, 52).addBox(1.0F, 18.0F, 2.0F, 1, 1, 10)
                        .texOffs(26, 52).addBox(2.5F, 14.0F, 0.0F, 1, 1, 8),     // exhaust
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(PitBike bike, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    public void setWheelRotation(float radians) {
        rearWheel.xRot = radians;
        frontWheel.xRot = 0.35F + radians;
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, float r, float g, float b, float a) {
        root.render(pose, buffer, light, overlay, r, g, b, a);
    }
}
