package com.zoneraids.client.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zoneraids.ZoneRaids;
import com.zoneraids.vehicle.PitBike;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public class PitBikeRenderer extends EntityRenderer<PitBike> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(ZoneRaids.MODID, "textures/entity/pit_bike.png");

    private final PitBikeModel model;

    public PitBikeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new PitBikeModel(context.bakeLayer(PitBikeModel.LAYER));
        this.shadowRadius = 0.5F;
    }

    @Override
    public void render(PitBike bike, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0F, Mth.lerp(partialTicks, bike.stepOffsetO, bike.stepOffset), 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        float lean = Mth.lerp(partialTicks, bike.leanO, bike.lean);
        float hurt = bike.hurtTicks() - partialTicks;
        if (hurt > 0.0F) {
            lean += Mth.sin(hurt * 1.5F) * hurt * 0.6F;
        }
        pose.mulPose(Axis.ZP.rotationDegrees(lean));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0.0F, -1.501F, 0.0F);
        model.setWheelRotation(Mth.lerp(partialTicks, bike.wheelRotO, bike.wheelRot));
        model.renderToBuffer(pose, buffers.getBuffer(model.renderType(TEXTURE)), light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        pose.popPose();
        super.render(bike, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(PitBike bike) {
        return TEXTURE;
    }
}
