package org.geysermc.hydraulic.pack;

import org.geysermc.pack.converter.util.LogListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Passes the converter's messages to the log, saying each thing only once.
 * <p>
 * A missing model is reported per model that wanted it, and mods reference the same few parents over
 * and over - every dyed canvas sign in Farmer's Delight asks for the same sixteen sign templates - so
 * one absent file turns into hundreds of identical complaints. Converting a full set of mods produced
 * over two thousand lines, of which fewer than fifty said anything new, and writing them all out is
 * slow enough to be felt while the server starts.
 * <p>
 * Repeats are counted rather than printed, and {@link #suppressed()} reports the total afterwards so
 * nothing is quietly lost.
 */
public class PackLogListener implements LogListener {
    private final Logger logger;

    // Mods are converted on several threads at once, so both of these are written to concurrently
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final AtomicInteger suppressed = new AtomicInteger();

    public PackLogListener(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void debugUnchecked(@NotNull String s) {
        this.logger.debug(s);
    }

    /**
     * The converter's running commentary, which says the same six things about every mod.
     * <p>
     * Each is announced twice over - beginning and finishing - for every pack converted, so a server
     * with a hundred mods writes six hundred lines that say only that the thing which was always
     * going to happen did. What is worth knowing afterwards is what went wrong and what was skipped,
     * and both are logged elsewhere. Kept at debug rather than dropped, for when a conversion hangs
     * and the question is which mod it stopped on.
     */
    private static final Set<String> ROUTINE = Set.of(
            "Transforming textures",
            "Transformed textures",
            "Packaging pack",
            "Packaged pack",
            "Pack conversion completed",
            "Pack converted"
    );

    @Override
    public void info(@NotNull String s) {
        for (String routine : ROUTINE) {
            if (s.startsWith(routine)) {
                this.logger.debug(s);
                return;
            }
        }

        this.logger.info(s);
    }

    @Override
    public void warn(@NotNull String s) {
        this.once(s, this.logger::warn);
    }

    @Override
    public void error(@NotNull String s) {
        this.once(s, this.logger::error);
    }

    @Override
    public void error(@NotNull String s, @Nullable Throwable throwable) {
        // Anything carrying a stack trace is rare enough to be worth seeing every time
        this.logger.error(s, throwable);
    }

    /**
     * How many messages were held back as repeats.
     */
    public int suppressed() {
        return this.suppressed.get();
    }

    private void once(@NotNull String message, @NotNull Consumer<String> sink) {
        if (this.seen.add(key(message))) {
            sink.accept(message);
        } else {
            this.suppressed.incrementAndGet();
        }
    }

    /**
     * What counts as the same complaint twice.
     * <p>
     * "Could not find parent model X for model Y" is really a complaint about X, so everything from
     * {@code for model} onwards is dropped and the missing parent is reported once however many
     * models wanted it. Any other message is only a repeat if it is identical.
     */
    @NotNull
    private static String key(@NotNull String message) {
        int index = message.indexOf(" for model ");
        return index == -1 ? message : message.substring(0, index);
    }
}
