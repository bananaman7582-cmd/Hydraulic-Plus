package org.geysermc.hydraulic.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agrees a number for each animation a mob performs, so three separate things can talk about the same
 * one.
 * <p>
 * Bedrock will not be told to play an animation - see the note below - but it will read a number off
 * the mob and decide for itself. That means the pack has to say "play the bite when the number is
 * four", the server has to set the number to four when the mob bites, and the description handed to
 * Geyser has to declare that such a number exists. Three places, one meaning, and nothing shared
 * between them but the mob itself.
 * <p>
 * So the number is worked out from the recording, which all three can read: the animations a mob has,
 * sorted, counted from one. Zero means the mob is doing none of them. Sorting is what makes it
 * reliable - the same recording gives the same numbers to the pack built on Tuesday and the server
 * started on Friday.
 * <p>
 * <b>Why not simply tell Bedrock to play it.</b> There is a packet for exactly that, and it does
 * nothing here: sent for animations that were present in the pack, correctly named, on the right
 * entity, with every field copied from Geyser's own working use of it, no animation ever played.
 * What does work is the client deciding for itself from what it knows about the mob, which is what
 * this exists to give it something to know.
 */
public final class AnimationIndex {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The number's name, which the pack reads with {@code query.property} and Geyser declares to the
     * client when the mob first appears.
     */
    public static final String PROPERTY = Constants.MOD_ID + ":animation";

    /**
     * The most animations one mob can have numbers for. A mob with more keeps the first of them,
     * since the alternative is a number the client was never told the range of.
     */
    public static final int LIMIT = 32;

    /**
     * Animations the Bedrock client works out on its own from how the mob is moving. Numbering these
     * would be asking the client to be told something it already knows better.
     */
    private static final List<String> CLIENT_DRIVEN = List.of("walk", "idle");

    private static final Map<Identifier, List<String>> NAMES = new ConcurrentHashMap<>();

    private AnimationIndex() {
    }

    /**
     * The animations this mob performs, in the order their numbers run.
     */
    @NotNull
    public static List<String> forEntity(@NotNull Identifier entity) {
        return NAMES.computeIfAbsent(entity, AnimationIndex::read);
    }

    /**
     * The number standing for one of a mob's animations, or zero if it has no number.
     */
    public static int numberOf(@NotNull Identifier entity, @NotNull String animation) {
        int at = forEntity(entity).indexOf(animation);
        return at < 0 ? 0 : at + 1;
    }

    /**
     * Reads the animations a mob was recorded with, keeping only the ones worth numbering.
     */
    @NotNull
    private static List<String> read(@NotNull Identifier entity) {
        Path file = newest(entity.getNamespace() + "." + entity.getPath() + ".animation.json");
        if (file == null) {
            return List.of();
        }

        JsonObject animations;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            animations = root.getAsJsonObject("animations");
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not read the animations for {}", entity, e);
            return List.of();
        }

        if (animations == null) {
            return List.of();
        }

        List<String> named = new ArrayList<>();
        for (String full : animations.keySet()) {
            int dot = full.lastIndexOf('.');
            String name = dot >= 0 && dot < full.length() - 1 ? full.substring(dot + 1) : full;

            if (!CLIENT_DRIVEN.contains(name) && !named.contains(name)) {
                named.add(name);
            }
        }

        // Sorted so that the order depends on the recording alone. Read straight from the file, the
        // order would be whatever the recorder happened to write, and a pack and a server built from
        // the same mob on different days could disagree about which number meant which animation
        Collections.sort(named);

        return named.size() > LIMIT ? List.copyOf(named.subList(0, LIMIT)) : List.copyOf(named);
    }

    /**
     * The more recently written of the two copies of a mob's recording.
     * <p>
     * The same choice {@link EntityPackModule} makes, for the same reason: one copy sits beside the
     * server and one is written by the player's client, and taking the older of them silently pins
     * everything to whichever was made first.
     */
    @Nullable
    private static Path newest(@NotNull String name) {
        Path best = null;
        long newest = Long.MIN_VALUE;

        for (Path folder : List.of(
                HydraulicImpl.instance().dataFolder(Constants.MOD_ID).resolve("entity-geometry"),
                org.geysermc.hydraulic.entity.client.EntityGeometryDump.directory())) {
            Path file = folder.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }

            try {
                long written = Files.getLastModifiedTime(file).toMillis();
                if (written > newest) {
                    newest = written;
                    best = file;
                }
            } catch (IOException e) {
                if (best == null) {
                    best = file;
                }
            }
        }

        return best;
    }
}
