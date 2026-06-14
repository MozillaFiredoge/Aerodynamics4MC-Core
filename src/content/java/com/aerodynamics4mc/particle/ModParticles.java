package com.aerodynamics4mc.particle;

import com.aerodynamics4mc.ModTemplate;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
//? neoforge {
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
//?}

public final class ModParticles {
    public static final Identifier SAND_DUST_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "sand_dust");
    public static final Identifier RED_SAND_DUST_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "red_sand_dust");
    public static final Identifier DIRT_DUST_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "dirt_dust");
    public static final Identifier SNOW_DRIFT_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "snow_drift");
    public static final Identifier LEAF_MOTE_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "leaf_mote");
    public static final Identifier GRASS_MOTE_ID = Identifier.fromNamespaceAndPath(ModTemplate.MOD_ID, "grass_mote");

    //? neoforge {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, ModTemplate.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SAND_DUST =
            PARTICLE_TYPES.register(SAND_DUST_ID.getPath(), ModParticles::simple);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RED_SAND_DUST =
            PARTICLE_TYPES.register(RED_SAND_DUST_ID.getPath(), ModParticles::simple);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DIRT_DUST =
            PARTICLE_TYPES.register(DIRT_DUST_ID.getPath(), ModParticles::simple);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SNOW_DRIFT =
            PARTICLE_TYPES.register(SNOW_DRIFT_ID.getPath(), ModParticles::simple);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> LEAF_MOTE =
            PARTICLE_TYPES.register(LEAF_MOTE_ID.getPath(), ModParticles::simple);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> GRASS_MOTE =
            PARTICLE_TYPES.register(GRASS_MOTE_ID.getPath(), ModParticles::simple);
    //?}

    //? fabric {
    /*public static final SimpleParticleType SAND_DUST = register(SAND_DUST_ID);
    public static final SimpleParticleType RED_SAND_DUST = register(RED_SAND_DUST_ID);
    public static final SimpleParticleType DIRT_DUST = register(DIRT_DUST_ID);
    public static final SimpleParticleType SNOW_DRIFT = register(SNOW_DRIFT_ID);
    public static final SimpleParticleType LEAF_MOTE = register(LEAF_MOTE_ID);
    public static final SimpleParticleType GRASS_MOTE = register(GRASS_MOTE_ID);
    *///?}

    private ModParticles() {}

    public static void register(/*? neoforge { */net.neoforged.bus.api.IEventBus modEventBus/*?} */) {
        //? neoforge {
        PARTICLE_TYPES.register(modEventBus);
        //?}
    }

    private static SimpleParticleType simple() {
        return new SimpleParticleType(false) {};
    }

    //? fabric {
    /*private static SimpleParticleType register(Identifier id) {
        return Registry.register(BuiltInRegistries.PARTICLE_TYPE, id, simple());
    }
    *///?}
}
