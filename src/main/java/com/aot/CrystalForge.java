package com.aot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** War Hammer Titan: instantly forges huge crystal constructs that rise out of the ground, then fade away. */
public final class CrystalForge {
    private CrystalForge() {}

    public enum Construct {
        GIANT_SWORD("Giant Sword", 30f), SPEAR("Crystal Spear", 35f), WAR_HAMMER("War Hammer", 40f),
        BOW("Crystal Bow", 0f), CROSSBOW("Crystal Crossbow", 0f), SHIELD("Crystal Shield", 0f),
        SPIKES("Spike Field", 25f), WALL("Crystal Wall", 8f), PILLAR("Crystal Pillar", 12f);

        public final String label;
        final float damage;
        Construct(String label, float damage) { this.label = label; this.damage = damage; }
    }

    /** Crystal being raised, a few blocks per tick, lowest layers first. */
    private static final class Job {
        final ServerLevel level;
        final ArrayDeque<BlockPos> queue;
        final List<BlockPos> placed = new ArrayList<>();
        final int perTick;
        Job(ServerLevel level, ArrayDeque<BlockPos> queue, int perTick) { this.level = level; this.queue = queue; this.perTick = perTick; }
    }

    /** Placed crystal waiting to shatter. */
    private static final class Expiry {
        final ServerLevel level;
        final List<BlockPos> blocks;
        int ttl = 900;      // 45 seconds
        Expiry(ServerLevel level, List<BlockPos> blocks) { this.level = level; this.blocks = blocks; }
    }

    private static final List<Job> JOBS = new ArrayList<>();
    private static final List<Expiry> EXPIRING = new ArrayList<>();

    // ------------------------------------------------------------------ shape helpers

    private static void box(Set<BlockPos> s, BlockPos o, Direction f, Direction r,
                            int f0, int f1, int r0, int r1, int u0, int u1) {
        for (int a = f0; a <= f1; a++)
            for (int b = r0; b <= r1; b++)
                for (int c = u0; c <= u1; c++)
                    s.add(o.relative(f, a).relative(r, b).above(c));
    }

    private static BlockPos ground(ServerLevel lv, BlockPos pos, int refY) {
        BlockPos g = lv.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
        return Math.abs(g.getY() - refY) > 14 ? new BlockPos(pos.getX(), refY, pos.getZ()) : g;
    }

    // ------------------------------------------------------------------ forging

    public static void forge(ServerPlayer p, Construct c) {
        ServerLevel lv = (ServerLevel) p.level();
        Direction f = p.getDirection(), r = f.getClockWise();
        BlockPos base = p.blockPosition();
        BlockPos o = ground(lv, base.relative(f, 7), base.getY());
        Set<BlockPos> set = new LinkedHashSet<>();
        RandomSource rnd = lv.getRandom();

        switch (c) {
            case GIANT_SWORD -> {
                box(set, o, f, r, 0, 1, 0, 1, 0, 3);                    // grip
                box(set, o, f, r, 0, 1, -4, 5, 4, 5);                   // cross-guard
                for (int u = 6; u <= 25; u++) {                          // tapering blade
                    int half = Math.max(1, Math.min(2, 26 - u));
                    box(set, o, f, r, 0, 1, 1 - half, half, u, u);
                }
            }
            case SPEAR -> {
                for (int i = 0; i <= 24; i++) {
                    int u = 2 + i / 3;
                    int w = i < 18 ? 0 : (24 - i) / 2;                   // head widens then tapers
                    box(set, o, f, r, i, i, -w, 1 + w, u, u + 1);
                }
            }
            case WAR_HAMMER -> {
                box(set, o, f, r, 0, 1, 0, 1, 0, 13);                    // haft
                box(set, o, f, r, -3, 4, -3, 4, 14, 19);                 // head
            }
            case BOW -> {
                for (double t = -1; t <= 1; t += 0.05) {                 // stave
                    int u = (int) Math.round(1 + (t + 1) * 11);
                    int fo = (int) Math.round(4 * t * t);
                    box(set, o, f, r, fo, fo, 0, 1, u, u);
                }
                box(set, o, f, r, 4, 4, 0, 0, 1, 23);                   // string
            }
            case CROSSBOW -> {
                box(set, o, f, r, 0, 12, 0, 1, 10, 11);                  // stock
                box(set, o, f, r, 0, 1, -8, 9, 12, 13);                  // prod
                box(set, o, f, r, 5, 6, 0, 1, 8, 9);                     // trigger
            }
            case SHIELD -> {
                for (int deg = -80; deg <= 80; deg += 4) {
                    double ang = Math.toRadians(deg);
                    for (int rad = 7; rad <= 8; rad++)
                        box(set, base, f, r, (int) Math.round(rad * Math.cos(ang)), (int) Math.round(rad * Math.cos(ang)),
                                (int) Math.round(rad * Math.sin(ang)), (int) Math.round(rad * Math.sin(ang)), 0, 11);
                }
            }
            case SPIKES -> {
                for (int k = 0; k < 16; k++) {
                    BlockPos sp = ground(lv, base.relative(f, 4 + rnd.nextInt(16)).relative(r, rnd.nextInt(21) - 10), base.getY());
                    int h = 4 + rnd.nextInt(8);
                    for (int u = 0; u < h; u++) {
                        int half = u < h / 3 ? 1 : 0;
                        for (int dx = -half; dx <= half; dx++)
                            for (int dz = -half; dz <= half; dz++) set.add(sp.offset(dx, u, dz));
                    }
                }
            }
            case WALL -> box(set, o, f, r, 0, 1, -10, 10, -3, 9);
            case PILLAR -> box(set, o, f, r, 0, 2, -1, 1, -3, 26);
        }

        // keep only replaceable positions
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> sorted = new ArrayList<>();
        for (BlockPos pos : set) {
            BlockState st = lv.getBlockState(pos);
            if (st.isAir() || st.canBeReplaced() || st.getDestroySpeed(lv, pos) <= 0.5f) sorted.add(pos);
        }
        sorted.sort(Comparator.comparingInt(BlockPos::getY));
        queue.addAll(sorted);
        JOBS.add(new Job(lv, queue, Math.max(24, sorted.size() / 18)));

        lv.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 5f, 0.5f);
        lv.playSound(null, o, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 5f, 0.4f);
        lv.sendParticles(ParticleTypes.ELECTRIC_SPARK, o.getX() + .5, o.getY() + 1, o.getZ() + .5, 80, 3, 2, 3, 0.4);

        // eruption damage + launch
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (BlockPos q : sorted) {
            minX = Math.min(minX, q.getX()); minY = Math.min(minY, q.getY()); minZ = Math.min(minZ, q.getZ());
            maxX = Math.max(maxX, q.getX() + 1); maxY = Math.max(maxY, q.getY() + 1); maxZ = Math.max(maxZ, q.getZ() + 1);
        }
        if (!sorted.isEmpty() && c.damage > 0) {
            AABB zone = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(1.5);
            for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, zone, x -> x != p && !(x instanceof Player))) {
                e.hurtServer(lv, lv.damageSources().playerAttack(p), c.damage);
                TitanManager.push(e, new Vec3(0, 0.9, 0));
            }
        }

        // projectile constructs
        if (c == Construct.BOW) {
            Vec3 from = Vec3.atBottomCenterOf(o.relative(f, 2).above(12));
            Vec3 dir = new Vec3(f.getStepX(), 0.04, f.getStepZ());
            for (int i = 0; i < 14; i++) volley(lv, p, from.add(r.getStepX() * (i - 7) * 0.8, rnd.nextDouble() * 4 - 2, r.getStepZ() * (i - 7) * 0.8), dir, 6f, 14, 0);
        } else if (c == Construct.CROSSBOW) {
            Vec3 from = Vec3.atBottomCenterOf(o.relative(f, 13).above(11));
            volley(lv, p, from, new Vec3(f.getStepX(), 0.02, f.getStepZ()), 8f, 90, 0);
        }
    }

    private static void volley(ServerLevel lv, ServerPlayer owner, Vec3 pos, Vec3 dir, float speed, double dmg, int pierce) {
        @SuppressWarnings("unchecked")
        EntityType<AbstractArrow> arrowType = (EntityType<AbstractArrow>) BuiltInRegistries.ENTITY_TYPE
                .getValue(Identifier.withDefaultNamespace("arrow"));
        AbstractArrow a = arrowType.create(lv, EntitySpawnReason.TRIGGERED);
        if (a == null) return;
        a.setOwner(owner);
        a.setPos(pos.x, pos.y, pos.z);
        a.setBaseDamage(dmg);
        a.setCritArrow(true);
        a.setNoGravity(true);
        a.pickup = AbstractArrow.Pickup.DISALLOWED;
        a.shoot(dir.x, dir.y, dir.z, speed, 1.5f);
        lv.addFreshEntity(a);
    }

    // ------------------------------------------------------------------ tick

    public static void tick() {
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job j = it.next();
            for (int i = 0; i < j.perTick && !j.queue.isEmpty(); i++) {
                BlockPos pos = j.queue.poll();
                BlockState st = j.level.getBlockState(pos);
                if (!(st.isAir() || st.canBeReplaced() || st.getDestroySpeed(j.level, pos) <= 0.5f)) continue;
                j.level.setBlock(pos, AotRegistry.CRYSTAL.defaultBlockState(), 3);
                j.placed.add(pos);
                if (i % 5 == 0)
                    j.level.sendParticles(ParticleTypes.END_ROD, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 2, .3, .3, .3, 0.02);
            }
            if (j.queue.isEmpty()) {
                EXPIRING.add(new Expiry(j.level, j.placed));
                it.remove();
            }
        }
        for (Iterator<Expiry> it = EXPIRING.iterator(); it.hasNext(); ) {
            Expiry e = it.next();
            if (--e.ttl > 0) continue;
            int n = 0;
            for (BlockPos pos : e.blocks) {
                if (!e.level.getBlockState(pos).is(AotRegistry.CRYSTAL)) continue;
                e.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                if (n++ % 6 == 0)
                    e.level.sendParticles(ParticleTypes.END_ROD, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 1, .2, .2, .2, 0.05);
            }
            if (!e.blocks.isEmpty())
                e.level.playSound(null, e.blocks.get(0), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 4f, 0.6f);
            it.remove();
        }
    }
}
