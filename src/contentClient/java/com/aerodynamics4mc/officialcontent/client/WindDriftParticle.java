package com.aerodynamics4mc.officialcontent.client;

import com.aerodynamics4mc.client.ParticleWindController;
import com.aerodynamics4mc.particle.ModParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
//? <1.21.11 {
/*import net.minecraft.client.particle.ParticleRenderType;
*///?}
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
//? <1.21.11 {
/*import net.minecraft.client.particle.TextureSheetParticle;
*///?}
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

public final class WindDriftParticle extends
        //? >=1.21.11 {
        SingleQuadParticle
        //?} <1.21.11 {
        /*TextureSheetParticle
        *///?}
{
    private static final Profile SAND_PROFILE = new Profile(
            0.78f, 0.66f, 0.43f, 0.34f,
            0.09f, 0.18f,
            28, 48,
            0.62, 0.16, 0.58,
            0.012, 0.0038, 0.0013,
            0.018f
    );
    private static final Profile RED_SAND_PROFILE = new Profile(
            0.70f, 0.42f, 0.30f, 0.32f,
            0.09f, 0.17f,
            26, 44,
            0.58, 0.16, 0.54,
            0.011, 0.0035, 0.0014,
            0.018f
    );
    private static final Profile DIRT_PROFILE = new Profile(
            0.47f, 0.42f, 0.34f, 0.24f,
            0.07f, 0.14f,
            22, 38,
            0.44, 0.14, 0.42,
            0.008, 0.0025, 0.0017,
            0.014f
    );
    private static final Profile SNOW_PROFILE = new Profile(
            0.88f, 0.92f, 0.95f, 0.46f,
            0.06f, 0.13f,
            34, 60,
            0.78, 0.18, 0.66,
            0.014, 0.0045, 0.0008,
            0.010f
    );
    private static final Profile LEAF_PROFILE = new Profile(
            0.45f, 0.58f, 0.22f, 0.42f,
            0.045f, 0.115f,
            30, 58,
            0.74, 0.20, 0.60,
            0.010, 0.0052, 0.0009,
            0.032f
    );
    private static final Profile GRASS_PROFILE = new Profile(
            0.38f, 0.64f, 0.26f, 0.34f,
            0.035f, 0.090f,
            24, 46,
            0.66, 0.22, 0.50,
            0.007, 0.0044, 0.0011,
            0.026f
    );

    private final SpriteSet sprites;
    private final Profile profile;
    private final float rollVelocity;
    private Vec3 windTarget = Vec3.ZERO;
    private int windRefreshTicks;

    private WindDriftParticle(
            ClientLevel world,
            double x,
            double y,
            double z,
            double velocityX,
            double velocityY,
            double velocityZ,
            SpriteSet sprites,
            Profile profile,
            RandomSource random
    ) {
        //? >=1.21.11 {
        super(world, x, y, z, velocityX, velocityY, velocityZ, sprites.get(random));
        //?} <1.21.11 {
        /*super(world, x, y, z, velocityX, velocityY, velocityZ);
        this.setSprite(sprites.get(random));
        *///?}
        this.sprites = sprites;
        this.profile = profile;
        this.xd = velocityX;
        this.yd = velocityY;
        this.zd = velocityZ;
        this.hasPhysics = true;
        this.friction = 0.96f;
        this.gravity = 0.0f;
        this.speedUpWhenYMotionIsBlocked = false;
        this.lifetime = profile.minLifetime() + random.nextInt(profile.maxLifetime() - profile.minLifetime() + 1);
        this.quadSize = Mth.lerp(random.nextFloat(), profile.minSize(), profile.maxSize());
        this.roll = (float) (random.nextFloat() * Math.PI * 2.0);
        this.oRoll = this.roll;
        this.rollVelocity = (random.nextBoolean() ? 1.0f : -1.0f) * profile.rollSpeed() * (0.45f + random.nextFloat() * 0.75f);
        this.windRefreshTicks = random.nextInt(3);
        this.setSize(0.04f, 0.04f);
        this.setColor(profile.red(), profile.green(), profile.blue());
        this.setAlpha(0.0f);
        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        if (this.windRefreshTicks-- <= 0) {
            this.windTarget = ParticleWindController.groundDustTargetVelocity(
                    this.level,
                    this.x,
                    this.y,
                    this.z,
                    this.profile.windCoupling(),
                    this.profile.maxHorizontalSpeed()
            );
            this.windRefreshTicks = 3 + this.random.nextInt(3);
        }

        this.xd = Mth.lerp(this.profile.response(), this.xd, this.windTarget.x);
        this.zd = Mth.lerp(this.profile.response(), this.zd, this.windTarget.z);
        this.yd = Mth.clamp(
                this.yd + (this.random.nextDouble() - 0.45) * this.profile.flutter() - this.profile.gravity(),
                -0.018,
                0.04
        );

        this.move(this.xd, this.yd, this.zd);
        if (this.onGround) {
            this.yd = this.profile.groundLift() * (0.35 + this.random.nextDouble() * 0.65);
            this.xd *= 0.92;
            this.zd *= 0.92;
        } else {
            this.yd *= 0.82;
            this.xd *= this.friction;
            this.zd *= this.friction;
        }

        this.oRoll = this.roll;
        this.roll += this.rollVelocity;
        this.setAlpha(alphaForAge());
        this.setSpriteFromAge(this.sprites);
    }

    //? >=1.21.11 {
    @Override
    protected SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }
    //?} <1.21.11 {
    /*@Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }
    *///?}

    private float alphaForAge() {
        float progress = (float) this.age / (float) this.lifetime;
        float fadeIn = Mth.clamp(this.age / 6.0f, 0.0f, 1.0f);
        float fadeOut = 1.0f - Mth.clamp((progress - 0.62f) / 0.38f, 0.0f, 1.0f);
        return this.profile.maxAlpha() * fadeIn * fadeOut;
    }

    private static Profile profileFor(SimpleParticleType type) {
        if (type == ModParticles.RED_SAND_DUST/*? neoforge{ */.get()/*?} */) {
            return RED_SAND_PROFILE;
        }
        if (type == ModParticles.DIRT_DUST/*? neoforge{ */.get()/*?} */) {
            return DIRT_PROFILE;
        }
        if (type == ModParticles.SNOW_DRIFT/*? neoforge{ */.get()/*?} */) {
            return SNOW_PROFILE;
        }
        if (type == ModParticles.LEAF_MOTE/*? neoforge{ */.get()/*?} */) {
            return LEAF_PROFILE;
        }
        if (type == ModParticles.GRASS_MOTE/*? neoforge{ */.get()/*?} */) {
            return GRASS_PROFILE;
        }
        return SAND_PROFILE;
    }

    private record Profile(
            float red,
            float green,
            float blue,
            float maxAlpha,
            float minSize,
            float maxSize,
            int minLifetime,
            int maxLifetime,
            double windCoupling,
            double response,
            double maxHorizontalSpeed,
            double groundLift,
            double flutter,
            double gravity,
            float rollSpeed
    ) {}

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientLevel world,
                double x,
                double y,
                double z,
                double velocityX,
                double velocityY,
                double velocityZ
                //? >=1.21.11 {
                , RandomSource random
                //?}
        ) {
            //? <1.21.11 {
            /*RandomSource random = RandomSource.create();
            *///?}
            return new WindDriftParticle(
                    world,
                    x,
                    y,
                    z,
                    velocityX,
                    velocityY,
                    velocityZ,
                    this.sprites,
                    profileFor(type),
                    random
            );
        }
    }
}
