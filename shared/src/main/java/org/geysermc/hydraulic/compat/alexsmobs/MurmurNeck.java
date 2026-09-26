package org.geysermc.hydraulic.compat.alexsmobs;

import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws something between a murmur's head and the body it belongs to.
 * <p>
 * <b>This is a patch for one mod, and is meant to look like one.</b> Everything else in the entity
 * code works from the shape of what a mod provides rather than from knowing which mod it is; this
 * cannot, because what it is standing in for is not data anywhere.
 * <p>
 * A murmur is two entities - a body, and a head that leaves it and flies at you - joined by a neck
 * that exists only as drawing code, worked out each frame from where the two currently are. There is
 * no neck in the model, no bone for it, and nothing in the mod's files describing it, so there is
 * nothing to convert and the head simply floats away unattached.
 * <p>
 * Bedrock will not draw a chain of segments between two entities. It will draw a leash, which is the
 * same idea reduced to its simplest form: a line, from one creature to another, that follows both as
 * they move. It is not the neck - it is thinner, and it hangs - but it says the two are one creature,
 * which is the thing that was missing.
 */
public final class MurmurNeck {
    /**
     * The body class, matched by name for the same reason everything else here is: the mod ships its
     * own copy of everything it depends on, and this is not compiled against it.
     */
    private static final String BODY_CLASS = "EntityMurmur";

    /**
     * How often to say it again, in ticks. A link is sent once and remembered by the client, but a
     * player who arrives later, or one whose head has just been replaced, has never been told - and
     * repeating a cheap thing occasionally is simpler than tracking who knows what.
     */
    private static final int REPEAT = 40;

    /**
     * The method on the body that gives its head, looked up once per class.
     */
    private static final Map<Class<?>, Method> HEADS = new ConcurrentHashMap<>();

    private static final Method MISSING = missingMethod();

    private static int ticks;

    private MurmurNeck() {
    }

    /**
     * Tells Bedrock players which head belongs to which murmur.
     */
    public static void tick(@NotNull MinecraftServer server) {
        if (ticks++ % REPEAT != 0) {
            return;
        }

        try {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (!entity.getClass().getSimpleName().equals(BODY_CLASS)) {
                        continue;
                    }

                    Entity head = headOf(entity);
                    if (head == null || head == entity) {
                        continue;
                    }

                    // The head is the thing on the end of the line, and the body is what holds it
                    ClientboundSetEntityLinkPacket link = new ClientboundSetEntityLinkPacket(head, entity);
                    for (ServerPlayer player : level.players()) {
                        if (isBedrock(player)) {
                            player.connection.send(link);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // A murmur without a neck is the thing this was trying to improve on; it is not worth a
            // single tick of the server, let alone a crash
        }
    }

    /**
     * The head a murmur body currently owns, if it has one.
     */
    @Nullable
    private static Entity headOf(@NotNull Entity body) {
        Method head = HEADS.computeIfAbsent(body.getClass(), MurmurNeck::findHead);
        if (head == MISSING) {
            return null;
        }

        try {
            Object found = head.invoke(body);
            return found instanceof Entity entity ? entity : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @NotNull
    private static Method findHead(@NotNull Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals("getHead") && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }

        return MISSING;
    }

    /**
     * Floodgate gives Bedrock players a UUID whose most significant bits are zero.
     */
    private static boolean isBedrock(@NotNull ServerPlayer player) {
        return player.getUUID().getMostSignificantBits() == 0;
    }

    @NotNull
    private static Method missingMethod() {
        try {
            return Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Object has no hashCode", e);
        }
    }
}
