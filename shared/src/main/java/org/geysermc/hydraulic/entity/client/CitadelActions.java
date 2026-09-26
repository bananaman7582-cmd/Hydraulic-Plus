package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the animations a mob performs, rather than the one it is always doing.
 * <p>
 * A walk can be recorded by pretending the legs are moving, because a walk is written as arithmetic
 * on how far through a stride the mob is. Everything else it does - biting, eating, sitting up,
 * shaking off water - is not written that way. Those are keyframes, held in the mob's own animation
 * system, and the code that plays them begins by asking the mob which animation it is currently
 * performing. Asked of a mob that has just been made and put nowhere, the answer is none, so that
 * code does nothing at all and the recording captures a creature standing still.
 * <p>
 * The mob can simply be told. Setting the animation and the tick it is up to, then asking the model
 * to pose itself, gives the pose at that moment; stepping the tick through the animation's length
 * gives the whole thing. That turns a keyframe animation nobody could read into one Bedrock can play,
 * and it is the mod's own timing throughout - nothing here decides how long anything takes.
 * <p>
 * The animations are named by the fields holding them, since an animation itself carries only a
 * number. A mob declaring {@code ANIMATION_SWIPE_R} gives Bedrock {@code swipe_r}, which is both a
 * name a person can read and the only clue anywhere to what the animation is <i>for</i> - which is
 * what decides when it should play.
 */
public final class CitadelActions {
    /**
     * The interface a mob implements to have animations at all, matched by name because it is shaded
     * into each mod that uses it and so is a different class in every one.
     */
    private static final String ANIMATED = "IAnimatedEntity";

    /**
     * How many ticks there are in a second, which is what Bedrock measures animations in.
     */
    private static final float TICKS = 20.0f;

    /**
     * The longest animation worth recording, in ticks. Anything beyond half a minute is not an action
     * but a state the mob sits in, and recording it frame by frame would cost more than it is worth.
     */
    private static final int LONGEST = 600;

    /**
     * The most poses to record from one animation. Long animations are sampled rather than copied
     * tick for tick, which keeps a thirty-second one from being written out six hundred times.
     */
    private static final int MOST_FRAMES = 60;

    /**
     * Citadel's animation entry point per model class, so a model without one is not searched for it
     * again on every frame of every animation.
     */
    private static final java.util.Map<Class<?>, Method> ANIMATE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Stands for "this class has not got one", since a map cannot hold a missing answer.
     */
    private static final Method MISSING = missingMethod();

    @NotNull
    private static Method missingMethod() {
        try {
            return Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Object has no hashCode", e);
        }
    }

    private CitadelActions() {
    }

    /**
     * Records every animation a mob knows how to perform.
     *
     * @param model the model to pose, taken from the mob's renderer
     * @param parts the model's bones and the names the geometry gave them
     * @param entity a mob of this kind, which is what holds the animations
     * @return each animation by name, or an empty map if this mob has none to read
     */
    @NotNull
    public static Map<String, JsonObject> extract(@NotNull Object model, @NotNull Map<Object, String> parts,
                                                  @Nullable Object entity) {
        Map<String, JsonObject> found = new LinkedHashMap<>();
        if (entity == null || !implementsAnimated(entity.getClass())) {
            return found;
        }

        Method setupAnim = setupAnim(model);
        Method setAnimation = method(entity, "setAnimation", 1);
        Method setAnimationTick = method(entity, "setAnimationTick", 1);
        Method getAnimations = method(entity, "getAnimations", 0);
        if (setupAnim == null || setAnimation == null || setAnimationTick == null || getAnimations == null) {
            return found;
        }

        Object[] animations;
        try {
            animations = (Object[]) getAnimations.invoke(entity);
        } catch (Throwable t) {
            return found;
        }

        if (animations == null || animations.length == 0) {
            return found;
        }

        Map<Object, String> names = names(entity.getClass());

        // What the model looks like doing nothing, so what is written down is the animation rather
        // than the pose it starts from. Bedrock adds an animation to the bone's own rest position,
        // which the geometry beside this already carries
        Object previous = current(entity);
        Map<Object, float[]> rest = poseAt(setupAnim, model, entity, setAnimation, setAnimationTick,
                null, 0, parts);

        if (rest == null) {
            return found;
        }

        for (Object animation : animations) {
            if (animation == null) {
                continue;
            }

            int duration = duration(animation);
            if (duration <= 0 || duration > LONGEST) {
                continue;
            }

            String name = names.get(animation);
            if (name == null) {
                continue; // an animation the mob never named, which nothing could decide when to play
            }

            JsonObject built = record(setupAnim, model, entity, setAnimation, setAnimationTick,
                    animation, duration, parts, rest);

            if (built != null) {
                found.put(name, built);
            }
        }

        // Put the mob back to whatever it was doing, since it is handed on to other recordings after
        // this one and must not arrive mid-bite
        try {
            setAnimation.invoke(entity, previous);
            setAnimationTick.invoke(entity, 0);
        } catch (Throwable t) {
            // Nothing here owns the mob; it is discarded shortly anyway
        }

        restore(rest);
        return found;
    }

    /**
     * Steps one animation from beginning to end, writing down every bone that moves.
     */
    @Nullable
    private static JsonObject record(@NotNull Method setupAnim, @NotNull Object model, @NotNull Object entity,
                                     @NotNull Method setAnimation, @NotNull Method setAnimationTick,
                                     @NotNull Object animation, int duration, @NotNull Map<Object, String> parts,
                                     @NotNull Map<Object, float[]> rest) {
        int step = Math.max(1, duration / MOST_FRAMES);

        Map<String, Map<Double, float[]>> rotations = new LinkedHashMap<>();
        Map<String, Map<Double, float[]>> positions = new LinkedHashMap<>();

        boolean moved = false;
        for (int tick = 0; tick <= duration; tick += step) {
            Map<Object, float[]> pose = poseAt(setupAnim, model, entity, setAnimation, setAnimationTick,
                    animation, tick, parts);

            if (pose == null) {
                return null; // the animation wanted something of the mob it could not have
            }

            double time = tick / TICKS;
            for (Map.Entry<Object, String> part : parts.entrySet()) {
                float[] now = pose.get(part.getKey());
                float[] was = rest.get(part.getKey());
                if (now == null || was == null) {
                    continue;
                }

                if (differs(now, was, 0)) {
                    rotations.computeIfAbsent(part.getValue(), key -> new LinkedHashMap<>())
                            .put(time, new float[] { now[0] - was[0], now[1] - was[1], now[2] - was[2] });
                    moved = true;
                }

                if (differs(now, was, 3)) {
                    positions.computeIfAbsent(part.getValue(), key -> new LinkedHashMap<>())
                            .put(time, new float[] { now[3] - was[3], now[4] - was[4], now[5] - was[5] });
                    moved = true;
                }
            }
        }

        if (!moved) {
            return null; // an animation that changes nothing about how the mob is drawn
        }

        JsonObject bones = new JsonObject();
        for (Map.Entry<String, Map<Double, float[]>> entry : rotations.entrySet()) {
            bone(bones, entry.getKey()).add("rotation", degrees(entry.getValue()));
        }

        for (Map.Entry<String, Map<Double, float[]>> entry : positions.entrySet()) {
            bone(bones, entry.getKey()).add("position", offsets(entry.getValue()));
        }

        JsonObject built = new JsonObject();

        // These are things a mob does once and finishes, unlike a walk, so they play through and stop
        built.addProperty("loop", false);
        built.addProperty("animation_length", duration / TICKS);
        built.add("bones", bones);
        return built;
    }

    /**
     * Poses the model as it would be at one moment of one animation, and reads where its bones ended up.
     *
     * @param animation the animation to perform, or null for the mob doing nothing
     * @return every bone's rotation and position, or null if the model refused to pose
     */
    @Nullable
    private static Map<Object, float[]> poseAt(@NotNull Method setupAnim, @NotNull Object model,
                                               @NotNull Object entity, @NotNull Method setAnimation,
                                               @NotNull Method setAnimationTick, @Nullable Object animation,
                                               int tick, @NotNull Map<Object, String> parts) {
        try {
            setAnimation.invoke(entity, animation == null ? noAnimation(entity) : animation);
            setAnimationTick.invoke(entity, tick);

            // The mob is standing still throughout: a walk is recorded separately, and mixing the two
            // would write the stride into every animation
            CitadelGeometry.wake(model, entity);
            setupAnim.invoke(model, entity, 0.0f, 0.0f, (float) tick, 0.0f, 0.0f);
        } catch (Throwable t) {
            // Posing a model does two things at once: it plays whatever keyframed animation the mob is
            // performing, and then it does the model's own arithmetic on top - a tail that sways, a
            // head that turns to follow something. Only the second can fail, and when it does the
            // first is lost with it, which is how a tiger with four animations and four poses recorded
            // not one of them.
            //
            // The keyframe half can be asked for on its own. A model that will not pose itself will
            // still play its animations, so what is left is the animation without the flourishes -
            // which is the part being recorded here anyway
            if (!playKeyframes(model, entity, tick)) {
                return null;
            }
        }

        Map<Object, float[]> pose = new LinkedHashMap<>();
        for (Object part : parts.keySet()) {
            pose.put(part, stateOf(part));
        }

        return pose;
    }

    /**
     * Plays just the keyframed half of a model's posing, for models that will not do the whole thing.
     * <p>
     * Citadel splits the work in two: {@code animate} runs the mob's animations, and the model's own
     * {@code setupAnim} calls that and then adds whatever it does itself. Asking for the first alone
     * skips the arithmetic that failed while keeping the animation that was wanted.
     *
     * @return whether the model would play them
     */
    private static boolean playKeyframes(@NotNull Object model, @NotNull Object entity, int tick) {
        Method animate = ANIMATE.computeIfAbsent(model.getClass(), CitadelActions::findAnimate);
        if (animate == MISSING) {
            return false;
        }

        try {
            animate.invoke(model, entity, 0.0f, 0.0f, (float) tick, 0.0f, 0.0f);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Citadel's own entry point for playing a mob's animations, which takes the mob and the state of
     * its stride exactly as posing does.
     */
    @NotNull
    private static Method findAnimate(@NotNull Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("animate") || method.getParameterCount() != 6) {
                    continue;
                }

                Class<?>[] parameters = method.getParameterTypes();
                boolean floats = true;
                for (int i = 1; i < parameters.length; i++) {
                    floats &= parameters[i] == float.class;
                }

                if (floats) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }

        return MISSING;
    }

    /**
     * The value meaning "not doing anything", which every mob shares.
     */
    @Nullable
    private static Object noAnimation(@NotNull Object entity) {
        for (Class<?> type : entity.getClass().getInterfaces()) {
            if (!type.getSimpleName().equals(ANIMATED)) {
                continue;
            }

            try {
                Field field = type.getField("NO_ANIMATION");
                field.setAccessible(true);
                return field.get(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Fall through to the wider search below
            }
        }

        return null;
    }

    /**
     * Names each animation after the field holding it, which is the only description of one anywhere.
     * <p>
     * An animation is a number and a length and nothing else, so a recording of one would be
     * unplayable - there would be no way to say when it should be used. The mod names the fields it
     * keeps them in, though, and {@code ANIMATION_EAT_GRASS} says plainly what it is for.
     */
    @NotNull
    private static Map<Object, String> names(@NotNull Class<?> type) {
        Map<Object, String> names = new LinkedHashMap<>();

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
                    // A field that will not be read names nothing, which only costs that animation
                }
            }
        }

        return names;
    }

    /**
     * A bone's rotation and position together, as six numbers.
     */
    @NotNull
    private static float[] stateOf(@NotNull Object part) {
        return new float[] {
            read(part, "rotateAngleX"), read(part, "rotateAngleY"), read(part, "rotateAngleZ"),
            read(part, "rotationPointX"), read(part, "rotationPointY"), read(part, "rotationPointZ")
        };
    }

    private static void restore(@NotNull Map<Object, float[]> pose) {
        for (Map.Entry<Object, float[]> entry : pose.entrySet()) {
            float[] state = entry.getValue();
            write(entry.getKey(), "rotateAngleX", state[0]);
            write(entry.getKey(), "rotateAngleY", state[1]);
            write(entry.getKey(), "rotateAngleZ", state[2]);
            write(entry.getKey(), "rotationPointX", state[3]);
            write(entry.getKey(), "rotationPointY", state[4]);
            write(entry.getKey(), "rotationPointZ", state[5]);
        }
    }

    private static boolean differs(@NotNull float[] a, @NotNull float[] b, int from) {
        for (int i = from; i < from + 3; i++) {
            if (Math.abs(a[i] - b[i]) >= 1.0e-4f) {
                return true;
            }
        }

        return false;
    }

    /**
     * Turns recorded rotations into the degrees Bedrock reads, turned the way the geometry was.
     */
    @NotNull
    private static JsonObject degrees(@NotNull Map<Double, float[]> frames) {
        JsonObject keyframes = new JsonObject();
        for (Map.Entry<Double, float[]> frame : frames.entrySet()) {
            float[] radians = frame.getValue();

            keyframes.add(String.valueOf(frame.getKey()), BoneRotation.degrees(radians));
        }

        return keyframes;
    }

    /**
     * Turns recorded movement into Bedrock's, which measures the vertical the other way up.
     */
    @NotNull
    private static JsonObject offsets(@NotNull Map<Double, float[]> frames) {
        JsonObject keyframes = new JsonObject();
        for (Map.Entry<Double, float[]> frame : frames.entrySet()) {
            float[] offset = frame.getValue();

            keyframes.add(String.valueOf(frame.getKey()), BoneRotation.offset(offset));
        }

        return keyframes;
    }

    @NotNull
    private static JsonObject bone(@NotNull JsonObject bones, @NotNull String name) {
        if (bones.has(name)) {
            return bones.getAsJsonObject(name);
        }

        JsonObject bone = new JsonObject();
        bones.add(name, bone);
        return bone;
    }

    private static int duration(@NotNull Object animation) {
        try {
            Method duration = animation.getClass().getMethod("getDuration");
            duration.setAccessible(true);
            return (int) duration.invoke(animation);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Nullable
    private static Object current(@NotNull Object entity) {
        try {
            Method get = entity.getClass().getMethod("getAnimation");
            get.setAccessible(true);
            return get.invoke(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean implementsAnimated(@NotNull Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> face : current.getInterfaces()) {
                if (face.getSimpleName().equals(ANIMATED)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Nullable
    private static Method setupAnim(@NotNull Object model) {
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("setupAnim") || method.getParameterCount() != 6) {
                    continue;
                }

                Class<?>[] parameters = method.getParameterTypes();
                boolean floats = true;
                for (int i = 1; i < parameters.length; i++) {
                    floats &= parameters[i] == float.class;
                }

                if (floats) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }

        return null;
    }

    @Nullable
    private static Method method(@NotNull Object owner, @NotNull String name, int parameters) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }

        return null;
    }

    private static float read(@NotNull Object owner, @NotNull String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getFloat(owner);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next class up
            }
        }

        return 0.0f;
    }

    private static void write(@NotNull Object owner, @NotNull String name, float value) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setFloat(owner, value);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next class up
            }
        }
    }
}
