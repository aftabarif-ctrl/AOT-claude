package com.aot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Handles Titan inheritance, the transformation cinematic, Titan-form stats, abilities and cleanup.
 *
 * Controls (hold the Titan Spike):
 *   not transformed      right-click            -> transform (lightning + steam growth)
 *   transformed          right-click            -> ability (Attack: Roar | War Hammer: forge selected crystal construct)
 *   transformed          sprint + right-click   -> (War Hammer) cycle construct
 *   transformed          sneak + right-click    -> revert to human form
 * Attack Titan, empty hand: right-click a mob to GRAB it, then SNEAK to THROW it.
 */
public final class TitanManager {
    private TitanManager() {}

    public enum Type {
        ATTACK("aot_attack", 10.0, "Attack Titan"),
        WAR_HAMMER("aot_war_hammer", 12.0, "War Hammer Titan");

        public final String tag;
        public final double scale;
        public final String label;

        Type(String tag, double scale, String label) {
            this.tag = tag;
            this.scale = scale;
            this.label = label;
        }
    }

    enum Phase { GROW, ACTIVE, SHRINK }

    static final class State {
        final Type type;
        Phase phase = Phase.GROW;
        int t;
        int energy = 6000;      // 5 minutes of Titan time
        int cd;                 // ability cooldown (ticks)
        int construct;          // selected War Hammer construct
        UUID grabbed;
        State(Type type) { this.type = type; }
    }

    static final class Thrown {
        final ServerLevel level;
        final UUID by;
        int age;
        Thrown(ServerLevel level, UUID by) { this.level = level; this.by = by; }
    }

    static final Map<UUID, State> STATES = new HashMap<>();
    static final Map<UUID, Thrown> THROWN = new HashMap<>();

    private static final int GROW_TICKS = 40;
    private static final int SHRINK_TICKS = 30;

    // ------------------------------------------------------------------ helpers

    private static ServerLevel lv(ServerPlayer p) { return (ServerLevel) p.level(); }

    private static float ease(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    public static Type inherited(ServerPlayer p) {
        for (Type t : Type.values()) if (p.entityTags().contains(t.tag)) return t;
        return null;
    }

    public static boolean isTransformed(Player p) {
        State s = STATES.get(p.getUUID());
        return s != null && s.phase == Phase.ACTIVE;
    }

    public static void push(Entity e, Vec3 v) {
        e.setDeltaMovement(v);
        if (e instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
        else e.hurtMarked = true;
    }

    private static void mod(LivingEntity e, Holder<Attribute> attr, String name, double value, AttributeModifier.Operation op) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst == null) return;
        var id = AotRegistry.id("titan_" + name);
        inst.removeModifier(id);
        inst.addTransientModifier(new AttributeModifier(id, value, op));
    }

    private static final String[] STAT_NAMES = { "hp", "dmg", "spd", "step", "jump", "fall", "armor", "kb", "reach", "breach" };
    private static final Holder<Attribute>[] STAT_ATTRS = statAttrs();

    @SuppressWarnings("unchecked")
    private static Holder<Attribute>[] statAttrs() {
        return new Holder[] { Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE, Attributes.MOVEMENT_SPEED,
                Attributes.STEP_HEIGHT, Attributes.JUMP_STRENGTH, Attributes.SAFE_FALL_DISTANCE, Attributes.ARMOR,
                Attributes.KNOCKBACK_RESISTANCE, Attributes.ENTITY_INTERACTION_RANGE, Attributes.BLOCK_INTERACTION_RANGE };
    }

    private static void applyStats(ServerPlayer p, State s) {
        boolean a = s.type == Type.ATTACK;
        double sc = s.type.scale;
        double[] v = { a ? 240 : 180, a ? 30 : 18, a ? 1.2 : 0.7, sc * 0.5, 0.5, 60, a ? 10 : 16, 1.0, sc * 0.8, sc * 0.8 };
        AttributeModifier.Operation[] ops = new AttributeModifier.Operation[STAT_NAMES.length];
        for (int i = 0; i < ops.length; i++) ops[i] = AttributeModifier.Operation.ADD_VALUE;
        ops[2] = AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
        for (int i = 0; i < STAT_NAMES.length; i++) mod(p, STAT_ATTRS[i], STAT_NAMES[i], v[i], ops[i]);
        p.setHealth(Math.max(p.getHealth(), p.getMaxHealth() * 0.6f));
    }

    private static void clearStats(ServerPlayer p) {
        for (int i = 0; i < STAT_NAMES.length; i++) {
            AttributeInstance inst = p.getAttribute(STAT_ATTRS[i]);
            if (inst != null) inst.removeModifier(AotRegistry.id("titan_" + STAT_NAMES[i]));
        }
        AttributeInstance sc = p.getAttribute(Attributes.SCALE);
        if (sc != null) sc.removeModifier(AotRegistry.id("titan_scale"));
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    // ------------------------------------------------------------------ visual effects

    static void steam(ServerLevel lv, Entity e, int n) {
        double w = Math.max(0.6, e.getBbWidth()) / 2, h = e.getBbHeight();
        lv.sendParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + h / 2, e.getZ(), n, w, h / 2, w, 0.03);
        lv.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + h * 0.3, e.getZ(), n / 2 + 1, w, h / 3, w, 0.05);
        lv.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, e.getX(), e.getY() + h * 0.6, e.getZ(), 1, w, h / 4, w, 0.01);
    }

    static void lightning(ServerLevel lv, Entity e, double spread) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(lv, EntitySpawnReason.TRIGGERED);
        if (bolt == null) return;
        bolt.setPos(e.getX() + (lv.getRandom().nextDouble() - 0.5) * spread * 2, e.getY(),
                e.getZ() + (lv.getRandom().nextDouble() - 0.5) * spread * 2);
        bolt.setVisualOnly(true);
        lv.addFreshEntity(bolt);
    }

    static int destroy(ServerLevel lv, AABB box, float maxHardness) {
        if (!lv.getGameRules().get(GameRules.MOB_GRIEFING)) return 0;
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState st = lv.getBlockState(pos);
            if (st.isAir() || !st.getFluidState().isEmpty() || st.is(AotRegistry.CRYSTAL)) continue;
            float h = st.getDestroySpeed(lv, pos);
            if (h < 0 || h > maxHardness) continue;
            if (lv.getRandom().nextInt(6) == 0)
                lv.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st),
                        pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 4, .3, .3, .3, .1);
            lv.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), 3);
            if (++n >= 400) break;
        }
        return n;
    }

    static void destroyAround(ServerLevel lv, BlockPos c, int r, float maxHard) {
        destroy(lv, new AABB(c).inflate(r), maxHard);
    }

    // ------------------------------------------------------------------ lifecycle

    public static void grant(ServerPlayer p, Type t) {
        p.addTag(t.tag);
        ServerLevel lv = lv(p);
        lightning(lv, p, 1);
        lv.playSound(null, p.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 3f, 0.7f);
        steam(lv, p, 40);
        p.sendSystemMessage(Component.literal("\u00a76You inherited the power of the " + t.label + "."));
        p.sendSystemMessage(Component.literal("\u00a77Hold a Titan Spike and right-click to transform."));
    }

    public static void useSpike(ServerPlayer p) {
        Type type = inherited(p);
        if (type == null) {
            p.sendOverlayMessage(Component.literal("\u00a77You hold no Titan power. Drink a serum first."));
            return;
        }
        State s = STATES.get(p.getUUID());
        if (s == null) {
            STATES.put(p.getUUID(), new State(type));
            p.sendOverlayMessage(Component.literal("\u00a7c" + type.label + "..."));
            return;
        }
        if (s.phase != Phase.ACTIVE) return;
        if (p.isShiftKeyDown()) { startShrink(p, s); return; }
        if (type == Type.WAR_HAMMER && p.isSprinting()) {
            s.construct = (s.construct + 1) % CrystalForge.Construct.values().length;
            p.sendOverlayMessage(Component.literal("\u00a7bConstruct: " + CrystalForge.Construct.values()[s.construct].label));
            return;
        }
        if (s.cd > 0) {
            p.sendOverlayMessage(Component.literal("\u00a77Recharging... " + (s.cd / 20 + 1) + "s"));
            return;
        }
        if (type == Type.ATTACK) { roar(p, s); s.cd = 240; }
        else { CrystalForge.forge(p, CrystalForge.Construct.values()[s.construct]); s.cd = 40; }
    }

    private static void startShrink(ServerPlayer p, State s) {
        s.phase = Phase.SHRINK;
        s.t = 0;
        s.grabbed = null;
        ServerLevel lv = lv(p);
        for (int i = 0; i < STAT_NAMES.length; i++) {
            AttributeInstance inst = p.getAttribute(STAT_ATTRS[i]);
            if (inst != null) inst.removeModifier(AotRegistry.id("titan_" + STAT_NAMES[i]));
        }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        lv.playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 3f, 1.4f);
        steam(lv, p, 80);
    }

    public static void forceEnd(ServerPlayer p) {
        State s = STATES.remove(p.getUUID());
        if (s != null) clearStats(p);
    }

    // ------------------------------------------------------------------ per-tick

    public static void tick(MinecraftServer server) {
        for (UUID id : new ArrayList<>(STATES.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            State s = STATES.get(id);
            if (p == null || !p.isAlive() || s == null) {
                if (p != null) forceEnd(p); else STATES.remove(id);
                continue;
            }
            tickPlayer(p, s);
        }
        tickThrown();
    }

    private static void tickPlayer(ServerPlayer p, State s) {
        ServerLevel lv = lv(p);
        double sc = s.type.scale;
        switch (s.phase) {
            case GROW -> {
                p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 5, 4, false, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 5, 6, false, false, false));
                if (s.t == 0) {
                    lv.playSound(null, p.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 5f, 0.6f);
                    lightning(lv, p, 0.5);
                    lv.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 120, 1.5, 2, 1.5, 0.6);
                }
                if (s.t == 8 || s.t == 18 || s.t == 28) lightning(lv, p, 3 + s.t / 6.0);
                if (s.t == 20) lv.playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 6f, 0.5f);
                steam(lv, p, 6 + s.t / 2);
                mod(p, Attributes.SCALE, "scale", (sc - 1) * ease(s.t / (float) GROW_TICKS), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
                if (s.t % 3 == 0) destroy(lv, p.getBoundingBox().deflate(0, 0.2, 0).move(0, 0.2, 0), 6f);
                if (++s.t >= GROW_TICKS) {
                    applyStats(p, s);
                    s.phase = Phase.ACTIVE;
                    s.t = 0;
                    shockwave(lv, p, 8 + sc, 8f);
                    lv.playSound(null, p.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 4f, 0.7f);
                    p.sendOverlayMessage(Component.literal("\u00a7c" + s.type.label));
                }
            }
            case ACTIVE -> {
                s.energy--;
                if (s.cd > 0) s.cd--;
                p.getFoodData().setFoodLevel(20);
                if (s.t++ % 3 == 0) destroy(lv, p.getBoundingBox().inflate(0.3, 0, 0.3).move(0, 0.2, 0), 6f);
                if (s.type == Type.ATTACK) {
                    if (p.tickCount % 5 == 0 && p.getHealth() < p.getMaxHealth()) { p.heal(1.5f); steam(lv, p, 3); }
                } else if (p.tickCount % 10 == 0) p.heal(0.5f);
                if (p.getHealth() < p.getMaxHealth() * 0.6f && p.tickCount % 4 == 0) steam(lv, p, 4);
                if (p.onGround() && p.getDeltaMovement().horizontalDistanceSqr() > 0.01 && p.tickCount % 8 == 0) {
                    lv.sendParticles(ParticleTypes.POOF, p.getX(), p.getY(), p.getZ(), 6, sc * 0.2, 0.1, sc * 0.2, 0.05);
                    lv.playSound(null, p.blockPosition(), SoundEvents.IRON_GOLEM_STEP, SoundSource.PLAYERS, 4f, 0.4f);
                }
                tickGrab(p, s, lv, sc);
                if (p.tickCount % 10 == 0) {
                    String extra = s.type == Type.WAR_HAMMER ? "  \u00a7b[" + CrystalForge.Construct.values()[s.construct].label + "]" : "";
                    p.sendOverlayMessage(Component.literal("\u00a7c" + s.type.label + " \u00a77" + (s.energy / 20) + "s" + extra));
                }
                if (s.energy <= 0) startShrink(p, s);
            }
            case SHRINK -> {
                steam(lv, p, 10);
                mod(p, Attributes.SCALE, "scale", (sc - 1) * (1 - ease(s.t / (float) SHRINK_TICKS)), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
                if (++s.t >= SHRINK_TICKS) {
                    clearStats(p);
                    STATES.remove(p.getUUID());
                    p.getCooldowns().addCooldown(new ItemStack(AotRegistry.TITAN_SPIKE), 600);
                    p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 400, 1));
                    p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 200, 1));
                    p.sendOverlayMessage(Component.literal("\u00a77You return to human form, exhausted."));
                }
            }
        }
    }

    private static void tickGrab(ServerPlayer p, State s, ServerLevel lv, double sc) {
        if (s.grabbed == null) return;
        Entity g = lv.getEntity(s.grabbed);
        if (g == null || !g.isAlive()) { s.grabbed = null; return; }
        if (p.isShiftKeyDown()) {
            Vec3 look = p.getLookAngle();
            push(g, look.scale(3.2).add(0, 0.5, 0));
            THROWN.put(g.getUUID(), new Thrown(lv, p.getUUID()));
            lv.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 4f, 0.4f);
            s.grabbed = null;
            return;
        }
        Vec3 fwd = Vec3.directionFromRotation(0, p.getYRot());
        Vec3 hand = p.position().add(fwd.scale(sc * 0.45)).add(0, p.getBbHeight() * 0.55, 0);
        if (g instanceof ServerPlayer sp) sp.teleportTo(hand.x, hand.y, hand.z);
        else g.setPos(hand.x, hand.y, hand.z);
        g.setDeltaMovement(Vec3.ZERO);
        g.fallDistance = 0;
        if (p.tickCount % 10 == 0 && g instanceof LivingEntity le)
            le.hurtServer(lv, lv.damageSources().playerAttack(p), 3f);
    }

    private static void tickThrown() {
        for (UUID id : new ArrayList<>(THROWN.keySet())) {
            Thrown th = THROWN.get(id);
            Entity e = th.level.getEntity(id);
            if (e == null || !e.isAlive()) { THROWN.remove(id); continue; }
            th.age++;
            if (th.age > 4 && (e.onGround() || e.horizontalCollision || e.verticalCollision || th.age > 120)) {
                ServerPlayer by = th.level.getServer().getPlayerList().getPlayer(th.by);
                var src = by != null ? th.level.damageSources().playerAttack(by) : th.level.damageSources().generic();
                if (e instanceof LivingEntity le) le.hurtServer(th.level, src, 28f);
                Vec3 c = e.position();
                th.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                th.level.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 40, 1.5, 0.5, 1.5, 0.2);
                th.level.playSound(null, e.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 4f, 0.5f);
                destroyAround(th.level, e.blockPosition(), 3, 3f);
                THROWN.remove(id);
            }
        }
    }

    static void shockwave(ServerLevel lv, ServerPlayer p, double radius, float dmg) {
        Vec3 c = p.position();
        for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(radius), x -> x != p)) {
            Vec3 d = e.position().subtract(c);
            d = new Vec3(d.x, 0, d.z).normalize();
            push(e, new Vec3(d.x * 1.8, 0.6, d.z * 1.8));
            e.hurtServer(lv, lv.damageSources().playerAttack(p), dmg);
        }
        lv.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI / 18;
            lv.sendParticles(ParticleTypes.CLOUD, c.x + Math.cos(a) * radius * 0.6, c.y + 0.3, c.z + Math.sin(a) * radius * 0.6, 2, 0, 0.2, 0, 0.15);
        }
    }

    private static void roar(ServerPlayer p, State s) {
        ServerLevel lv = lv(p);
        lv.playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 8f, 0.5f);
        lv.playSound(null, p.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 8f, 0.4f);
        lv.sendParticles(ParticleTypes.SONIC_BOOM, p.getX(), p.getY() + p.getBbHeight() * 0.85, p.getZ(), 1, 0, 0, 0, 0);
        shockwave(lv, p, 28, 6f);
    }

    // ------------------------------------------------------------------ events

    /** Titan punches splash nearby enemies and shatter terrain; dual blades get a bonus slash. */
    public static InteractionResult onAttack(Player pl, Level w, InteractionHand h, Entity target, EntityHitResult hit) {
        if (!(w instanceof ServerLevel lv) || !(pl instanceof ServerPlayer p)) return InteractionResult.PASS;
        State s = STATES.get(p.getUUID());
        if (s != null && s.phase == Phase.ACTIVE) {
            Vec3 c = target.position().add(0, target.getBbHeight() / 2, 0);
            lv.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 0.5, 0.5, 0.5, 0);
            lv.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 4f, 0.5f);
            double r = 2 + s.type.scale * 0.35;
            for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r),
                    x -> x != p && x != target && !(x instanceof Player))) {
                e.hurtServer(lv, lv.damageSources().playerAttack(p), s.type == Type.ATTACK ? 15f : 8f);
                Vec3 d = e.position().subtract(p.position());
                d = new Vec3(d.x, 0, d.z).normalize();
                push(e, new Vec3(d.x * 1.2, 0.5, d.z * 1.2));
            }
            if (s.type == Type.ATTACK) {
                destroyAround(lv, BlockPos.containing(c), 2, 4f);
                Vec3 d = target.position().subtract(p.position());
                d = new Vec3(d.x, 0, d.z).normalize();
                push(target, new Vec3(d.x * 2.0, 0.6, d.z * 2.0));
            }
        } else if (p.getMainHandItem().getItem() instanceof AotItems.Blade
                && p.getOffhandItem().getItem() instanceof AotItems.Blade && target instanceof LivingEntity le) {
            le.hurtServer(lv, lv.damageSources().playerAttack(p), 4f);
            lv.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(), 1, 0, 0, 0, 0);
        }
        return InteractionResult.PASS;
    }

    /** Attack Titan: empty-hand right-click on a mob grabs it. */
    public static InteractionResult onUseEntity(Player pl, Level w, InteractionHand hand, Entity e, EntityHitResult hit) {
        if (w.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        State s = STATES.get(pl.getUUID());
        if (s == null || s.phase != Phase.ACTIVE || s.type != Type.ATTACK || s.grabbed != null
                || !pl.getMainHandItem().isEmpty() || !(e instanceof LivingEntity)) return InteractionResult.PASS;
        s.grabbed = e.getUUID();
        ((ServerLevel) w).playSound(null, pl.blockPosition(), SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 3f, 0.4f);
        ((ServerPlayer) pl).sendOverlayMessage(Component.literal("\u00a7cGrabbed! Sneak to throw."));
        return InteractionResult.SUCCESS;
    }
}
