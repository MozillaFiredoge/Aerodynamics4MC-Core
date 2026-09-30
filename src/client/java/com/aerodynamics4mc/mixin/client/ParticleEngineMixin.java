package com.aerodynamics4mc.mixin.client;

import com.aerodynamics4mc.client.FancyParticleWindCompat;
import net.minecraft.client.particle.Particle;
//? >=1.21.11 {
import net.minecraft.client.particle.ParticleGroup;
//?} else {
/*import net.minecraft.client.particle.ParticleEngine;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? >=1.21.11 {
@Mixin(ParticleGroup.class)
//?} else {
/*@Mixin(ParticleEngine.class)
*///?}
abstract class ParticleEngineMixin {
    @Inject(method = "tickParticle", at = @At("TAIL"))
    private void a4mc$applyAdditionalParticleWind(Particle particle, CallbackInfo ci) {
        FancyParticleWindCompat.afterTick(particle);
    }
}
