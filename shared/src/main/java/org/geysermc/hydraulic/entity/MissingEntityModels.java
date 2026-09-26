package org.geysermc.hydraulic.entity;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which mods have entities nobody could find a model for.
 * <p>
 * A mod that defines its entity models in code cannot be read by the server at all, so its mobs stay
 * invisible to Bedrock players until the Java client has been opened once to convert them. That is
 * easy to forget and gives no clue in game - the mob is simply not there - so what is recorded here
 * is repeated to operators as they join rather than left in a log they may never read.
 * <p>
 * Filled in as packs are converted. A mod whose pack was already built and cached is not converted
 * again, so it does not appear here; the case this exists for is the one that matters, which is a
 * mod that was just added.
 */
public final class MissingEntityModels {
    private static final Map<String, List<Identifier>> BY_MOD = new ConcurrentHashMap<>();

    /**
     * Every entity with no model, flattened, for asking about one at a time.
     */
    private static final Set<Identifier> ENTITIES = ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.atomic.AtomicInteger MODELS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger TEXTURES = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger ANIMATIONS = new java.util.concurrent.atomic.AtomicInteger();

    private MissingEntityModels() {
    }

    /**
     * Notes that a mod has entities with no model.
     *
     * @param modId the mod they belong to
     * @param entities the entities with nothing to draw
     */
    public static void record(@NotNull String modId, @NotNull List<Identifier> entities) {
        if (!entities.isEmpty()) {
            BY_MOD.put(modId, List.copyOf(entities));
            ENTITIES.addAll(entities);
        }
    }

    /**
     * Whether this entity is one nothing could be drawn for.
     * <p>
     * Worth asking before sending a Bedrock player to a model that was never written: an entity
     * pointed at a definition the pack does not contain is invisible, which is worse than the vanilla
     * stand-in it would otherwise have been given.
     */
    public static boolean isMissing(@NotNull Identifier entity) {
        return ENTITIES.contains(entity);
    }

    /**
     * Notes that a mod's entities are all accounted for, clearing any earlier complaint about it.
     */
    public static void clear(@NotNull String modId) {
        BY_MOD.remove(modId);
    }

    @NotNull
    public static Collection<String> mods() {
        return BY_MOD.keySet();
    }

    public static int count() {
        return BY_MOD.values().stream().mapToInt(List::size).sum();
    }

    public static boolean isEmpty() {
        return BY_MOD.isEmpty();
    }

    /**
     * What went right, so a player joining is told the state of things rather than only the faults.
     */
    public static void recordConverted(int models, int textures, int animations) {
        MODELS.addAndGet(models);
        TEXTURES.addAndGet(textures);
        ANIMATIONS.addAndGet(animations);
    }

    public static int models() {
        return MODELS.get();
    }

    public static int textures() {
        return TEXTURES.get();
    }

    public static int animations() {
        return ANIMATIONS.get();
    }
}
