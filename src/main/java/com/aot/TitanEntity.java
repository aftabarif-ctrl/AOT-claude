package com.aot;

import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A mindless giant. Strong in melee, steams and regenerates, but a strike to the NAPE
 * (from behind and above, ideally at ODM speed) deals six times damage and halts regeneration.
 * Blades, Thunder Spears and fast aerial hits are the way to kill it.
 */
public class TitanEntity extends Monster {
    private int napeStun;

    public TitanEntity(EntityType<? extends TitanEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 160)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 12)
                .add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 3.0);
    }

    public static AttributeSupplier.Builder createBossAttributes() {
        return createAttributes()
                .add(Attributes.MAX_HEALTH, 1500)
                .add(Attributes.ATTACK_DAMAGE, 35)
                .add(Attributes.ARMOR, 12)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.STEP_HEIGHT, 6.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 40f));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        EntitySpawnReason reason, SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        if (!(this instanceof Boss)) {
            double sc = 2.5 + getRandom().nextDouble() * 7.0;           // 2.5x - 9.5x: small to giant
            getAttribute(Attributes.SCALE).setBaseValue(sc);
            getAttribute(Attributes.MAX_HEALTH).setBaseValue(40 + sc * 22);
            getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(6 + sc * 1.4);
            setHealth(getMaxHealth());
        }
        return data;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource src, float amount) {
        Entity at = src.getEntity();
        if (at instanceof LivingEntity a && !src.is(DamageTypeTags.IS_EXPLOSION)) {
            Vec3 rel = a.position().subtract(position());
            rel = new Vec3(rel.x, 0, rel.z);
            Vec3 face = Vec3.directionFromRotation(0, getYRot());
            boolean behind = rel.lengthSqr() > 1e-4 && rel.normalize().dot(face) < -0.2;
            boolean high = a.getEyeY() > getY() + getBbHeight() * 0.6;
            boolean blade = a.getMainHandItem().getItem() instanceof AotItems.Blade;
            if (behind && high) {
                amount *= 6f;
                napeStun = 120;
                level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, getX(), getY() + getBbHeight() * 0.85, getZ(), 12, 0.4, 0.4, 0.4, 0.2);
                level.sendParticles(ParticleTypes.CRIT, getX(), getY() + getBbHeight() * 0.85, getZ(), 20, 0.5, 0.5, 0.5, 0.4);
                level.playSound(null, blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 3f, 0.6f);
                if (blade) amount *= 1.5f;
            }
            // ODM momentum: faster swings hit harder
            amount *= 1f + (float) Math.min(2.5, a.getDeltaMovement().length()) * 1.2f;
        }
        return super.hurtServer(level, src, amount);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(level() instanceof ServerLevel lv)) return;
        if (napeStun > 0) {
            napeStun--;
            addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 5, 2, false, false, false));
        } else if (tickCount % 20 == 0 && getHealth() < getMaxHealth()) {
            heal(getMaxHealth() * 0.01f);
            lv.sendParticles(ParticleTypes.CLOUD, getX(), getY() + getBbHeight() * 0.5, getZ(), 4, getBbWidth() / 2, getBbHeight() / 3, getBbWidth() / 2, 0.02);
        }
        if (getHealth() < getMaxHealth() * 0.5f && tickCount % 10 == 0)
            lv.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + getBbHeight() * 0.7, getZ(), 1, getBbWidth() / 3, getBbHeight() / 4, getBbWidth() / 3, 0.01);
        // trample weak blocks (leaves, glass, wood, thatch...) while walking
        if (tickCount % 10 == 0 && getDeltaMovement().horizontalDistanceSqr() > 0.001) {
            AABB b = getBoundingBox();
            TitanManager.destroy(lv, new AABB(b.minX, b.minY + 0.2, b.minZ, b.maxX, b.minY + b.getYsize() * 0.6, b.maxZ).inflate(0.3, 0, 0.3), 2.2f);
        }
        if (tickCount % 12 == 0 && onGround() && getDeltaMovement().horizontalDistanceSqr() > 0.001)
            lv.playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_STEP, SoundSource.HOSTILE, 3f + (float) getScale() * 0.3f, 0.4f);
    }

    @Override
    public void die(DamageSource src) {
        super.die(src);
        if (level() instanceof ServerLevel lv) {
            lv.sendParticles(ParticleTypes.CLOUD, getX(), getY() + getBbHeight() * 0.5, getZ(), 80, getBbWidth(), getBbHeight() / 2, getBbWidth(), 0.08);
            lv.playSound(null, blockPosition(), SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.HOSTILE, 5f, 0.4f);
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource src, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, src, recentlyHit);
        if (getRandom().nextFloat() < 0.15f) spawnAtLocation(level, AotRegistry.GAS_CANISTER);
        if (getRandom().nextFloat() < 0.04f) spawnAtLocation(level, AotRegistry.THUNDER_SPEAR);
    }

    // ===================================================================== BOSS

    /** Rogue Titan: 14x scale, ground-slam shockwaves, summons Titans at half health, drops a serum. */
    public static class Boss extends TitanEntity {
        private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Rogue Titan"),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        private int slamTimer;
        private boolean summoned;

        public Boss(EntityType<? extends Boss> type, Level level) {
            super(type, level);
            setPersistenceRequired();
        }

        @Override
        public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                            EntitySpawnReason reason, SpawnGroupData data) {
            data = super.finalizeSpawn(level, difficulty, reason, data);
            getAttribute(Attributes.SCALE).setBaseValue(14.0);
            setHealth(getMaxHealth());
            return data;
        }

        @Override
        protected void customServerAiStep(ServerLevel lv) {
            super.customServerAiStep(lv);
            bar.setProgress(getHealth() / getMaxHealth());
            LivingEntity t = getTarget();
            if (++slamTimer >= 140 && t != null && distanceToSqr(t) < 40 * 40) {
                slamTimer = 0;
                slam(lv);
            }
            if (!summoned && getHealth() < getMaxHealth() * 0.5f) {
                summoned = true;
                lv.playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 10f, 0.4f);
                for (int i = 0; i < 3; i++) {
                    double a = i * Math.PI * 2 / 3;
                    AotRegistry.TITAN.spawn(lv, blockPosition().offset((int) (Math.cos(a) * 20), 0, (int) (Math.sin(a) * 20)), EntitySpawnReason.MOB_SUMMONED);
                }
            }
        }

        private void slam(ServerLevel lv) {
            lv.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 10f, 0.4f);
            lv.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
            for (int i = 0; i < 48; i++) {
                double a = i * Math.PI / 24;
                lv.sendParticles(ParticleTypes.CLOUD, getX() + Math.cos(a) * 12, getY() + 0.3, getZ() + Math.sin(a) * 12, 3, 0, 0.3, 0, 0.2);
            }
            for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(26), x -> x != this && !(x instanceof TitanEntity))) {
                Vec3 d = e.position().subtract(position());
                d = new Vec3(d.x, 0, d.z).normalize();
                TitanManager.push(e, new Vec3(d.x * 2.0, 0.9, d.z * 2.0));
                e.hurtServer(lv, lv.damageSources().mobAttack(this), 10f);
            }
            if (lv.getGameRules().get(GameRules.MOB_GRIEFING))
                TitanManager.destroy(lv, getBoundingBox().inflate(6, 0, 6).setMinY(getY() - 2).setMaxY(getY() + 4), 3f);
        }

        @Override public void startSeenByPlayer(ServerPlayer p) { super.startSeenByPlayer(p); bar.addPlayer(p); }
        @Override public void stopSeenByPlayer(ServerPlayer p) { super.stopSeenByPlayer(p); bar.removePlayer(p); }

        @Override
        protected void dropCustomDeathLoot(ServerLevel level, DamageSource src, boolean recentlyHit) {
            super.dropCustomDeathLoot(level, src, recentlyHit);
            spawnAtLocation(level, getRandom().nextBoolean() ? AotRegistry.ATTACK_SERUM : AotRegistry.WAR_HAMMER_SERUM);
            spawnAtLocation(level, AotRegistry.THUNDER_SPEAR);
        }
    }
}
