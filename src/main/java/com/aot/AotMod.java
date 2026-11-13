package com.aot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

public class AotMod implements ModInitializer {
    public static final String ID = "aot";

    @Override
    public void onInitialize() {
        AotRegistry.init();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TitanManager.tick(server);
            OdmManager.tick(server);
            CrystalForge.tick();
            titanWaves(server);
        });

        AttackEntityCallback.EVENT.register(TitanManager::onAttack);
        UseEntityCallback.EVENT.register(TitanManager::onUseEntity);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            TitanManager.forceEnd(handler.getPlayer());
            OdmManager.release(handler.getPlayer());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) {
                TitanManager.forceEnd(p);
                OdmManager.release(p);
            }
        });
        // keep inherited Titan powers after respawn
        ServerPlayerEvents.COPY_FROM.register((oldP, newP, alive) -> {
            for (String t : oldP.getTags()) if (t.startsWith("aot_")) newP.addTag(t);
        });
    }

    /** Every ~2 minutes there is a chance a lone Titan wanders toward each overworld player. */
    private static void titanWaves(MinecraftServer server) {
        if (server.getTickCount() % 2400 != 0) return;
        ServerLevel ow = server.overworld();
        for (ServerPlayer p : ow.players()) {
            if (ow.getRandom().nextFloat() > 0.35f) continue;
            int near = ow.getEntitiesOfClass(TitanEntity.class, p.getBoundingBox().inflate(160)).size();
            if (near >= 3) continue;
            double ang = ow.getRandom().nextDouble() * Math.PI * 2;
            BlockPos pos = ow.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.containing(p.getX() + Math.cos(ang) * 75, 0, p.getZ() + Math.sin(ang) * 75));
            AotRegistry.TITAN.spawn(ow, pos, EntitySpawnReason.EVENT);
        }
    }
}
