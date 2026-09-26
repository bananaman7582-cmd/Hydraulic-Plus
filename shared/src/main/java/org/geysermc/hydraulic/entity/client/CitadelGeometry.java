package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts entity models built with Citadel's own model system, which Alex's Mobs and its siblings
 * use instead of the game's.
 * <p>
 * Vanilla registers every entity model as a {@code LayerDefinition}, which is how
 * {@link EntityGeometryConverter} finds them - one place holding them all. Citadel does not: its
 * models are ordinary objects a renderer builds for itself, so nothing enumerates them and that
 * converter sees an Alex's Mobs world as having no mobs in it at all. They still describe the same
 * thing though - named parts, each with boxes given by two corners - so given a model object the
 * shape can be read out just as well.
 * <p>
 * <b>Everything here is reflection, deliberately.</b> Citadel is shaded inside each mod that uses it
 * - Alex's Mobs carries its own copy under its own package - so there is no one class to compile
 * against, and a mod built on a differently-shaded copy would not match it anyway. Matching on the
 * shape of the class instead means any mod carrying Citadel works without knowing which mod it is.
 * <p>
 * A model that does not fit this shape is left alone rather than guessed at; the caller falls back to
 * reporting it as missing.
 */
public final class CitadelGeometry {
    /**
     * The class every Citadel model extends, by name only, since the package differs per mod.
     */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Said once per model. A hundred repetitions of the same line is not more information. */
    private static final java.util.Set<String> TOLD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final String MODEL_CLASS = "AdvancedEntityModel";

    /**
     * The part class, likewise. Its own parent {@code BasicModelPart} holds the pose and the boxes.
     */
    private static final String PART_CLASS = "AdvancedModelBox";

    private static final float Y_OFFSET = 24.0f;

    private CitadelGeometry() {
    }

    /**
     * Asks a model to pose itself once, so what is read afterwards is what it would actually draw.
     * <p>
     * Everything about a model that varies - which parts are shown, where an arm has been put - is
     * settled by the method that poses it, not by the constructor. Reading a model that has never been
     * asked to draw gives whatever its parts were built holding, which for anything conditional is the
     * wrong answer.
     * <p>
     * Failure is fine and expected: a model that wants more of the mob than there is here simply
     * leaves its parts as they were, which is exactly what would have been read anyway.
     */
    /**
     * Hands the mob to the model's animator, which is normally the renderer's job.
     * <p>
     * A Citadel model keeps an animator that plays its keyframed animations, and that animator asks
     * the mob what it is doing. It is given the mob by whatever is about to draw it - not by the model
     * itself - so a model posed directly, as it is here, reaches an animator that has never been told
     * which creature this is and throws the moment it asks.
     * <p>
     * That cost thirty of Alex's Mobs their walk and their idle: a bison, an elephant, an emu and a
     * crocodile among them, all standing about perfectly still while their attacks worked fine, since
     * only the part that goes through the animator was failing.
     */
    public static void wake(@NotNull Object model, @Nullable Object subject) {
        if (subject == null) {
            return;
        }

        String reported = model.getClass().getSimpleName();
        boolean foundAnimator = false;

        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getSimpleName().equals("ModelAnimator")) {
                    continue;
                }

                try {
                    field.setAccessible(true);

                    Object animator = field.get(model);
                    if (animator == null) {
                        continue;
                    }

                    foundAnimator = true;

                    for (java.lang.reflect.Method method : animator.getClass().getDeclaredMethods()) {
                        if (method.getName().equals("update") && method.getParameterCount() == 1
                                && method.getParameterTypes()[0].isInstance(subject)) {
                            method.setAccessible(true);
                            method.invoke(animator, subject);
                            return;
                        }
                    }

                    if (TOLD.add(reported)) {
                        LOGGER.warn("{} keeps an animator that will not take a {}; its keyframed poses "
                                + "cannot be read", reported, subject.getClass().getSimpleName());
                    }
                } catch (Throwable t) {
                    if (TOLD.add(reported)) {
                        LOGGER.warn("Could not hand {} the mob it animates", reported, t);
                    }
                }
            }
        }

        if (!foundAnimator && TOLD.add(reported)) {
            LOGGER.warn("{} has no animator field to hand the mob to", reported);
        }
    }

    public static void settle(@NotNull Object model, @Nullable Object subject) {
        wake(model, subject);
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("setupAnim") || method.getParameterCount() != 6) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    method.invoke(model, subject, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
                } catch (Throwable t) {
                    // Left as built, which is where it already was
                }

                return;
            }
        }
    }

    /**
     * Whether this looks like a model Citadel built.
     */
    public static boolean isCitadelModel(@Nullable Object model) {
        return model != null && inherits(model.getClass(), MODEL_CLASS);
    }

    /**
     * Converts a Citadel model into Bedrock geometry.
     *
     * @param identifier the name to give the geometry, without the {@code geometry.} prefix
     * @param model the model object taken from the entity's renderer
     * @return the geometry, or null if the model could not be read
     */
    @Nullable
    public static JsonObject convert(@NotNull String identifier, @NotNull Object model, @Nullable Object subject) {
        try {
            Map<Object, String> names = partsOf(model);
            if (names.isEmpty()) {
                return null;
            }

            // Let the model pose itself once before anything is read off it. Which parts a model shows
            // is not decided when it is built but when it is asked to draw: a bear carries a joke hat
            // and a microphone it only puts on when it is named after a particular bear, and both sit
            // switched on until the model is asked and switches them off. Read too early, every bear
            // in the world gets the hat
            settle(model, subject);

            JsonArray bones = new JsonArray();
            for (Map.Entry<Object, String> entry : names.entrySet()) {
                // Only the parts nothing else owns start a branch; the rest are reached by walking
                // down from those, which is what gives each bone its parent
                if (parentOf(entry.getKey()) == null) {
                    appendBone(bones, entry.getKey(), entry.getValue(), null, 0, 0, 0, names, intField(model, "texWidth", 64), intField(model, "texHeight", 64));
                }
            }

            if (bones.isEmpty()) {
                return null;
            }

            JsonObject description = new JsonObject();
            description.addProperty("identifier", "geometry." + identifier);
            description.addProperty("texture_width", intField(model, "texWidth", 64));
            description.addProperty("texture_height", intField(model, "texHeight", 64));
            VisibleBounds.describe(description, bones);

            JsonObject geometry = new JsonObject();
            geometry.add("description", description);
            geometry.add("bones", bones);

            JsonArray geometries = new JsonArray();
            geometries.add(geometry);

            JsonObject file = new JsonObject();
            file.addProperty("format_version", "1.12.0");
            file.add("minecraft:geometry", geometries);
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Every part the model holds, and the name to call it by.
     * <p>
     * Citadel models keep their parts in plain fields - {@code body}, {@code head} and so on - so the
     * fields are what is read. A part carries a name of its own too, but a field name is always there
     * and always unique, which a box name is not.
     */
    @NotNull
    public static Map<Object, String> partsOf(@NotNull Object model) throws IllegalAccessException {
        Map<Object, String> parts = new LinkedHashMap<>();

        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!inherits(field.getType(), PART_CLASS)) {
                    continue;
                }

                field.setAccessible(true);
                Object part = field.get(model);
                if (part != null) {
                    parts.putIfAbsent(part, field.getName());
                }
            }
        }

        // Children are not always fields of the model, so collect anything hanging off what was found
        for (Object part : List.copyOf(parts.keySet())) {
            collectChildren(part, parts);
        }

        return parts;
    }

    private static void collectChildren(@NotNull Object part, @NotNull Map<Object, String> into) {
        for (Object child : childrenOf(part)) {
            if (into.putIfAbsent(child, "bone" + into.size()) == null) {
                collectChildren(child, into);
            }
        }
    }

    private static void appendBone(@NotNull JsonArray bones, @NotNull Object part, @NotNull String name,
                                   @Nullable String parentName, float parentX, float parentY, float parentZ,
                                   @NotNull Map<Object, String> names, int texWidth, int texHeight) {
        // A part the model keeps switched off, along with everything hanging from it. Java skips these
        // when drawing, so a model can carry pieces it almost never shows - a joke hat, a second head
        // for one variant - and converting them anyway puts them on every one of those mobs at once.
        // Bedrock has no way to turn a bone off afterwards, so the decision has to be made here
        if (!booleanField(part, "showModel", true)) {
            return;
        }

        float x = parentX + floatField(part, "rotationPointX", 0);
        float y = parentY + floatField(part, "rotationPointY", 0);
        float z = parentZ + floatField(part, "rotationPointZ", 0);

        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parentName != null) {
            bone.addProperty("parent", parentName);
        }

        // The same turn from Java's downward Y to Bedrock's upward one that vanilla models need
        bone.add("pivot", numbers(x, Y_OFFSET - y, z));

        float rotX = floatField(part, "rotateAngleX", 0);
        float rotY = floatField(part, "rotateAngleY", 0);
        float rotZ = floatField(part, "rotateAngleZ", 0);
        if (rotX != 0 || rotY != 0 || rotZ != 0) {
            // The same convention the animations use. A bone turns the same way in both games, and
            // mirroring it here while an animation does not left the two disagreeing about a bone
            // that starts out turned - a kangaroo's ears splayed inward instead of outward
            bone.add("rotation", numbers(Math.toDegrees(rotX), Math.toDegrees(rotY), Math.toDegrees(rotZ)));
        }

        // Two boxes in the same place is not a model with a thick surface - it is a model with a
        // second layer over the first, an overlay or a glow that Java draws in its own pass. Bedrock
        // draws both in the same pass, at the same depth, and the two flicker against each other from
        // every angle. Keeping the first is what Java shows underneath anyway
        Set<String> placed = new HashSet<>();

        JsonArray cubes = new JsonArray();
        for (Object cube : cubesOf(part)) {
            JsonObject built = cube(cube, part, x, y, z, texWidth, texHeight);

            JsonElement origin = built.get("origin");
            JsonElement size = built.get("size");
            if (origin != null && size != null && !placed.add(origin + "|" + size)) {
                continue;
            }

            cubes.add(built);
        }

        if (!cubes.isEmpty()) {
            bone.add("cubes", cubes);
        }

        bones.add(bone);

        for (Object child : childrenOf(part)) {
            String childName = names.get(child);
            if (childName != null) {
                appendBone(bones, child, childName, name, x, y, z, names, texWidth, texHeight);
            }
        }
    }

    @NotNull
    private static JsonObject cube(@NotNull Object cube, @NotNull Object part, float partX, float partY, float partZ,
                                   int texWidth, int texHeight) {
        // A Citadel box states both its corners rather than a corner and a size
        float x1 = floatField(cube, "posX1", 0);
        float y1 = floatField(cube, "posY1", 0);
        float z1 = floatField(cube, "posZ1", 0);
        float x2 = floatField(cube, "posX2", 0);
        float y2 = floatField(cube, "posY2", 0);
        float z2 = floatField(cube, "posZ2", 0);

        // Citadel resizes a part when it draws it, which Bedrock geometry has no way to express - a
        // gorilla's head is a normal box made bigger at the last moment, so taken at face value it
        // comes out too small. Growing the box itself about the part's own pivot says the same thing
        // in a form Bedrock can hold
        float scaleX = floatField(part, "scaleX", 1);
        float scaleY = floatField(part, "scaleY", 1);
        float scaleZ = floatField(part, "scaleZ", 1);

        x1 *= scaleX;
        x2 *= scaleX;
        y1 *= scaleY;
        y2 *= scaleY;
        z1 *= scaleZ;
        z2 *= scaleZ;

        JsonObject json = new JsonObject();
        json.add("origin", numbers(partX + x1, Y_OFFSET - (partY + y2), partZ + z1));
        json.add("size", numbers(x2 - x1, y2 - y1, z2 - z1));

        // Every box has a texture corner of its own, which is not written down anywhere - only baked
        // into the corners of the faces built from it. Taking the part's single offset instead was
        // right only for a part holding one box, and wrong for every segment of a centipede after the
        // first, which is why long many-boxed mobs came out scrambled while simple ones looked fine
        float[] uv = uvOf(cube, texWidth, texHeight);
        json.add("uv", uv == null
                ? numbers(intField(part, "textureOffsetX", 0), intField(part, "textureOffsetY", 0))
                : numbers(uv[0], uv[1]));

        if (booleanField(part, "mirror")) {
            json.addProperty("mirror", true);
        }

        return json;
    }

    /**
     * Works out where on the texture a box is drawn from, since Citadel does not keep it.
     * <p>
     * A box is given a texture corner when it is built, but that corner is never stored - it is used
     * once to lay out the faces and then only survives in their corners. Reading it back means taking
     * the smallest corner across every face, which is where the box's patch of texture begins.
     * <p>
     * The faces hold their coordinates as fractions of the whole texture, so they are scaled back up
     * to pixels, which is what Bedrock is given.
     *
     * @return the corner in pixels, or null if the faces could not be read
     */
    @Nullable
    private static float[] uvOf(@NotNull Object cube, int texWidth, int texHeight) {
        Object quads = readField(cube, "quads");
        if (!(quads instanceof Object[] faces) || faces.length == 0) {
            return null;
        }

        float u = Float.MAX_VALUE;
        float v = Float.MAX_VALUE;

        for (Object face : faces) {
            Object vertices = readField(face, "vertexPositions");
            if (!(vertices instanceof Object[] corners)) {
                continue;
            }

            for (Object corner : corners) {
                u = Math.min(u, floatField(corner, "textureU", Float.MAX_VALUE));
                v = Math.min(v, floatField(corner, "textureV", Float.MAX_VALUE));
            }
        }

        if (u == Float.MAX_VALUE || v == Float.MAX_VALUE) {
            return null;
        }

        return new float[] { Math.round(u * texWidth), Math.round(v * texHeight) };
    }

    @NotNull
    private static List<Object> childrenOf(@NotNull Object part) {
        Object children = readField(part, "childModels");
        return children instanceof Iterable<?> iterable ? copy(iterable) : List.of();
    }

    @NotNull
    private static List<Object> cubesOf(@NotNull Object part) {
        Object cubes = readField(part, "cubeList");
        return cubes instanceof Iterable<?> iterable ? copy(iterable) : List.of();
    }

    @NotNull
    private static List<Object> copy(@NotNull Iterable<?> iterable) {
        List<Object> copied = new ArrayList<>();
        iterable.forEach(copied::add);
        return copied;
    }

    @Nullable
    private static Object parentOf(@NotNull Object part) {
        return readField(part, "parent");
    }

    /**
     * Whether a class, or anything it extends, is called this.
     */
    private static boolean inherits(@Nullable Class<?> type, @NotNull String simpleName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getSimpleName().equals(simpleName)) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private static Object readField(@NotNull Object owner, @NotNull String name) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next class up
            }
        }

        return null;
    }

    private static float floatField(@NotNull Object owner, @NotNull String name, float fallback) {
        Object value = readField(owner, name);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static int intField(@NotNull Object owner, @NotNull String name, int fallback) {
        Object value = readField(owner, name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean booleanField(@NotNull Object owner, @NotNull String name) {
        return Boolean.TRUE.equals(readField(owner, name));
    }

    /**
     * Reads a flag that may not be there, where its absence and its being false mean different things.
     */
    private static boolean booleanField(@NotNull Object owner, @NotNull String name, boolean fallback) {
        Object value = readField(owner, name);
        return value instanceof Boolean flag ? flag : fallback;
    }

    @NotNull
    private static JsonArray numbers(@NotNull Number... values) {
        JsonArray array = new JsonArray();
        for (Number value : values) {
            array.add(value);
        }

        return array;
    }
}
