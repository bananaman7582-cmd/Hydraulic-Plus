package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.model.geom.ModelPart;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the walk of a model built the way the game builds its own.
 * <p>
 * {@link CitadelAnimation} does this for mods carrying their own model system, and does it by calling
 * the method those models pose themselves with. Mods that use the game's system were left out, not
 * because they cannot be driven but because they are driven differently: the game moved from passing
 * a mob and five numbers to passing a single object describing the mob, and the recorder only knew
 * the older shape. Anything modern therefore recorded nothing, and thirty-one converted models had a
 * shape and no movement at all.
 * <p>
 * They fell back to Bedrock's humanoid animations, which is why a modded zombie walked correctly and
 * a modded fish did not: the fallback moves bones called {@code leftArm} and {@code rightLeg}, and a
 * mob without those stands perfectly still.
 * <p>
 * What is recorded is the same as for any other model - one stride and one moment of standing about -
 * and by the same means: pose the model, read where the bones ended up, and step forward.
 */
public final class VanillaAnimation {
    /**
     * How many poses are recorded across one cycle.
     */
    private static final int FRAMES = 8;

    /**
     * How long the recording lasts, in seconds.
     */
    private static final double LENGTH = 1.0;

    /**
     * How far a mob has to walk before its legs come back round to where they started.
     * <p>
     * The game counts a walk in distance travelled and models turn that into a wave with
     * {@code cos(walkAnimationPos * 0.6662)}, so a whole cycle takes {@code 2π / 0.6662} - about nine
     * and a half. Guessing at two recorded a fifth of a stride and then looped that fragment, which
     * is not a shortened walk but a leg swinging one way and snapping back, over and over.
     */
    private static final float STRIDE = (float) (Math.PI * 2 / 0.6662);

    /**
     * How hard the legs swing, from nothing to the most the game allows.
     * <p>
     * Full strength is not what walking looks like - it is what falling down a cliff looks like. A
     * model multiplies its swing by this, and at one a vanilla leg reaches eighty degrees, which is
     * roughly twice what a mob walking across a field actually does.
     */
    private static final float SWING = 0.7f;

    /**
     * How many ticks of standing about to record, so a breath or a fin's drift is caught whole.
     */
    private static final float IDLE_TICKS = 20.0f;

    /**
     * Ticks in a second, which is the rate a recording has to play back at to look like itself.
     */
    private static final float TICKS_PER_SECOND = 20.0f;

    /**
     * How finely to search for the moment standing about comes round again, in ticks.
     */
    private static final float PROBE = 0.25f;

    /**
     * The shortest idle worth believing. Below this every pose looks like every other and the first
     * match would mean nothing.
     */
    private static final float SHORTEST_IDLE = 2.0f;

    /**
     * How far to keep looking. A sway can take three or four seconds; this leaves room for several
     * times that without searching forever.
     */
    private static final float LONGEST_IDLE = 200.0f;

    private VanillaAnimation() {
    }

    /**
     * Records a model's walk and idle.
     *
     * @param identifier the name to give the animation, without the {@code animation.} prefix
     * @param model the model, taken from the mob's renderer
     * @param state a render state describing the mob, which is what a modern model poses itself from
     * @return the animation, or null if nothing could be recorded
     */
    @Nullable
    public static JsonObject sample(@NotNull String identifier, @NotNull Object model, @Nullable Object state) {
        if (state == null) {
            return null;
        }

        Method setupAnim = setupAnim(model, state);
        Method root = method(model, "root");
        if (setupAnim == null || root == null) {
            return null;
        }

        Map<Object, String> parts;
        try {
            Object top = root.invoke(model);
            if (!(top instanceof ModelPart part)) {
                return null;
            }

            parts = new LinkedHashMap<>();
            collect(part, parts);
        } catch (Throwable t) {
            return null;
        }

        if (parts.isEmpty()) {
            return null;
        }

        // Put the model back to its own rest before measuring what rest means. Read as found, this was
        // whatever pose the last thing to draw the model happened to leave it in, and every frame
        // afterwards was measured against that - so the recording carried the difference between two
        // unrelated poses as though it were movement
        resetAll(parts.keySet());

        Map<Object, float[]> rest = poseOf(parts.keySet());

        Map<String, Map<Double, float[]>> walk = record(setupAnim, model, state, parts, rest, true);
        Map<String, Map<Double, float[]>> idle = record(setupAnim, model, state, parts, rest, false);

        restore(rest, parts.keySet());

        if (empty(walk) && empty(idle)) {
            return null;
        }

        JsonObject animations = new JsonObject();
        if (!empty(walk)) {
            animations.add("animation." + identifier + ".walk", cycle(walk, lengthOf(walk)));
        }

        if (!empty(idle)) {
            animations.add("animation." + identifier + ".idle", cycle(idle, lengthOf(idle)));
        }

        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.8.0");
        file.add("animations", animations);
        return file;
    }

    /**
     * Steps the model through a cycle, writing down every bone that moves.
     */
    @Nullable
    private static Map<String, Map<Double, float[]>> record(@NotNull Method setupAnim, @NotNull Object model,
                                                            @NotNull Object state, @NotNull Map<Object, String> parts,
                                                            @NotNull Map<Object, float[]> rest, boolean moving) {
        Map<String, Map<Double, float[]>> frames = new LinkedHashMap<>();

        // Standing about turns on a far slower wave than walking - a vanilla sway comes round about
        // every seventy ticks - so a fixed twenty recorded a quarter of it and looped that
        float cycle = moving ? STRIDE : idleCycle(setupAnim, model, state, parts);

        for (int frame = 0; frame < FRAMES; frame++) {
            float through = (float) frame / FRAMES;

            // A walk is counted in how far the mob has travelled; standing about is counted in time,
            // which has to keep moving even when the legs do not
            write(state, "walkAnimationPos", moving ? STRIDE * through : 0.0f);
            write(state, "walkAnimationSpeed", moving ? SWING : 0.0f);
            write(state, "ageInTicks", moving ? through * STRIDE : cycle * through);

            // Facing straight ahead throughout. A model that turns its head towards something has
            // nothing to look at here, and recording it mid-turn would bake that turn into the walk
            write(state, "yRot", 0.0f);
            write(state, "xRot", 0.0f);
            write(state, "bodyRot", 0.0f);

            try {
                resetAll(parts.keySet());
                setupAnim.invoke(model, state);
            } catch (Throwable t) {
                return null;
            }

            double time = (moving ? LENGTH : Math.max(LENGTH, cycle / TICKS_PER_SECOND)) * frame / FRAMES;
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
     * Measures how long a mob takes to come back round to where it started while standing about.
     * <p>
     * Idle motion is a wave like a walk is, only far slower - breathing, a tail drifting, a head
     * settling - and a fixed window recorded a slice of it and then looped the slice. What that
     * reads as is not a shorter idle but an arm setting off to sway and being pulled back before it
     * gets anywhere.
     * <p>
     * Two poses have to match rather than one, since a wave passes its starting value twice per turn:
     * once on the way up and once on the way down. Matching only the first finds the halfway point.
     */
    private static float idleCycle(@NotNull Method setupAnim, @NotNull Object model,
                                   @NotNull Object state, @NotNull Map<Object, String> parts) {
        Map<Object, float[]> start = poseAtAge(setupAnim, model, state, parts, 0.0f);
        Map<Object, float[]> justAfter = poseAtAge(setupAnim, model, state, parts, PROBE);

        if (start == null || justAfter == null) {
            return IDLE_TICKS;
        }

        for (float age = SHORTEST_IDLE; age <= LONGEST_IDLE; age += PROBE) {
            Map<Object, float[]> here = poseAtAge(setupAnim, model, state, parts, age);
            if (here == null || !alike(here, start)) {
                continue;
            }

            Map<Object, float[]> next = poseAtAge(setupAnim, model, state, parts, age + PROBE);
            if (next != null && alike(next, justAfter)) {
                return age;
            }
        }

        return IDLE_TICKS; // nothing repeated in range; a mob that simply stands there
    }

    /**
     * The model posed as it would be after standing about for a while.
     */
    @Nullable
    private static Map<Object, float[]> poseAtAge(@NotNull Method setupAnim, @NotNull Object model,
                                                  @NotNull Object state, @NotNull Map<Object, String> parts,
                                                  float age) {
        write(state, "walkAnimationPos", 0.0f);
        write(state, "walkAnimationSpeed", 0.0f);
        write(state, "ageInTicks", age);
        write(state, "yRot", 0.0f);
        write(state, "xRot", 0.0f);
        write(state, "bodyRot", 0.0f);

        try {
            resetAll(parts.keySet());
            setupAnim.invoke(model, state);
        } catch (Throwable t) {
            return null;
        }

        return poseOf(parts.keySet());
    }

    private static boolean alike(@NotNull Map<Object, float[]> a, @NotNull Map<Object, float[]> b) {
        for (Map.Entry<Object, float[]> entry : a.entrySet()) {
            float[] other = b.get(entry.getKey());
            if (other == null || !same(entry.getValue(), other)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Names every bone in the tree the way the geometry beside it does, so the two agree.
     */
    private static void collect(@NotNull ModelPart part, @NotNull Map<Object, String> into) {
        for (Map.Entry<String, ModelPart> child : childrenOf(part).entrySet()) {
            into.putIfAbsent(child.getValue(), EntityGeometryConverter.boneName(child.getKey()));
            collect(child.getValue(), into);
        }
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> childrenOf(@NotNull ModelPart part) {
        try {
            Field field = ModelPart.class.getDeclaredField("children");
            field.setAccessible(true);

            Object children = field.get(part);
            return children instanceof Map<?, ?> map ? (Map<String, ModelPart>) map : Map.of();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * The method a modern model poses itself with, which takes the render state and nothing else.
     */
    @Nullable
    private static Method setupAnim(@NotNull Object model, @NotNull Object state) {
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals("setupAnim") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(state)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }

        return null;
    }

    @Nullable
    private static Method method(@NotNull Object owner, @NotNull String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }

        return null;
    }

    private static void resetAll(@NotNull Iterable<Object> parts) {
        for (Object part : parts) {
            if (part instanceof ModelPart model) {
                model.resetPose();
            }
        }
    }

    @NotNull
    private static Map<Object, float[]> poseOf(@NotNull Iterable<Object> parts) {
        Map<Object, float[]> pose = new LinkedHashMap<>();
        for (Object part : parts) {
            pose.put(part, rotationOf(part));
        }

        return pose;
    }

    private static void restore(@NotNull Map<Object, float[]> pose, @NotNull Iterable<Object> parts) {
        resetAll(parts);
        for (Map.Entry<Object, float[]> entry : pose.entrySet()) {
            if (entry.getKey() instanceof ModelPart part) {
                part.xRot = entry.getValue()[0];
                part.yRot = entry.getValue()[1];
                part.zRot = entry.getValue()[2];
            }
        }
    }

    @NotNull
    private static float[] rotationOf(@NotNull Object part) {
        if (part instanceof ModelPart model) {
            return new float[] { model.xRot, model.yRot, model.zRot };
        }

        return new float[] { 0.0f, 0.0f, 0.0f };
    }

    private static void write(@NotNull Object state, @NotNull String name, float value) {
        for (Class<?> type = state.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setFloat(state, value);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next class up; a state without this field simply does not read it
            }
        }
    }

    private static boolean same(@NotNull float[] a, @NotNull float[] b) {
        return Math.abs(a[0] - b[0]) < 1.0e-4f
                && Math.abs(a[1] - b[1]) < 1.0e-4f
                && Math.abs(a[2] - b[2]) < 1.0e-4f;
    }

    /**
     * How long a set of recorded frames runs for, taken from the last moment in it.
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

    /**
     * One looping animation built from the recorded poses, closed so it repeats without a jump.
     */
    @NotNull
    private static JsonObject cycle(@NotNull Map<String, Map<Double, float[]>> frames, double length) {
        JsonObject bones = new JsonObject();
        for (Map.Entry<String, Map<Double, float[]>> bone : frames.entrySet()) {
            JsonObject rotation = new JsonObject();
            for (Map.Entry<Double, float[]> frame : bone.getValue().entrySet()) {
                rotation.add(String.valueOf(frame.getKey()), degrees(frame.getValue()));
            }

            // A loop has to end where it began, or the last moment of the cycle sits wherever the
            // recording stopped and the whole thing snaps back when it repeats
            Map.Entry<Double, float[]> first = bone.getValue().entrySet().iterator().next();
            if (first.getKey() == 0.0) {
                rotation.add(String.valueOf(length), degrees(first.getValue()));
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

    @NotNull
    private static JsonArray degrees(@NotNull float[] radians) {
        return BoneRotation.degrees(radians);
    }
}
