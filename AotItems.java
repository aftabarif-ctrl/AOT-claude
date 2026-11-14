package com.aot;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class AotItems {
    private AotItems() {}

    /** Drinking grants a Titan power permanently (one per player). */
    public static class Serum extends Item {
        final TitanManager.Type type;

        public Serum(TitanManager.Type type, Properties props) {
            super(props);
            this.type = type;
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (level instanceof ServerLevel && player instanceof ServerPlayer sp) {
                if (TitanManager.inherited(sp) != null) {
                    sp.sendOverlayMessage(Component.literal("\u00a77You already carry a Titan's power."));
                    return InteractionResult.FAIL;
                }
                TitanManager.grant(sp, type);
                player.getItemInHand(hand).consume(1, player);
            }
            return InteractionResult.SUCCESS;
        }
    }

    /** Transform / detransform / use ability. See TitanManager#useSpike for controls. */
    public static class Spike extends Item {
        public Spike(Properties props) { super(props); }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (level instanceof ServerLevel && player instanceof ServerPlayer sp) TitanManager.useSpike(sp);
            return InteractionResult.SUCCESS;
        }
    }

    /** Durability bar == gas tank. */
    public static class OdmGear extends Item {
        public OdmGear(Properties props) { super(props); }

        public static int gas(ItemStack s) { return s.getMaxDamage() - 1 - s.getDamageValue(); }

        public static void burn(ItemStack s, int n) {
            s.setDamageValue(Math.min(s.getMaxDamage() - 1, s.getDamageValue() + n));
        }

        @Override
        public int getBarColor(ItemStack stack) { return 0x4FC3F7; }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (level instanceof ServerLevel && player instanceof ServerPlayer sp)
                OdmManager.use(sp, player.getItemInHand(hand));
            return InteractionResult.SUCCESS;
        }
    }

    public static class GasCanister extends Item {
        public GasCanister(Properties props) { super(props); }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (level instanceof ServerLevel lv) {
                ItemStack odm = OdmManager.findGear(player);
                if (odm == null || odm.getDamageValue() == 0) {
                    player.sendOverlayMessage(Component.literal("\u00a77No ODM gear needs refilling."));
                    return InteractionResult.FAIL;
                }
                odm.setDamageValue(Math.max(0, odm.getDamageValue() - 600));
                player.getItemInHand(hand).consume(1, player);
                lv.playSound(null, player.blockPosition(), SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 1f, 0.8f);
            }
            return InteractionResult.SUCCESS;
        }
    }

    /** Ultrahard blade: marker class so combat code can detect it (Nape strikes, dual wield bonus). */
    public static class Blade extends Item {
        public Blade(Properties props) { super(props); }
    }

    public static class ThunderSpearItem extends Item {
        public ThunderSpearItem(Properties props) { super(props); }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack st = player.getItemInHand(hand);
            if (level instanceof ServerLevel lv) {
                ThunderSpearEntity e = new ThunderSpearEntity(lv, player, st.copyWithCount(1));
                e.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, 2.8f, 0.4f);
                lv.addFreshEntity(e);
                lv.playSound(null, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.2f, 0.6f);
            }
            player.getCooldowns().addCooldown(st, 25);
            if (!player.getAbilities().instabuild) st.shrink(1);
            return InteractionResult.SUCCESS;
        }
    }
}
