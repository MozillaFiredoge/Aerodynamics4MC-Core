package com.aerodynamics4mc.client;

import com.aerodynamics4mc.ModTemplate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ParticleWindConfig {
    private static volatile ParticleWindRules rules = new ParticleWindRules(true, List.of(), List.of());
    private static volatile ClassValue<Selection> selections = newSelections(rules);

    private ParticleWindConfig() {}

    /** Read once at client initialization. Restart the client after editing the JSON file. */
    public static void load() {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("config/aerodynamics4mc-particles.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Settings settings;
            if (Files.exists(path)) {
                settings = gson.fromJson(Files.readString(path), Settings.class);
                if (settings == null) throw new IllegalArgumentException("Empty particle config");
            } else {
                settings = new Settings();
                Files.createDirectories(path.getParent());
                Files.writeString(path, gson.toJson(settings) + "\n");
            }
            rules = new ParticleWindRules(settings.enabled, settings.whitelist, settings.blacklist);
            selections = newSelections(rules);
        } catch (IOException | RuntimeException exception) {
            ModTemplate.LOGGER.warn("Cannot load particle wind config {}; keeping previous rules", path, exception);
        }
    }

    public static boolean allowsBuiltIn(Object particle) {
        return selections.get(particle.getClass()).builtIn;
    }

    public static boolean allowsAdditional(Object particle) {
        return selections.get(particle.getClass()).additional;
    }

    private static ClassValue<Selection> newSelections(ParticleWindRules current) {
        return new ClassValue<>() {
            @Override protected Selection computeValue(Class<?> type) {
                return new Selection(current.allows(type.getName(), true), current.allows(type.getName(), false));
            }
        };
    }

    private record Selection(boolean builtIn, boolean additional) {}

    private static final class Settings {
        private boolean enabled = true;
        private List<String> whitelist = List.of();
        private List<String> blacklist = List.of();
    }
}
