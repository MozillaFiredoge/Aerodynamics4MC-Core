package com.aerodynamics4mc.client;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Blacklist wins; whitelist adds classes to the built-in selection. Patterns use '*'. */
public final class ParticleWindRules {
    private final boolean enabled;
    private final List<Pattern> whitelist;
    private final List<Pattern> blacklist;

    public ParticleWindRules(boolean enabled, List<String> whitelist, List<String> blacklist) {
        this.enabled = enabled;
        this.whitelist = compile(whitelist);
        this.blacklist = compile(blacklist);
    }

    public boolean allows(String className, boolean builtIn) {
        return enabled && !matches(blacklist, className) && (builtIn || matches(whitelist, className));
    }

    private static boolean matches(List<Pattern> patterns, String name) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(name).matches());
    }

    private static List<Pattern> compile(List<String> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        return patterns.stream().map(value -> {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Empty particle class pattern");
            return Pattern.compile(Arrays.stream(value.split("\\*", -1))
                    .map(Pattern::quote).collect(Collectors.joining(".*")));
        }).toList();
    }
}
