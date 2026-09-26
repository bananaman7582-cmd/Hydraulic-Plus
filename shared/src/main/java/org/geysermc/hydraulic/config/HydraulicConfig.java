package org.geysermc.hydraulic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The settings an operator can change, kept in {@code config/hydraulic/config.json}.
 * <p>
 * Written back out after being read, so a file that is missing an option gains it with its default
 * rather than silently going without - which also means the file on disk always shows everything
 * there is to change, instead of only what someone happened to write in it.
 */
public final class HydraulicConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static HydraulicConfig instance;

    /**
     * Whether to keep Polymer's packets away from Bedrock players.
     * <p>
     * Polymer and Hydraulic solve the same problem for different audiences: Polymer dresses modded
     * content up as vanilla so an unmodified <i>Java</i> client can see it, and Hydraulic converts it
     * properly for Bedrock. A server running both sends a Bedrock player both answers, and the two
     * disagree about what a thing is.
     * <p>
     * See {@link org.geysermc.hydraulic.config.PolymerFilter} for what this does and, importantly,
     * what it does not.
     */
    public boolean hidePolymerFromBedrock = true;

    /**
     * The settings in force, read from disk the first time they are asked for.
     */
    @NotNull
    public static HydraulicConfig get() {
        if (instance == null) {
            instance = load();
        }

        return instance;
    }

    @NotNull
    private static HydraulicConfig load() {
        Path file = file();

        HydraulicConfig config = new HydraulicConfig();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                HydraulicConfig read = GSON.fromJson(reader, HydraulicConfig.class);
                if (read != null) {
                    config = read;
                }
            } catch (IOException | RuntimeException e) {
                // A config nobody can read is not worth refusing to start over; the defaults are the
                // behaviour anyone who has not edited it was getting anyway
                LOGGER.warn("Could not read {}, carrying on with the default settings", file, e);
            }
        }

        config.save(file);
        return config;
    }

    private void save(@NotNull Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }

    @NotNull
    private static Path file() {
        return HydraulicImpl.instance().dataFolder(Constants.MOD_ID).resolve("config.json");
    }
}
