package com.aerodynamics4mc.vehicle;

import com.aerodynamics4mc.ModTemplate;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
//? neoforge {
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
//?}

public final class ModEntities {
    public static final Identifier SAILBOAT_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "sailboat");

    //? neoforge {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ModTemplate.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<SailboatEntity>> SAILBOAT =
            ENTITY_TYPES.register(SAILBOAT_ID.getPath(), ModEntities::createSailboatType);
    //?}

    //? fabric {
    /*public static final EntityType<SailboatEntity> SAILBOAT = register(
            SAILBOAT_ID,
            EntityType.Builder.<SailboatEntity>of(SailboatEntity::new, MobCategory.MISC)
                    .sized(1.375f, 0.5625f)
                    .clientTrackingRange(10)
                    .updateInterval(3)
    );
    *///?}

    private ModEntities() {
    }

    public static void register(/*? neoforge { */net.neoforged.bus.api.IEventBus modEventBus/*?} */) {
        //? neoforge {
        ENTITY_TYPES.register(modEventBus);
        //?}
    }

    public static EntityType<SailboatEntity> sailboat() {
        return SAILBOAT/*? neoforge{ */.get()/*?} */;
    }

    private static EntityType<SailboatEntity> createSailboatType() {
        EntityType.Builder<SailboatEntity> builder = EntityType.Builder.<SailboatEntity>of(SailboatEntity::new, MobCategory.MISC)
                .sized(1.375f, 0.5625f)
                .clientTrackingRange(10)
                .updateInterval(3);
        //? >=1.21.11 {
        return builder.build(ResourceKey.create(Registries.ENTITY_TYPE, SAILBOAT_ID));
        //?} <1.21.11 {
        /*return builder.build(SAILBOAT_ID.toString());
        *///?}
    }

    //? fabric {
    /*private static <T extends Entity> EntityType<T> register(Identifier id, EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
        EntityType<T> entityType = builder.build(key);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, entityType);
    }
    *///?}
}
