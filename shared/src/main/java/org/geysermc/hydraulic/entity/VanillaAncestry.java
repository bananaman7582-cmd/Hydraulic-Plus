package org.geysermc.hydraulic.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out which vanilla creature a modded one is a kind of, by asking Java rather than guessing.
 * <p>
 * A great many mods do not invent a creature so much as vary one: a frozen spider, a snowy creeper,
 * a zombie that lives in the desert. In Java that is written as a subclass, so the mod's spider
 * <i>is</i> a {@code Spider} as far as the game is concerned, and the whole of Bedrock's own spider -
 * its shape, its walk, the way it climbs walls - already fits it exactly.
 * <p>
 * Choosing by {@link net.minecraft.world.entity.MobCategory} could not see any of that. Every
 * hostile creature is a monster, so a modded creeper and a modded spider were both carried across as
 * zombies: the creeper arrived looking like a zombie, and the spider arrived as a zombie wearing a
 * spider's wide, flat hitbox, which is why it looked wrong side up.
 * <p>
 * Nothing here knows about any particular mod. It reads the class the mod itself chose to extend,
 * which is the same answer the mod's author already gave when they wrote it.
 */
public final class VanillaAncestry {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String VANILLA = "minecraft";

    /**
     * Worked out once per kind of creature. The answer cannot change while the server is up, and the
     * work behind it is a registry walk that is not worth repeating for every spawn.
     */
    private static final Map<EntityType<?>, EntityType<?>> RESOLVED = new ConcurrentHashMap<>();

    /**
     * Every vanilla creature by the Java class that implements it. Built once, on the first modded
     * spawn, because there is no way to ask an {@link EntityType} what class it makes without making
     * one.
     */
    private static volatile Map<Class<?>, EntityType<?>> vanillaByClass;

    private VanillaAncestry() {
    }

    /**
     * The vanilla creature a modded one descends from.
     *
     * @param modded the mod's entity type
     * @param server the running server, used to look the spawning entity up and to build the table
     * @param uuid the spawning entity's uuid, which is how its Java class is found
     * @return the nearest vanilla ancestor, or null if it has none worth using
     */
    @Nullable
    public static EntityType<?> standInFor(@NotNull EntityType<?> modded, @Nullable MinecraftServer server,
                                           @NotNull UUID uuid) {
        if (server == null) {
            return null;
        }

        EntityType<?> known = RESOLVED.get(modded);
        if (known != null) {
            return known == modded ? null : known; // itself means "asked before, found nothing"
        }

        Entity entity = server.overworld().getEntityInAnyDimension(uuid);
        if (entity == null) {
            return null; // not resolvable this time; worth trying again on the next spawn
        }

        EntityType<?> found = ancestorOf(entity.getClass(), server.overworld());
        RESOLVED.put(modded, found == null ? modded : found);

        if (found != null) {
            LOGGER.info("{} is a kind of {}, so Bedrock players are shown one",
                    BuiltInRegistries.ENTITY_TYPE.getKey(modded), BuiltInRegistries.ENTITY_TYPE.getKey(found));
        }

        return found;
    }

    /**
     * Walks up from the mod's class until it reaches one the game itself uses.
     */
    @Nullable
    private static EntityType<?> ancestorOf(@NotNull Class<?> type, @NotNull ServerLevel level) {
        Map<Class<?>, EntityType<?>> vanilla = table(level);

        for (Class<?> current = type; current != null && current != Entity.class; current = current.getSuperclass()) {
            EntityType<?> match = vanilla.get(current);
            if (match != null) {
                return match;
            }
        }

        return null; // built straight onto one of the game's abstract creatures; nothing to copy
    }

    /**
     * Every vanilla creature paired with the class that implements it.
     * <p>
     * One of each is built and thrown away, which is the only way to learn what class a type makes.
     * They are never added to a level, so nothing about the world changes; the same is done on the
     * client to read modded models. Anything that will not build is skipped rather than fatal - it
     * costs that one creature as a possible answer, not the whole table.
     */
    @NotNull
    private static Map<Class<?>, EntityType<?>> table(@NotNull ServerLevel level) {
        Map<Class<?>, EntityType<?>> built = vanillaByClass;
        if (built != null) {
            return built;
        }

        synchronized (VanillaAncestry.class) {
            if (vanillaByClass != null) {
                return vanillaByClass;
            }

            Map<Class<?>, EntityType<?>> table = new HashMap<>();
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (key == null || !key.getNamespace().equals(VANILLA)) {
                    continue;
                }

                try {
                    Entity sample = type.create(level, EntitySpawnReason.NATURAL);
                    if (sample != null) {
                        // The first one wins. Several types share a class - every boat is a Boat -
                        // and any of them stands in equally well for something built on it
                        table.putIfAbsent(sample.getClass(), type);
                        sample.discard();
                    }
                } catch (Throwable t) {
                    // A creature that will not be built outside a world is simply not an answer here
                }
            }

            LOGGER.info("Read {} vanilla creature shapes, so a mod's own can be matched to them", table.size());
            vanillaByClass = table;
            return table;
        }
    }
}
