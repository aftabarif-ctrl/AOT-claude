package com.aot;

import java.util.function.Function;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class AotRegistry {
    private AotRegistry() {}

    public static Item ODM_GEAR, GAS_CANISTER, BLADE, THUNDER_SPEAR, TITAN_SPIKE,
            ATTACK_SERUM, WAR_HAMMER_SERUM, TITAN_EGG, BOSS_EGG, CRYSTAL_ITEM;
    public static Block CRYSTAL;
    public static EntityType<TitanEntity> TITAN;
    public static EntityType<TitanEntity.Boss> BOSS;
    public static EntityType<ThunderSpearEntity> SPEAR;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(AotMod.ID, path);
    }

    private static <T extends Item> T item(String name, Function<Item.Properties, T> factory, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
    }

    private static <T extends Entity> EntityType<T> entity(String name, EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id(name));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }

    public static void init() {
        // ---- block ----
        ResourceKey<Block> bk = ResourceKey.create(Registries.BLOCK, id("crystal_block"));
        CRYSTAL = Registry.register(BuiltInRegistries.BLOCK, bk, new CrystalBlock(
                BlockBehaviour.Properties.of().setId(bk).strength(4.0f, 6.0f)
                        .lightLevel(s -> 9).sound(SoundType.AMETHYST).noOcclusion()));
        CRYSTAL_ITEM = item("crystal_block", p -> new BlockItem(CRYSTAL, p), new Item.Properties().useBlockDescriptionPrefix());

        // ---- entities ----
        TITAN = entity("titan", EntityType.Builder.of(TitanEntity::new, MobCategory.MONSTER)
                .sized(0.6f, 1.95f).clientTrackingRange(16));
        BOSS = entity("rogue_titan", EntityType.Builder.of(TitanEntity.Boss::new, MobCategory.MONSTER)
                .sized(0.6f, 1.95f).clientTrackingRange(24).fireImmune());
        SPEAR = entity("thunder_spear", EntityType.Builder.<ThunderSpearEntity>of(ThunderSpearEntity::new, MobCategory.MISC)
                .sized(0.3f, 0.3f).clientTrackingRange(8).updateInterval(5));
        FabricDefaultAttributeRegistry.register(TITAN, TitanEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(BOSS, TitanEntity.createBossAttributes());

        // ---- items ----
        ODM_GEAR = item("odm_gear", AotItems.OdmGear::new, new Item.Properties().durability(1000));
        GAS_CANISTER = item("gas_canister", AotItems.GasCanister::new, new Item.Properties().stacksTo(16));
        BLADE = item("ultrahard_blade", AotItems.Blade::new,
                new Item.Properties().sword(ToolMaterial.IRON, 4.0f, -1.0f));
        THUNDER_SPEAR = item("thunder_spear", AotItems.ThunderSpearItem::new, new Item.Properties().stacksTo(8));
        TITAN_SPIKE = item("titan_spike", AotItems.Spike::new, new Item.Properties().stacksTo(1));
        ATTACK_SERUM = item("attack_titan_serum", p -> new AotItems.Serum(TitanManager.Type.ATTACK, p),
                new Item.Properties().stacksTo(1));
        WAR_HAMMER_SERUM = item("war_hammer_titan_serum", p -> new AotItems.Serum(TitanManager.Type.WAR_HAMMER, p),
                new Item.Properties().stacksTo(1));
        TITAN_EGG = item("titan_spawn_egg", SpawnEggItem::new, new Item.Properties().spawnEgg(TITAN));
        BOSS_EGG = item("rogue_titan_spawn_egg", SpawnEggItem::new, new Item.Properties().spawnEgg(BOSS));

        // ---- creative tab ----
        ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("main"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tabKey, FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.aot"))
                .icon(() -> new ItemStack(ODM_GEAR))
                .displayItems((params, out) -> {
                    out.accept(ATTACK_SERUM);
                    out.accept(WAR_HAMMER_SERUM);
                    out.accept(TITAN_SPIKE);
                    out.accept(ODM_GEAR);
                    out.accept(GAS_CANISTER);
                    out.accept(BLADE);
                    out.accept(THUNDER_SPEAR);
                    out.accept(CRYSTAL_ITEM);
                    out.accept(TITAN_EGG);
                    out.accept(BOSS_EGG);
                }).build());
    }
}
