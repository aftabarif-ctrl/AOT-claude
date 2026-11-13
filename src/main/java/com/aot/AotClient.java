package com.aot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

public class AotClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(AotRegistry.TITAN, TitanRenderer::new);
        EntityRendererRegistry.register(AotRegistry.BOSS, TitanRenderer::new);
        EntityRendererRegistry.register(AotRegistry.SPEAR, ThrownItemRenderer::new);
    }
}
