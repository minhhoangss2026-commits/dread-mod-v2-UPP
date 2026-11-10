package vn.dread.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.AbstractZombieModel;
import vn.dread.FlayedEntity;

public class FlayedModel extends AbstractZombieModel<FlayedEntity> {
    public FlayedModel(ModelPart root) { super(root); }
    @Override public boolean isAttacking(FlayedEntity e) { return e.isAttacking(); }
}
