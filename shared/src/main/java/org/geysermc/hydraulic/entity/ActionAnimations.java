package org.geysermc.hydraulic.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.GeyserSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays a mob's own animations on Bedrock at the moment it actually performs them.
 * <p>
 * Most of what a mob does can be worked out by the Bedrock client on its own: it knows whether the
 * mob is moving, sitting, in water. What it cannot know is the things that happen <i>to</i> the mob
 * or are decided by the server - the instant a bear swipes, or a moose lowers its head to eat. Those
 * animations are read off the mob and written into the pack, but nothing on the client side would
 * ever start them, so without this they sit there unused and the mob stands still through its own
 * attacks.
 * <p>
 * The server does know. A mob animated this way keeps which animation it is performing as ordinary
 * state, readable here like anything else, so asking each tick gives exactly the moment wanted.
 * <p>
 * Telling Bedrock to play it does not work. There is a packet for precisely that and it was tried at
 * length - animations present in the pack, correctly named, on the right entity, every field copied
 * from Geyser's own working use of it - and nothing ever played. What Bedrock does act on is what it
 * can see for itself, so instead a number is put on the mob saying which animation it is performing,
 * and the pack draws whichever one the number names. See {@link AnimationIndex}.
 * <p>
 * Nothing here is specific to any mod. It looks for the shape of an animated mob rather than for a
 * mod that has them, so any mod whose mobs work this way is carried along without being named.
 */
public final class ActionAnimations {
    /**
     * The interface a mob implements to have animations, matched by name because each mod shades its
     * own copy and so has a different class for it.
     */
    private static final String ANIMATED = "IAnimatedEntity";

    /**
     * Whether a class of mob is animated at all, worked out once per class rather than per mob per
     * tick. Almost every entity on a server is not, and this is what keeps the answer cheap.
     */
    private static final Map<Class<?>, Boolean> ANIMATED_CLASSES = new ConcurrentHashMap<>();

    /**
     * What each animation is called, by the class that declares it. The animations themselves are one
     * set of shared objects per class, so this is read once for each.
     */
    private static final Map<Class<?>, Map<Object, String>> NAMES = new ConcurrentHashMap<>();

    /**
     * The method that says what a mob is doing, by class. A map cannot hold a missing answer, so a
     * class without one is remembered as this rather than looked up again every tick.
     */
    private static final Method MISSING = missingMethod();

    private static final Map<Class<?>, Method> GETTERS = new ConcurrentHashMap<>();

    /**
     * The number each mob is currently showing, so that only a change is sent. Rebuilt every tick
     * from the mobs that are actually there, which is what keeps mobs that have died or been unloaded
     * from accumulating here.
     */
    private static Map<Integer, Integer> showing = new HashMap<>();

    /**
     * The flags a kind of mob keeps, and the pose each one turns on, worked out once per class.
     */
    private static final Map<Class<?>, Map<String, java.lang.reflect.Field>> STATE_FLAGS = new ConcurrentHashMap<>();

    /**
     * Set once something here fails in a way that would fail every tick, so a broken setup costs one
     * complaint rather than one per tick forever.
     */
    private static boolean disabled;

    /**
     * How many ticks may fail before this gives up for good. A handful of failures is a mob caught
     * mid-removal; a steady stream is something structurally wrong, and running it every tick anyway
     * would be a cost paid forever for nothing.
     */
    private static final int GIVE_UP = 20;

    private static int failures;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Animations already reported, so the log gets one line each rather than one per performance.
     */
    private static final java.util.Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private ActionAnimations() {
    }

    /**
     * Any method at all, used only as a marker for "this class has not got one".
     */
    @NotNull
    private static Method missingMethod() {
        try {
            return Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Object has no hashCode", e);
        }
    }

    /**
     * Looks over every animated mob and starts, on Bedrock, any animation that has just begun.
     */
    public static void tick(@NotNull MinecraftServer server) {
        if (disabled) {
            return;
        }

        List<GeyserSession> sessions = sessions();
        if (sessions == null || sessions.isEmpty()) {
            // Nobody is playing from Bedrock, so there is nothing to tell and no reason to look
            if (!showing.isEmpty()) {
                showing = new HashMap<>();
            }

            return;
        }

        Map<Integer, Integer> wanted = new HashMap<>();

        // This runs on the server thread at the end of every tick, so anything thrown here is thrown
        // by the server itself. Nothing this does is worth a crash - at worst a mob does not play an
        // animation - so a failure gives up on the tick and, if it is the kind that will happen every
        // time, gives up altogether
        try {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    int number = performing(entity);
                    if (number == 0 && !showing.containsKey(entity.getId())) {
                        continue; // doing nothing, and was doing nothing: there is no news to send
                    }

                    wanted.put(entity.getId(), number);
                    if (number == showing.getOrDefault(entity.getId(), 0)) {
                        continue; // already showing this, and saying so again would restart it
                    }

                    show(sessions, entity, number);
                }
            }
        } catch (Throwable t) {
            failures++;
            if (failures >= GIVE_UP) {
                disabled = true;
                LOGGER.warn("Giving up on playing modded animations after {} failed ticks", GIVE_UP, t);
            }

            return;
        }

        failures = 0;
        showing = wanted;
    }

    /**
     * Which of its own animations a mob is performing right now, as the number the pack watches for.
     * <p>
     * Read fresh every tick rather than noticed when it changes, and that is what makes an animation
     * stop as well as start: a mob that has finished its attack reports none, the number falls back
     * to zero, and the pack stops drawing it. Nothing here has to remember how long anything lasts,
     * because the mob is already keeping track of that itself.
     * <p>
     * A held pose wins over a performed animation, since a raccoon that is begging and also blinking
     * is more usefully drawn begging.
     */
    private static int performing(@NotNull Entity entity) {
        // Asked of every entity on the server, every tick, so the cheapest question comes first: two
        // lookups against a class both answer "no" for the pigs and arrows that make up almost all of
        // them, and nothing further is worked out for those
        boolean animated = isAnimated(entity.getClass());
        boolean posed = !flagsOf(entity.getClass()).isEmpty();
        if (!animated && !posed) {
            return 0;
        }

        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (type == null) {
            return 0;
        }

        for (Map.Entry<String, Boolean> state : statesOf(entity).entrySet()) {
            if (Boolean.TRUE.equals(state.getValue())) {
                int number = AnimationIndex.numberOf(type, state.getKey());
                if (number > 0) {
                    return number;
                }
            }
        }

        if (!isAnimated(entity.getClass())) {
            return 0;
        }

        Object animation = animationOf(entity);
        if (animation == null) {
            return 0;
        }

        String name = nameOf(entity, animation);
        return name == null || !BedrockTriggers.isServerDriven(name)
                ? 0
                : AnimationIndex.numberOf(type, name);
    }

    /**
     * Puts the number on the mob for every Bedrock player who can see it.
     */
    private static void show(@NotNull List<GeyserSession> sessions, @NotNull Entity entity, int number) {
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

        int told = 0;
        for (GeyserSession session : sessions) {
            try {
                org.geysermc.geyser.entity.type.Entity translated =
                        session.getEntityCache().getEntityByJavaId(entity.getId());

                // Null simply means this player cannot see the mob, which is most of them most of
                // the time - it is the check for whether this is worth sending at all
                if (translated == null) {
                    continue;
                }

                told++;
                setNumber(translated, number);
            } catch (Throwable t) {
                // One player's session going away mid-tick must not stop the others being told
            }
        }

        // Said once per animation, so a log shows which were reached and how many players saw each
        if (number > 0 && told > 0 && REPORTED.add(type + ":" + number)) {
            LOGGER.info("{} is performing its own animation {}; told {} Bedrock player(s)",
                    type, number, told);
        }
    }

    /**
     * Puts the number of the animation a mob is performing onto the mob, where the pack reads it.
     * <p>
     * Zero is sent as readily as anything else: it is how an animation ends. The pack draws whichever
     * animation the number names and nothing when it names none, so the mob stopping is simply the
     * number going back to zero.
     */
    private static void setNumber(@NotNull org.geysermc.geyser.entity.type.Entity entity, int number) {
        try {
            entity.updatePropertiesBatched(updater ->
                    updater.update(property(entity, AnimationIndex.PROPERTY), number), true);
        } catch (Throwable t) {
            // A mob whose description carries no such number; nothing to set, nothing to play
        }
    }

    /**
     * The named number on a mob's description, as the definition declared it.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> org.geysermc.geyser.api.entity.property.GeyserEntityProperty<T> property(
            @NotNull org.geysermc.geyser.entity.type.Entity entity, @NotNull String name) {
        for (var declared : entity.definition().properties()) {
            if (declared.identifier().toString().equals(name)) {
                return (org.geysermc.geyser.api.entity.property.GeyserEntityProperty<T>) declared;
            }
        }

        return null;
    }

    /**
     * Every Bedrock player currently connected, or null if Geyser is not there to ask.
     */
    @Nullable
    private static List<GeyserSession> sessions() {
        try {
            GeyserImpl geyser = GeyserImpl.getInstance();
            return geyser == null ? null : geyser.getSessionManager().getAllSessions();
        } catch (Throwable t) {
            // Not being able to ask yet is the normal state of affairs, not a fault. Ticking starts
            // with the server and Geyser finishes starting some way after that, so the first several
            // ticks always fail - and giving up on the first one switched the whole thing off before
            // a Bedrock player could possibly have connected, for the rest of the session, silently
            return null;
        }
    }

    /**
     * Which animation a mob is performing, if any.
     */
    @Nullable
    private static Object animationOf(@NotNull Entity entity) {
        Method get = GETTERS.computeIfAbsent(entity.getClass(), ActionAnimations::findGetter);
        if (get == MISSING) {
            return null;
        }

        try {
            return get.invoke(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Finds the method that says what a mob is doing.
     * <p>
     * Looked up once per class and kept. Asking for it by name is not free, and this runs for every
     * animated mob on the server on every tick - which is exactly the shape of thing that is cheap
     * in isolation and expensive where it actually sits.
     */
    @NotNull
    private static Method findGetter(@NotNull Class<?> type) {
        try {
            Method get = type.getMethod("getAnimation");
            get.setAccessible(true);
            return get;
        } catch (Throwable t) {
            return MISSING;
        }
    }

    /**
     * What this animation is called, taken from the field the mob's class keeps it in.
     */
    @Nullable
    private static String nameOf(@NotNull Entity entity, @NotNull Object animation) {
        return NAMES.computeIfAbsent(entity.getClass(), ActionAnimations::namesOf).get(animation);
    }

    /**
     * Reads the names of every animation a class of mob declares.
     * <p>
     * An animation carries a number and a length and no name at all, so the field holding it is the
     * only description there is. It is also what the recording used, which is what makes the name
     * written into the pack and the name looked up here the same one.
     */
    @NotNull
    private static Map<Object, String> namesOf(@NotNull Class<?> type) {
        Map<Object, String> names = new HashMap<>();

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().getSimpleName().equals("Animation")) {
                    continue;
                }

                try {
                    field.setAccessible(true);

                    Object value = field.get(null);
                    if (value == null) {
                        continue;
                    }

                    String name = field.getName().toLowerCase(Locale.ROOT);
                    if (name.startsWith("animation_")) {
                        name = name.substring("animation_".length());
                    }

                    names.putIfAbsent(value, name);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A field that will not be read costs only that one animation
                }
            }
        }

        return names;
    }

    /**
     * Which poses a mob is currently holding, by the name the recording gave each one.
     */
    @NotNull
    private static Map<String, Boolean> statesOf(@NotNull Entity entity) {
        Map<String, Field> flags = flagsOf(entity.getClass());
        if (flags.isEmpty()) {
            return Map.of();
        }

        Map<String, Boolean> held = new HashMap<>();
        for (Map.Entry<String, Field> flag : flags.entrySet()) {
            try {
                Object accessor = flag.getValue().get(null);
                Object value = entity.getEntityData().get(
                        (net.minecraft.network.syncher.EntityDataAccessor<?>) accessor);

                if (value instanceof Boolean on) {
                    held.put(flag.getKey(), on);
                }
            } catch (Throwable t) {
                // A flag that will not be read is a pose that is never played
            }
        }

        return held;
    }

    /**
     * Pairs each of a mob's flags with the pose it turns on.
     * <p>
     * The two are named for the same thing in different tenses: a raccoon keeps {@code BEGGING} beside
     * {@code begProgress}, one saying whether it is begging and the other how far into it the model
     * has got. The recording is named after the number, so a flag is matched to a pose by finding the
     * number whose name it starts with - {@code begging} beginning with {@code beg}.
     * <p>
     * Only flags that pair with a number are kept. A mob has plenty of others - which way it is
     * facing, what colour its collar is - and none of those pose it.
     */
    @NotNull
    private static Map<String, Field> flagsOf(@NotNull Class<?> type) {
        return STATE_FLAGS.computeIfAbsent(type, ActionAnimations::findFlags);
    }

    @NotNull
    private static Map<String, Field> findFlags(@NotNull Class<?> type) {
        Map<String, Field> flags = new HashMap<>();

        // The poses this mob has, named as the recording named them
        Map<String, String> poses = new HashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                String name = field.getName();
                if (field.getType() == float.class && name.endsWith("Progress") && !name.startsWith("prev")) {
                    String pose = name.substring(0, name.length() - "Progress".length()).toLowerCase(Locale.ROOT);
                    poses.putIfAbsent(pose, pose);
                }
            }
        }

        if (poses.isEmpty()) {
            return Map.of();
        }

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().getSimpleName().equals("EntityDataAccessor")) {
                    continue;
                }

                String lowered = field.getName().toLowerCase(Locale.ROOT);
                for (String pose : poses.keySet()) {
                    if (!lowered.startsWith(pose)) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        flags.putIfAbsent(pose, field);
                    } catch (RuntimeException e) {
                        // A flag that will not be read names nothing
                    }

                    break;
                }
            }
        }

        return flags;
    }

    /**
     * Whether mobs of this class carry animations at all.
     */
    private static boolean isAnimated(@NotNull Class<?> type) {
        return ANIMATED_CLASSES.computeIfAbsent(type, ActionAnimations::findsAnimated);
    }

    private static boolean findsAnimated(@NotNull Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> face : current.getInterfaces()) {
                if (face.getSimpleName().equals(ANIMATED)) {
                    return true;
                }
            }
        }

        return false;
    }
}
