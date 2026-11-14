package com.aot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

/**
 * Omni-Directional Mobility gear.
 *
 *  right-click          fire a grapple hook where you look (two hooks max, oldest is replaced)
 *                       -> if nothing is in range: gas dash in the look direction
 *  sneak + right-click  release all hooks
 *  sprint (hooked)      reel in (burns gas), swing momentum is conserved by a rope constraint
 *  sprint (airborne)    gas thrust forward while hooked
 *
 * The gear's durability bar is the gas tank; refill it with Gas Canisters.
 */
public final class OdmManager {
    private OdmManager() {}

    static final class Hook {
        final Vec3 anchor;
        double len;
        Hook(Vec3 anchor, double len) { this.anchor = anchor; this.len = len; }
    }

    static final Map<UUID, List<Hook>> HOOKS = new HashMap<>();
    private static final double RANGE = 70;

    public static ItemStack findGear(Player p) {
        if (p.getMainHandItem().getItem() == AotRegistry.ODM_GEAR) return p.getMainHandItem();
        if (p.getOffhandItem().getItem() == AotRegistry.ODM_GEAR) return p.getOffhandItem();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.getItem() == AotRegistry.ODM_GEAR) return s;
        }
        return null;
    }

    public static void release(ServerPlayer p) { HOOKS.remove(p.getUUID()); }

    public static void use(ServerPlayer p, ItemStack gear) {
        List<Hook> hooks = HOOKS.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
        ServerLevel lv = (ServerLevel) p.level();
        if (p.isShiftKeyDown()) {
            hooks.clear();
            lv.playSound(null, p.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1f, 1.2f);
            return;
        }
        if (AotItems.OdmGear.gas(gear) <= 5) {
            p.sendOverlayMessage(Component.literal("\u00a7cOut of gas! Use a Gas Canister."));
            return;
        }
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        BlockHitResult hit = lv.clip(new ClipContext(eye, eye.add(look.scale(RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) {
            // gas dash
            AotItems.OdmGear.burn(gear, 30);
            TitanManager.push(p, look.scale(1.8).add(p.getDeltaMovement().scale(0.3)));
            lv.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 25, 0.3, 0.4, 0.3, 0.08);
            lv.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1f, 1.5f);
            return;
        }
        while (hooks.size() >= 2) hooks.remove(0);
        Vec3 a = hit.getLocation();
        hooks.add(new Hook(a, eye.distanceTo(a)));
        AotItems.OdmGear.burn(gear, 5);
        // launch impulse toward the anchor
        TitanManager.push(p, p.getDeltaMovement().add(a.subtract(eye).normalize().scale(0.7)));
        lv.sendParticles(ParticleTypes.CRIT, a.x, a.y, a.z, 12, 0.2, 0.2, 0.2, 0.1);
        lv.playSound(null, p.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 1f, 1.6f);
        lv.playSound(null, a.x, a.y, a.z, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1f, 1.2f);
    }

    public static void tick(MinecraftServer server) {
        for (UUID id : new ArrayList<>(HOOKS.keySet())) {
            List<Hook> hooks = HOOKS.get(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive() || hooks.isEmpty()) { if (hooks.isEmpty() || p == null) HOOKS.remove(id); continue; }
            ItemStack gear = findGear(p);
            if (gear == null || AotItems.OdmGear.gas(gear) <= 0) {
                hooks.clear();
                p.sendOverlayMessage(Component.literal("\u00a7cGas depleted!"));
                continue;
            }
            ServerLevel lv = (ServerLevel) p.level();
            Vec3 body = p.position().add(0, p.getBbHeight() * 0.6, 0);
            Vec3 v = p.getDeltaMovement();
            boolean reel = p.isSprinting();
            for (Hook h : new ArrayList<>(hooks)) {
                Vec3 to = h.anchor.subtract(body);
                double dist = to.length();
                if (dist < 2.5 || dist > RANGE + 15) { hooks.remove(h); continue; }
                if (reel) {
                    h.len = Math.max(2.0, h.len - 0.45);
                    if (p.tickCount % 2 == 0) AotItems.OdmGear.burn(gear, 1);
                }
                if (dist > h.len) {
                    Vec3 dir = to.scale(1.0 / dist);
                    double radial = v.dot(dir);            // negative == moving away from anchor
                    if (radial < 0) v = v.subtract(dir.scale(radial));
                    v = v.add(dir.scale(Math.min(0.25 * (dist - h.len) + 0.05, 0.8)));
                }
                // draw the wire
                int pts = (int) Math.min(30, dist / 1.5);
                for (int i = 1; i < pts; i++) {
                    Vec3 q = body.add(to.scale(i / (double) pts));
                    lv.sendParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 1, 0, 0, 0, 0);
                }
            }
            if (hooks.isEmpty()) continue;
            if (reel && !p.onGround()) {                    // gas thrust
                v = v.add(p.getLookAngle().scale(0.045));
                if (p.tickCount % 3 == 0) {
                    AotItems.OdmGear.burn(gear, 1);
                    lv.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.8, p.getZ(), 2, 0.1, 0.1, 0.1, 0.02);
                }
            }
            double sp = v.length();
            if (sp > 3.0) v = v.scale(3.0 / sp);
            p.fallDistance = 0;
            TitanManager.push(p, v);
            if (p.tickCount % 20 == 0)
                p.sendOverlayMessage(Component.literal("\u00a7bGas " + AotItems.OdmGear.gas(gear) / 10 + "%"));
        }
    }
}
