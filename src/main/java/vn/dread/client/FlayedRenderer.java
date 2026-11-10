package vn.dread.client;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import vn.dread.FlayedEntity;

public class FlayedRenderer extends BipedEntityRenderer<FlayedEntity, FlayedModel> {
    private static final Identifier TEX = new Identifier("dread", "textures/entity/flayed.png");

    public FlayedRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new FlayedModel(ctx.getPart(EntityModelLayers.ZOMBIE)), 0.6f);
    }

    @Override public Identifier getTexture(FlayedEntity e) { return TEX; }

    @Override
    protected void scale(FlayedEntity e, MatrixStack m, float tickDelta) {
        float s = e.getMaxHealth() > 100 ? 1.9f : 1.25f; // trùm to hơn quái thường
        m.scale(s, s, s);
    }
}
