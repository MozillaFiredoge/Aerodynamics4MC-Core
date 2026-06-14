//? >=1.21.11 {
package com.aerodynamics4mc.mixin.client;

import com.aerodynamics4mc.client.AeroClientMod;
import com.aerodynamics4mc.client.WeatherWindController;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.List;

@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {
    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getRainLevel(F)F"
            )
    )
    private float a4mc$useLocalWeatherRenderIntensity(Level level, float partialTick) {
        return AeroClientMod.getInstance().getLocalWeatherData().localRainLevel(level, level.getRainLevel(partialTick));
    }

    @Redirect(
            method = "tickRainParticles",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"
            )
    )
    private float a4mc$useLocalWeatherParticleIntensity(ClientLevel level, float partialTick) {
        return AeroClientMod.getInstance().getLocalWeatherData().localRainLevel(level, level.getRainLevel(partialTick));
    }

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void a4mc$useLocalWeatherPrecipitation(Level level, BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Biome.Precipitation precipitation = AeroClientMod.getInstance().getLocalWeatherData().precipitationAt(level, pos);
        if (precipitation != null) {
            cir.setReturnValue(precipitation);
        }
    }

    @Inject(method = "renderInstances", at = @At("HEAD"))
    private void a4mc$beginWeatherWind(
            VertexConsumer vertexConsumer,
            List<?> instances,
            Vec3 cameraPosition,
            float columnAlphaBase,
            int radius,
            float intensity,
            CallbackInfo ci
    ) {
        WeatherWindController.beginRender(cameraPosition, columnAlphaBase < 0.9f, intensity);
    }

    @ModifyArgs(
            method = "renderInstances",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            )
    )
    private void a4mc$tiltWeatherVertex(Args args) {
        float x = args.get(0);
        float y = args.get(1);
        float z = args.get(2);
        args.set(0, WeatherWindController.driftedX(x, y));
        args.set(2, WeatherWindController.driftedZ(z, y));
    }
}
//?}
