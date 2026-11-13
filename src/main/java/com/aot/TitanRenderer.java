package com.aot;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/** Generic humanoid giant. Entity scale (Attributes.SCALE) is applied automatically by the renderer. */
public class TitanRenderer extends HumanoidMobRenderer<TitanEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
    private static final Identifier TEXTURE = AotRegistry.id("textures/entity/titan.png");

    public TitanRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
    }

    @Override
    public HumanoidRenderState createRenderState() { return new HumanoidRenderState(); }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) { return TEXTURE; }
}
