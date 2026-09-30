package com.aerodynamics4mc.client;

import java.util.List;

public final class ParticleWindRulesContractTest {
    public static void main(String[] args) {
        String flame = "hantonik.fbp.particle.FBPFlameParticle";
        ParticleWindRules defaults = new ParticleWindRules(true, List.of(), List.of());
        check(defaults.allows(flame, true), "Default recognized particles remain enabled");
        check(!defaults.allows("example.CustomParticle", false), "Defaults do not expand particle coverage");
        ParticleWindRules rules = new ParticleWindRules(true, List.of("example.*", flame), List.of("example.Blocked*", flame));
        check(rules.allows("example.CustomParticle", false), "Whitelist supports class globs");
        check(!rules.allows("example.BlockedParticle", true), "Blacklist wins for built-ins");
        check(!rules.allows(flame, false), "Blacklist wins over whitelist");
        check(!rules.allows("examplex.CustomParticle", false), "Dots are literal");
        check(!new ParticleWindRules(false, List.of("*"), List.of()).allows(flame, true), "Global disable");
        check(new ParticleWindRules(true, List.of("example.Particle$Variant"), List.of())
                .allows("example.Particle$Variant", false), "Inner class names are literal");
        try { new ParticleWindRules(true, List.of(""), List.of()); throw new AssertionError("Invalid config accepted"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("Particle wind rule contracts passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
