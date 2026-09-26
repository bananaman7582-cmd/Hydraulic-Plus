package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records what a mod's animation code does, so Bedrock can play it back.
 * <p>
 * A model built on Citadel is not animated by data anywhere - each mob has a method that moves its
 * bones a little further every frame, and there is no keyframe table to read. That looked like the
 * end of it: nothing to convert, so nothing to hand over.
 * <p>
 * But that method can be <i>called</i>. Walking it through a stride a step at a time and writing down
 * where every bone ends up turns the code back into exactly what Bedrock wants - a list of poses at
 * points in time. The mod animates the mob once, here, and Bedrock replays the recording.
 * <p>
 * That covers the two things a model does unprompted - walking, and standing about. Everything a mob
 * performs deliberately is held as keyframes rather than arithmetic and is read separately by
 * {@link CitadelActions}, which drives the same code with the mob told what it is doing.
 * <p>
 * What none of it captures is what an animation reads off the world rather than the mob: a head that
 * turns towards whatever it is chasing has nothing to chase here. Those parts stay at rest, which
 * leaves the mob's own movement intact and simply omits the part that was aimed at something.
 */
public final class CitadelAnimation {
    /**
     * How many poses are recorded across one stride. Eight is enough for a walk to read smoothly
     * without the file growing to match; Bedrock eases between them.
     */
    private static final int FRAMES = 8;

    /**
     * How long the recorded stride lasts, in seconds.
     */
    private static final double LENGTH = 1.0;

    /**
     * How far a leg swings through in one stride, in the units the animation counts in. A walk cycle
     * repeats every 2π of limb swing, so a full turn covers exactly one stride.
     */
    private static final float STRIDE = (float) (Math.PI * 2);

    /**
     * How many ticks of standing about are recorded. Idle motion is usually built on a slow wave, so
     * a full second of them covers a whole breath rather than a slice of one.
     */
    private static final float IDLE_TICKS = 20.0f;

    /**
     * Ticks in a second, which is the rate a recording has to be played back at to look like itself.
     */
    private static final float TICKS_PER_SECOND = 20.0f;

    /**
     * How finely the walk is searched for the point it repeats. Small enough to land close to the
     * real cycle, large enough that the search is a few hundred poses rather than thousands.
     */
    private static final float PROBE = 0.05f;

    /**
     * The shortest cycle worth believing. Anything under this is the model barely moving, where every
     * pose looks like every other and the first "match" would be meaningless.
     */
    private static final float SHORTEST_STRIDE = 0.5f;

    /**
     * How far to keep looking before giving up. Vanilla's walk comes round at about nine and a half;
     * this leaves room for a mob several times slower without searching forever.
     */
    private static final float LONGEST_STRIDE = 64.0f;

    /**
     * How far to keep looking for the moment standing about comes round again, in ticks. A sway can
     * take three or four seconds, so this leaves room for one several times slower than that.
     */
    private static final float LONGEST_IDLE = 200.0f;

    /**
     * How close two poses have to be to count as the same, in radians. Loose enough to survive the
     * rounding in a wave, tight enough not to call a quarter turn a whole one.
     */
    private static final float MATCH = 0.02f;

    /**
     * A real mob of each kind, kept only so the animation has something to read while it is driven.
     * <p>
     * Nearly every model asks the mob something - whether it is swimming, angry, sitting - and with
     * nothing to ask it simply throws, which is why driving these without one recorded not a single
     * walk. The mob is never put in the world; it exists to be a question's answer.
     */
    private static final Map<Object, Object> SUBJECTS = new LinkedHashMap<>();

    /**
     * A render state for each kind of mob, kept for the models that pose themselves from one rather
     * than from the mob directly.
     */
    private static final Map<Object, Object> STATES = new LinkedHashMap<>();

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Models already complained about, so a hundred mobs of one kind cost one line.
     */
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CitadelAnimation() {
    }

    /**
     * Notes a mob to drive this kind of model with.
     */
    public static void remember(@NotNull Object type, @NotNull Object entity) {
        SUBJECTS.put(type, entity);
    }

    /**
     * Notes the render state a modern model would be posed from.
     */
    public static void rememberState(@NotNull Object type, @Nullable Object state) {
        if (state != null) {
            STATES.put(type, state);
        }
    }

    /**
     * The render state for this kind of mob, if one was built.
     */
    @Nullable
    public static Object stateFor(@NotNull Object type) {
        return STATES.get(type);
    }

    /**
     * The mob to drive a model of this kind with, if one was built.
     */
    @Nullable
    public static Object subjectFor(@NotNull Object type) {
        return SUBJECTS.get(type);
    }

    /**
     * Records a mob's walk into a Bedrock animation.
     *
     * @param identifier the name to give the animation, without the {@code animation.} prefix
     * @param model the model to drive, taken from the entity's renderer
     * @param parts the model's bones and the names the geometry gave them
     * @return the animation, or null if this model cannot be driven without a live mob
     */
    @Nullable
    public static JsonObject sample(@NotNull String identifier, @NotNull Object model,
                                    @NotNull Map<Object, String> parts, @Nullable Object subject) {
        Method setupAnim = legacySetupAnim(model);
        if (setupAnim == null) {
            return null;
        }

        // Nothing to record without a mob, and nothing worth saying about it either. This runs twice:
        // once when resources load, where there are models but no world to build a mob in, and again
        // when a world opens, where there are both. Only the second can record anything. Letting the
        // first fail loudly filled the log with a complaint per model that read exactly like a real
        // failure - and, being said once per model, it then hid the real one when it came
        if (subject == null) {
            return null;
        }

        // Posed once first, so that resting here means the same thing it meant when the shape was
        // written. Bedrock adds an animation on top of the bone's own rotation, so the two have to
        // agree on what the bone's own rotation is: measure the movement from an unposed model while
        // the geometry was taken from a posed one and every frame carries the difference between them
        // as a permanent lean. A raccoon whose model pitches its body ten degrees forward the moment
        // it is asked to draw spent every frame of its walk ten degrees further up than it should be
        CitadelGeometry.settle(model, subject);

        // Where every bone rests, so what is recorded is the movement rather than the pose
        Map<Object, float[]> rest = pose(parts.keySet());

        // Two recordings from the same code, differing only in what it is told. Told the limbs are
        // swinging, it walks; told they are still while time passes, it does whatever it does when
        // standing about - breathing, a tail swaying, wings beating. For anything that flies the
        // second is the one that matters, since it never stops doing it.
        //
        // Either may come back with nothing, which is not the end of the mob: one whose walk cannot
        // be driven may still have a whole set of keyframed animations to read, and giving up at the
        // first refusal threw those away without ever looking
        Map<String, Map<Double, float[]>> walk = record(setupAnim, model, subject, parts, rest, true);
        Map<String, Map<Double, float[]>> idle = record(setupAnim, model, subject, parts, rest, false);

        // Everything else the mob does. A walk and a stand are the two things its code will do
        // unasked; biting, eating and sitting up are held as keyframes it only performs when told to,
        // and are read by telling it to
        Map<String, JsonObject> actions = CitadelActions.extract(model, parts, subject);

        // And the poses it holds rather than performs - begging, clapping, sitting up. Those are
        // neither arithmetic nor keyframes but a number that slides while the mob wants to, which is
        // a third thing again and was being missed entirely
        actions.putAll(CitadelStates.extract(model, parts, subject));

        // Put the model back the way it was found, so the geometry written beside this is the mob
        // standing still rather than mid-stride
        restore(rest);

        if (empty(walk) && empty(idle) && actions.isEmpty()) {
            return null; // nothing moved at all; a still model says the same thing more cheaply
        }

        return build(identifier, walk, idle, actions);
    }

    /**
     * Drives the animation through one cycle and writes down what each bone does.
     *
     * @param moving whether to tell it the mob is walking, or standing still while time passes
     * @return each bone that moved and where it was at each moment, or null if it refused to run
     */
    @Nullable
    private static Map<String, Map<Double, float[]>> record(@NotNull Method setupAnim, @NotNull Object model,
                                                            @Nullable Object subject, @NotNull Map<Object, String> parts,
                                                            @NotNull Map<Object, float[]> rest, boolean moving) {
        Map<String, Map<Double, float[]>> frames = new LinkedHashMap<>();

        // How far the mob has to walk before it is back where it started. Assuming this rather than
        // measuring it is what made walks jerk: a stride is written as a wave over how far the legs
        // have swung, and how fast that wave turns is the model's own choice. Take a fixed guess and
        // the recording ends partway through the cycle, so every repeat jumps from mid-stride back to
        // the beginning
        // Standing about needs measuring every bit as much as walking does, and for the same reason.
        // The wave a mob breathes or sways on turns far more slowly than the one it walks on - a
        // vanilla sway comes round about every seventy ticks - so the twenty that were recorded were
        // a quarter of it, looped. That is not a shortened idle; it is an arm setting off to sway and
        // being yanked back before it arrives, over and over
        float cycle = cycleOf(setupAnim, model, subject, parts, moving);

        // How long the recording should take to play, in seconds. A walk is given a second because
        // that is roughly what a stride takes and Bedrock has nothing to scale it against. Standing
        // about is real time and has to be honoured: a sway that takes three and a half seconds and
        // is played in one is not a faster sway, it is a mob twitching. Measuring the cycle only
        // helped once the recording was also allowed to last as long as the cycle does
        double length = moving ? LENGTH : Math.max(LENGTH, cycle / TICKS_PER_SECOND);

        for (int frame = 0; frame < FRAMES; frame++) {
            float through = cycle * frame / FRAMES;

            // A stride is counted in limb swing; standing about is counted in ticks, so the age has
            // to keep moving even when the legs do not
            float swing = moving ? through : 0.0f;
            float amount = moving ? 1.0f : 0.0f;
            float age = through;

            try {
                CitadelGeometry.wake(model, subject);
                setupAnim.invoke(model, subject, swing, amount, age, 0.0f, 0.0f);
            } catch (Throwable t) {
                // Said once per model, and worth saying. A model that will not pose itself loses its
                // walk and its idle entirely, which in game is a mob that slides about without moving
                // a limb - and from the outside that is indistinguishable from a mob that simply has
                // no walk to record. Naming it is the difference between the two
                if (subject != null && REPORTED.add(model.getClass().getName())) {
                    LOGGER.warn("{} would not pose itself, so it has no walk or idle. Its keyframed "
                            + "animations are still read", model.getClass().getSimpleName(),
                            t instanceof java.lang.reflect.InvocationTargetException wrapped
                                    ? wrapped.getCause() : t);
                }

                return null;
            }

            double time = length * frame / FRAMES;
            for (Map.Entry<Object, String> entry : parts.entrySet()) {
                float[] now = rotationOf(entry.getKey());
                float[] was = rest.get(entry.getKey());
                if (was == null || same(now, was)) {
                    continue;
                }

                frames.computeIfAbsent(entry.getValue(), name -> new LinkedHashMap<>())
                        .put(time, new float[] { now[0] - was[0], now[1] - was[1], now[2] - was[2] });
            }
        }

        return frames;
    }

    /**
     * The animation method every Citadel model has, which takes the mob and the state of its stride.
     */
    @Nullable
    private static Method legacySetupAnim(@NotNull Object model) {
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

    /**
     * Measures how far a mob's legs have to swing before its walk comes back round.
     * <p>
     * A walk is arithmetic on how far the limbs have swung - a wave, near enough - and the model
     * decides how fast that wave turns. Vanilla's is about a tenth of a turn per unit swung, Citadel
     * lets each mob pick its own, and a mob with a long slow gait can take several times as much.
     * There is nothing to read it off, so it is found by walking the mob and watching for the moment
     * it stands as it did at the start.
     * <p>
     * Two poses have to match, not one. A wave passes through its starting value twice per turn -
     * once going up, once coming down - so a single match finds the halfway point and records a
     * stride where the legs finish crossed.
     *
     * @return the swing a whole cycle takes, or a sensible guess if none was found
     */
    private static float cycleOf(@NotNull Method setupAnim, @NotNull Object model, @Nullable Object subject,
                                 @NotNull Map<Object, String> parts, boolean moving) {
        float fallback = moving ? STRIDE : IDLE_TICKS;

        Map<Object, float[]> start = poseDriven(setupAnim, model, subject, parts, 0.0f, moving);
        Map<Object, float[]> justAfter = poseDriven(setupAnim, model, subject, parts, PROBE, moving);

        if (start == null || justAfter == null) {
            return fallback;
        }

        float longest = moving ? LONGEST_STRIDE : LONGEST_IDLE;
        for (float at = SHORTEST_STRIDE; at <= longest; at += PROBE) {
            Map<Object, float[]> here = poseDriven(setupAnim, model, subject, parts, at, moving);
            if (here == null || !alike(here, start)) {
                continue;
            }

            Map<Object, float[]> next = poseDriven(setupAnim, model, subject, parts, at + PROBE, moving);
            if (next != null && alike(next, justAfter)) {
                return at;
            }
        }

        return fallback; // nothing repeated in range; a mob whose motion does not cycle at all
    }

    /**
     * The model posed mid-stride, with the legs told they are swinging.
     */
    @Nullable
    private static Map<Object, float[]> poseDriven(@NotNull Method setupAnim, @NotNull Object model,
                                                   @Nullable Object subject, @NotNull Map<Object, String> parts,
                                                   float at, boolean moving) {
        try {
            CitadelGeometry.wake(model, subject);
            setupAnim.invoke(model, subject, moving ? at : 0.0f, moving ? 1.0f : 0.0f, at, 0.0f, 0.0f);
        } catch (Throwable t) {
            return null;
        }

        return pose(parts.keySet());
    }

    /**
     * Whether two poses are close enough to call the same.
     */
    private static boolean alike(@NotNull Map<Object, float[]> a, @NotNull Map<Object, float[]> b) {
        for (Map.Entry<Object, float[]> entry : a.entrySet()) {
            float[] other = b.get(entry.getKey());
            if (other == null) {
                return false;
            }

            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(entry.getValue()[axis] - other[axis]) > MATCH) {
                    return false;
                }
            }
        }

        return true;
    }

    @NotNull
    private static Map<Object, float[]> pose(@NotNull Iterable<Object> parts) {
        Map<Object, float[]> pose = new LinkedHashMap<>();
        for (Object part : parts) {
            pose.put(part, rotationOf(part));
        }

        return pose;
    }

    private static void restore(@NotNull Map<Object, float[]> pose) {
        for (Map.Entry<Object, float[]> entry : pose.entrySet()) {
            write(entry.getKey(), "rotateAngleX", entry.getValue()[0]);
            write(entry.getKey(), "rotateAngleY", entry.getValue()[1]);
            write(entry.getKey(), "rotateAngleZ", entry.getValue()[2]);
        }
    }

    @NotNull
    private static float[] rotationOf(@NotNull Object part) {
        return new float[] {
            read(part, "rotateAngleX"),
            read(part, "rotateAngleY"),
            read(part, "rotateAngleZ")
        };
    }

    private static boolean same(@NotNull float[] a, @NotNull float[] b) {
        return Math.abs(a[0] - b[0]) < 1.0e-4f
                && Math.abs(a[1] - b[1]) < 1.0e-4f
                && Math.abs(a[2] - b[2]) < 1.0e-4f;
    }

    /**
     * Turns the recorded poses into the shape Bedrock reads.
     */
    @NotNull
    private static JsonObject build(@NotNull String identifier, @Nullable Map<String, Map<Double, float[]>> walk,
                                    @Nullable Map<String, Map<Double, float[]>> idle,
                                    @NotNull Map<String, JsonObject> actions) {
        JsonObject animations = new JsonObject();
        if (!empty(walk)) {
            animations.add("animation." + identifier + ".walk", cycle(walk, lengthOf(walk)));
        }

        if (!empty(idle)) {
            animations.add("animation." + identifier + ".idle", cycle(idle, lengthOf(idle)));
        }

        // Named by the mod rather than by us, so what arrives in the pack is "swipe_r" and "eat_grass"
        // - which is the only thing that makes it possible to decide when to play them
        for (Map.Entry<String, JsonObject> action : actions.entrySet()) {
            animations.add("animation." + identifier + "." + action.getKey(), action.getValue());
        }

        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.8.0");
        file.add("animations", animations);
        return file;
    }

    /**
     * One looping animation built from a set of recorded poses.
     */
    @NotNull
    private static JsonObject cycle(@NotNull Map<String, Map<Double, float[]>> frames, double length) {
        JsonObject bones = new JsonObject();
        for (Map.Entry<String, Map<Double, float[]>> bone : frames.entrySet()) {
            JsonObject rotation = new JsonObject();
            for (Map.Entry<Double, float[]> frame : bone.getValue().entrySet()) {
                float[] radians = frame.getValue();

                // Radians to degrees, turned the same way the geometry was
                rotation.add(String.valueOf(frame.getKey()), BoneRotation.degrees(radians));
            }

            // A loop has to end where it began. Without a pose at the very end matching the one at the
            // start, the last moment of the cycle sits wherever the recording stopped and the whole
            // thing snaps back when it repeats - which reads as an animation that jerks rather than
            // one that runs on
            Map.Entry<Double, float[]> first = bone.getValue().entrySet().iterator().next();
            if (first.getKey() == 0.0) {
                rotation.add(String.valueOf(length), BoneRotation.degrees(first.getValue()));
            }

            JsonObject moved = new JsonObject();
            moved.add("rotation", rotation);
            bones.add(bone.getKey(), moved);
        }

        JsonObject animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.addProperty("animation_length", length);
        animation.add("bones", bones);
        return animation;
    }

    /**
     * How long a set of recorded frames runs for, taken from the last moment in it. The frames were
     * keyed in seconds as they were recorded, so they already know.
     */
    private static double lengthOf(@Nullable Map<String, Map<Double, float[]>> frames) {
        double last = 0.0;
        if (frames != null) {
            for (Map<Double, float[]> bone : frames.values()) {
                for (Double at : bone.keySet()) {
                    last = Math.max(last, at);
                }
            }
        }

        return last <= 0.0 ? LENGTH : last * FRAMES / (FRAMES - 1.0);
    }

    private static boolean empty(@Nullable Map<String, Map<Double, float[]>> frames) {
        return frames == null || frames.isEmpty();
    }

    private static float read(@NotNull Object owner, @NotNull String name) {
        try {
            var field = owner.getClass().getField(name);
            return field.getFloat(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0.0f;
        }
    }

    private static void write(@NotNull Object owner, @NotNull String name, float value) {
        try {
            owner.getClass().getField(name).setFloat(owner, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Leaving it as the animation left it is no worse than failing here
        }
    }
}
