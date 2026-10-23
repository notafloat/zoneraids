package com.zoneraids.client.render;

import net.minecraft.client.model.AbstractZombieModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.monster.Monster;

/** Zombie-style humanoid: arms raised while it hunts. */
public class MutantHumanoidModel<T extends Monster> extends AbstractZombieModel<T> {
    public MutantHumanoidModel(ModelPart root) {
        super(root);
    }

    @Override
    public boolean isAggressive(T entity) {
        return entity.isAggressive();
    }
}
