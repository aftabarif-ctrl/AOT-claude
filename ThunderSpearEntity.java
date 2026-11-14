package com.aot;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Thrown explosive spear: terrain-destroying blast plus huge bonus damage against Titans. */
public class ThunderSpearEntity extends ThrowableItemProjectile {
    public ThunderSpearEntity(EntityType<? extends ThunderSpearEntity> type, Level level) {
        super(type, level);
    }

    public ThunderSpearEntity(Level level, LivingEntity owner, ItemStack stack) {
        super(AotRegistry.SPEAR, owner, level, stack);
    }

    @Override
    protected Item getDefaultItem() { return AotRegistry.THUNDER_SPEAR; }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!(level() instanceof ServerLevel lv)) return;
        Vec3 c = result.getLocation();
        Entity owner = getOwner();
        for (TitanEntity t : lv.getEntitiesOfClass(TitanEntity.class, new AABB(c, c).inflate(7)))
            t.hurtServer(lv, lv.damageSources().explosion(this, owner), 90f);
        lv.explode(this, c.x, c.y, c.z, 4.5f, Level.ExplosionInteraction.TNT);
        lv.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        lv.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 90, 2.5, 2.5, 2.5, 0.4);
        lv.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 40, 1.5, 1.5, 1.5, 0.3);
        lv.playSound(null, c.x, c.y, c.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 3f, 1.4f);
        discard();
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel lv)
            lv.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0.02);
    }
}
