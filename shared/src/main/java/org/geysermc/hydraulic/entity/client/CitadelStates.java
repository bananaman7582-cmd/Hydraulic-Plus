package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records the poses a mob holds while it is doing something, as opposed to the ones it moves through.
 * <p>
 * There turned out to be three ways a mob is animated, not two. A walk is arithmetic on how far the
 * legs have swung; an attack is keyframes in the mob's animation system; and this is the third - a
 * mob raises itself to beg, or claps the symbols hanging off it, and neither is an animation at all.
 * It is a number that slides from nothing to fully-doing-it while the mob wants to, slides back when
 * it stops, and a model that poses itself from wherever that number currently is.
 * <p>
 * Nothing about that is readable as an animation, which is why a raccoon that visibly begs reported
 * having only an attack, and a skreecher that visibly claps reported having nothing whatsoever. What
 * is readable is the pose at the end: set the number to fully-doing-it, ask the model to pose itself,
 * and write down where the bones went. That is one pose rather than a sequence, so Bedrock is given
 * the mob easing into it and holding it - which is what the sliding number was producing anyway.
 * <p>
 * The names come from the fields, as everywhere else here: {@code begProgress} gives {@code beg}, and
 * the server plays it when it sees the mob begin begging.
 */
public final class CitadelStates {
    /**
     * How long Bedrock takes to ease into the pose, in seconds. The number this stands in for slides
     * over about a quarter of a second in Java, which is close enough to copy.
     */
    private static final double EASE = 0.25;

    /**
     * How far the number goes when the mob is fully doing the thing. Citadel counts these from zero
     * to five, and a model divides by five to get a fraction - so five is "all the way".
     */
    private static final float FULLY = 5.0f;

    /**
     * Fields that look like one of these but are not: the previous frame's value, kept so the client
     * can draw part-way between two frames. Setting it changes nothing and reading it is meaningless.
     */
    private static final String PREVIOUS = "prev";

    /**
     * How many steps along the way from doing nothing to doing it fully are recorded. Enough to keep
     * the shape of a movement that is not a straight line - a clap that swings wide before it closes -
     * without writing a keyframe for every hundredth of it.
     */
    private static final int STEPS = 6;

    /** How many moments of a movement the mob keeps up are recorded. */
    private static final int FRAMES = 8;

    /** Ticks in a second, which is what turns a measured cycle into an animation length. */
    private static final float TICK_RATE = 20.0f;

    /** How finely the search for a repeat steps through time. */
    private static final float PROBE = 0.5f;

    /** How long a movement may take before it is treated as not repeating at all. */
    private static final float LONGEST = 200.0f;

    private CitadelStates() {
    }

    /**
     * Records every pose a mob can hold.
     *
     * @param model the model to pose, taken from the mob's renderer
     * @param parts the model's bones and the names the geometry gave them
     * @param entity a mob of this kind, which is what holds the numbers
     * @return each pose by name, or an empty map if this mob holds none
     */
    @NotNull
    public static Map<String, JsonObject> extract(@NotNull Object model, @NotNull Map<Object, String> parts,
                                                  @Nullable Object entity) {
        Map<String, JsonObject> found = new LinkedHashMap<>();
        if (entity == null) {
            return found;
        }

        Method setupAnim = setupAnim(model);
        if (setupAnim == null) {
            return found;
        }

        Map<Field, String> progresses = progressFields(entity.getClass());
        if (progresses.isEmpty()) {
            return found;
        }

        // The mob doing none of them, which is what the pose is measured against
        Map<Object, float[]> rest = poseWith(setupAnim, model, entity, parts, null, 0.0f, 0.0f);
        if (rest == null) {
            return found;
        }

        for (Map.Entry<Field, String> progress : progresses.entrySet()) {
            // Held fully, and then watched. Reaching the pose is not the end of the movement: a
            // raccoon that has finished sitting up still works its paws, and a skreecher holding its
            // symbols still swings them together. That part is not driven by the number at all - it
            // is driven by the mob's age, ticking along underneath - so a recording that only slides
            // the number up and stops catches the mob arriving and nothing after. Which is exactly
            // what it looked like: the right shape, frozen
            float cycle = cycleWith(setupAnim, model, entity, parts, progress.getKey());

            List<Map<Object, float[]>> along = new ArrayList<>();
            for (int frame = 0; frame < FRAMES; frame++) {
                Map<Object, float[]> at = poseWith(setupAnim, model, entity, parts,
                        progress.getKey(), FULLY, cycle * frame / FRAMES);

                if (at == null) {
                    along.clear();
                    break;
                }

                along.add(at);
            }

            if (along.isEmpty()) {
                continue;
            }

            // A pose that is the same at every moment is a pose, and is held. One that is not is a
            // movement, and repeats - the mob does it for as long as it goes on doing it
            JsonObject pose = still(along)
                    ? held(ramp(setupAnim, model, entity, parts, progress.getKey()), rest, parts)
                    : cycling(along, rest, parts, cycle);

            if (pose != null) {
                found.put(progress.getValue(), pose);
            }
        }

        // Every number back to nothing, so whatever is recorded after this is a mob doing nothing
        for (Field field : progresses.keySet()) {
            set(entity, field, 0.0f);
        }

        return found;
    }

    /**
     * The way up to the pose, for a pose that turns out to be held rather than worked at.
     */
    @NotNull
    private static List<Map<Object, float[]>> ramp(@NotNull Method setupAnim, @NotNull Object model,
                                                   @NotNull Object entity, @NotNull Map<Object, String> parts,
                                                   @NotNull Field progress) {
        List<Map<Object, float[]>> along = new ArrayList<>();
        for (int step = 0; step <= STEPS; step++) {
            Map<Object, float[]> at = poseWith(setupAnim, model, entity, parts,
                    progress, FULLY * step / STEPS, 0.0f);

            if (at == null) {
                along.clear();
                break;
            }

            along.add(at);
        }

        return along;
    }

    /**
     * How long the mob takes to come back round to where it started, while holding the pose.
     * <p>
     * Two moments have to match, not one, for the same reason a stride does: anything that swings
     * passes its starting point twice a turn, and stopping at the first match records half a movement
     * ending mid-swing.
     *
     * @return the number of ticks a turn takes, or a plain second if nothing repeats
     */
    private static float cycleWith(@NotNull Method setupAnim, @NotNull Object model, @NotNull Object entity,
                                   @NotNull Map<Object, String> parts, @NotNull Field progress) {
        Map<Object, float[]> start = poseWith(setupAnim, model, entity, parts, progress, FULLY, 0.0f);
        Map<Object, float[]> justAfter = poseWith(setupAnim, model, entity, parts, progress, FULLY, PROBE);

        if (start == null || justAfter == null) {
            return TICK_RATE;
        }

        for (float at = PROBE; at <= LONGEST; at += PROBE) {
            Map<Object, float[]> here = poseWith(setupAnim, model, entity, parts, progress, FULLY, at);
            if (here == null || !alike(here, start)) {
                continue;
            }

            Map<Object, float[]> next = poseWith(setupAnim, model, entity, parts, progress, FULLY, at + PROBE);
            if (next != null && alike(next, justAfter)) {
                return at;
            }
        }

        return TICK_RATE;
    }

    /**
     * Whether the mob holds perfectly still once it has reached the pose.
     */
    private static boolean still(@NotNull List<Map<Object, float[]>> along) {
        Map<Object, float[]> first = along.get(0);
        for (int frame = 1; frame < along.size(); frame++) {
            if (!alike(along.get(frame), first)) {
                return false;
            }
        }

        return true;
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
     * Turns a movement the mob keeps up into an animation that repeats for as long as it does.
     */
    @Nullable
    private static JsonObject cycling(@NotNull List<Map<Object, float[]>> along, @NotNull Map<Object, float[]> rest,
                                      @NotNull Map<Object, String> parts, float cycle) {
        JsonObject bones = new JsonObject();
        double length = cycle / TICK_RATE;

        for (Map.Entry<Object, String> part : parts.entrySet()) {
            float[] was = rest.get(part.getKey());
            if (was == null) {
                continue;
            }

            JsonObject rotation = new JsonObject();
            boolean moves = false;

            for (int frame = 0; frame < along.size(); frame++) {
                float[] now = along.get(frame).get(part.getKey());
                if (now == null) {
                    continue;
                }

                moves |= !same(now, was);
                rotation.add(String.valueOf(length * frame / along.size()), degrees(new float[] {
                    now[0] - was[0], now[1] - was[1], now[2] - was[2] }));
            }

            // Bones that sit where they always sat say nothing, and cost Bedrock the blending anyway
            if (!moves) {
                continue;
            }

            // Closed back onto its first frame, or the loop jumps at the seam
            float[] first = along.get(0).get(part.getKey());
            if (first != null) {
                rotation.add(String.valueOf(length), degrees(new float[] {
                    first[0] - was[0], first[1] - was[1], first[2] - was[2] }));
            }

            JsonObject moved = new JsonObject();
            moved.add("rotation", rotation);
            bones.add(part.getValue(), moved);
        }

        if (bones.isEmpty()) {
            return null;
        }

        JsonObject animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.addProperty("animation_length", length);
        animation.add("bones", bones);
        return animation;
    }

    /**
     * Poses the model with one of the numbers turned all the way up, and reads where the bones went.
     */
    @Nullable
    private static Map<Object, float[]> poseWith(@NotNull Method setupAnim, @NotNull Object model,
                                                 @NotNull Object entity, @NotNull Map<Object, String> parts,
                                                 @Nullable Field progress, float value, float age) {
        if (progress != null) {
            set(entity, progress, value);
        }

        try {
            CitadelGeometry.wake(model, entity);
            setupAnim.invoke(model, entity, 0.0f, 0.0f, age, 0.0f, 0.0f);
        } catch (Throwable t) {
            if (progress != null) {
                set(entity, progress, 0.0f);
            }

            return null;
        }

        Map<Object, float[]> pose = new LinkedHashMap<>();
        for (Object part : parts.keySet()) {
            pose.put(part, rotationOf(part));
        }

        if (progress != null) {
            set(entity, progress, 0.0f);
        }

        return pose;
    }

    /**
     * Turns a held pose into an animation that eases into it and stays there.
     */
    @Nullable
    private static JsonObject held(@NotNull List<Map<Object, float[]>> along, @NotNull Map<Object, float[]> rest,
                                   @NotNull Map<Object, String> parts) {
        if (along.isEmpty()) {
            return null; // the way up could not be read, which leaves nothing to hold
        }

        JsonObject bones = new JsonObject();

        for (Map.Entry<Object, String> part : parts.entrySet()) {
            float[] was = rest.get(part.getKey());
            if (was == null) {
                continue;
            }

            // Only bones that actually go somewhere. A mob has plenty that sit still throughout, and
            // writing a keyframe for each of those says nothing while costing Bedrock the work of
            // blending it against everything else
            float[] end = along.get(along.size() - 1).get(part.getKey());
            if (end == null || same(end, was)) {
                continue;
            }

            JsonObject rotation = new JsonObject();
            for (int step = 0; step < along.size(); step++) {
                float[] now = along.get(step).get(part.getKey());
                if (now == null) {
                    continue;
                }

                double time = EASE * step / (along.size() - 1.0);
                rotation.add(String.valueOf(time), degrees(new float[] {
                    now[0] - was[0], now[1] - was[1], now[2] - was[2] }));
            }

            JsonObject moved = new JsonObject();
            moved.add("rotation", rotation);
            bones.add(part.getValue(), moved);
        }

        if (bones.isEmpty()) {
            return null; // a number that changes nothing about how the mob is drawn
        }

        JsonObject animation = new JsonObject();

        // Held rather than played through. The mob stays like this until it stops, and Bedrock keeps
        // the last frame of an animation that does not loop
        animation.addProperty("loop", "hold_on_last_frame");
        animation.addProperty("animation_length", EASE);
        animation.add("bones", bones);
        return animation;
    }

    /**
     * Every number on a mob that says how far through doing something it is.
     * <p>
     * Found by name, because that is what they have in common: a mob keeps {@code begProgress} beside
     * {@code prevBegProgress}, one being where it is now and the other where it was a frame ago. Only
     * the first is worth setting; the second exists to draw between them.
     */
    @NotNull
    private static Map<Field, String> progressFields(@NotNull Class<?> type) {
        Map<Field, String> found = new LinkedHashMap<>();

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() != float.class) {
                    continue;
                }

                String name = field.getName();
                if (!name.endsWith("Progress") || name.startsWith(PREVIOUS)) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    found.putIfAbsent(field,
                            name.substring(0, name.length() - "Progress".length()).toLowerCase(Locale.ROOT));
                } catch (RuntimeException e) {
                    // A field that will not be set is a pose that cannot be read
                }
            }
        }

        return found;
    }

    /**
     * Sets a number, and the copy of it kept from the previous frame.
     * <p>
     * A model rarely reads the number on its own. It reads both and draws part-way between them, so
     * that a pose eases in over a frame rather than snapping - and asked for a moment part-way from
     * the last frame, "part-way" is nearly all of the previous value. Setting only the current one
     * therefore changes nothing at all: the answer comes back as whatever the mob was doing before,
     * which is nothing.
     * <p>
     * Both together leave no room for interpolation to land anywhere else. It is why a raccoon's beg
     * came out - its model reads the number directly - while a skreecher's clap did not.
     */
    private static void set(@NotNull Object entity, @NotNull Field field, float value) {
        try {
            field.setFloat(entity, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Left where it was, which only costs this one pose
        }

        String previous = PREVIOUS + Character.toUpperCase(field.getName().charAt(0))
                + field.getName().substring(1);

        for (Class<?> type = entity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field twin = type.getDeclaredField(previous);
                if (twin.getType() != float.class) {
                    continue;
                }

                twin.setAccessible(true);
                twin.setFloat(entity, value);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next class up; a number without a twin is read on its own
            }
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

    private static boolean same(@NotNull float[] a, @NotNull float[] b) {
        return Math.abs(a[0] - b[0]) < 1.0e-4f
                && Math.abs(a[1] - b[1]) < 1.0e-4f
                && Math.abs(a[2] - b[2]) < 1.0e-4f;
    }

    @NotNull
    private static JsonArray degrees(@NotNull float[] radians) {
        return BoneRotation.degrees(radians);
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
}
