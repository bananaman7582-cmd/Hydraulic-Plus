package org.geysermc.hydraulic.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small, format-level repairs for assets that Minecraft's own client accepts leniently.
 *
 * <p>These repairs deliberately preserve ordinary model data. They only remove declarations which
 * require a mod's Java renderer, or malformed optional fields which vanilla itself ignores.</p>
 */
public final class ResourceCompatibility {
    private static final Set<String> DIRECTIONS = Set.of("down", "up", "north", "south", "west", "east");

    private ResourceCompatibility() {
    }

    /**
     * Makes a parsed item/block model usable by the generic converter when possible.
     *
     * @param element parsed model JSON, modified in place
     * @return whether the JSON changed
     */
    public static boolean simplifyModdedRendering(@NotNull JsonElement element) {
        boolean changed = false;

        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                changed |= simplifyModdedRendering(child);
            }
            return changed;
        }

        if (!(element instanceof JsonObject object)) {
            return false;
        }

        // Java accepts negative quarter-turns in blockstate variants, while the resource library
        // insists on their equivalent positive form. Normalise only variant-shaped objects (those
        // naming a model), leaving item display transforms and arbitrary data untouched.
        if (object.get("model") instanceof JsonPrimitive model && model.isString()) {
            changed |= normaliseQuarterTurn(object, "x");
            changed |= normaliseQuarterTurn(object, "y");
        }

        // 26.2 introduced the baby humanoid equipment layer before the converter library learned
        // its enum name. The adult layer is the closest usable Bedrock representation and these
        // files normally provide the same texture for both.
        if (object.get("layers") instanceof JsonObject layers
                && layers.has("humanoid_baby")) {
            if (!layers.has("humanoid")) {
                layers.add("humanoid", layers.get("humanoid_baby").deepCopy());
            }
            layers.remove("humanoid_baby");
            changed = true;
        }

        // Custom item-model properties are Java code. They cannot be evaluated while producing a
        // static Bedrock item definition, but their ordinary/fallback branch remains useful. More
        // importantly, strict parsers must not discard the complete item just because they do not
        // know the property's codec.
        JsonElement fallback = fallbackForModdedProperty(object);
        if (fallback instanceof JsonObject fallbackObject) {
            replace(object, fallbackObject.deepCopy());
            changed = true;
        }

        // A Java renderer may sit beside the ordinary model it is drawn over. Bedrock cannot run
        // that renderer, but can still use the base model instead of losing the item altogether.
        if (isType(object, "minecraft:special") && object.get("base") instanceof JsonPrimitive base
                && base.isString()) {
            object.remove("base");
            object.remove("model");
            object.addProperty("type", "minecraft:model");
            object.add("model", base);
            changed = true;
        }

        // Code-backed tint sources have no Bedrock equivalent. The untinted texture is preferable
        // to rejecting the complete definition.
        if (object.get("tints") instanceof JsonArray tints && containsModdedType(tints)) {
            object.remove("tints");
            changed = true;
        }

        // Minecraft ignores an invalid optional cullface. Strict resource libraries can reject the
        // whole model instead (Alex's Caves cave paintings used "painting" here).
        JsonElement elements = object.get("elements");
        if (elements instanceof JsonArray modelElements) {
            for (JsonElement modelElement : modelElements) {
                if (!(modelElement instanceof JsonObject modelObject)
                        || !(modelObject.get("faces") instanceof JsonObject faces)) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                    if (!(face.getValue() instanceof JsonObject side)
                            || !(side.get("cullface") instanceof JsonPrimitive cullface)
                            || !cullface.isString()) {
                        continue;
                    }
                    if (!DIRECTIONS.contains(cullface.getAsString().toLowerCase(Locale.ROOT))) {
                        side.remove("cullface");
                        changed = true;
                    }
                }
            }
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            changed |= simplifyModdedRendering(entry.getValue());
        }
        return changed;
    }

    private static boolean normaliseQuarterTurn(JsonObject object, String axis) {
        JsonElement value = object.get(axis);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            return false;
        }
        int angle;
        try {
            angle = primitive.getAsInt();
        } catch (NumberFormatException ignored) {
            return false;
        }
        if (angle >= 0 || angle % 90 != 0) {
            return false;
        }
        object.addProperty(axis, Math.floorMod(angle, 360));
        return true;
    }

    /** Turns a registry path into a readable fallback name without changing explicit translations. */
    @NotNull
    public static String readableName(@NotNull String path) {
        StringBuilder output = new StringBuilder(path.length());
        boolean uppercase = true;
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '_' || character == '-' || character == '/') {
                if (!output.isEmpty() && output.charAt(output.length() - 1) != ' ') {
                    output.append(' ');
                }
                uppercase = true;
            } else {
                output.append(uppercase ? Character.toUpperCase(character) : character);
                uppercase = false;
            }
        }
        return output.toString().trim();
    }

    private static boolean containsModdedType(JsonArray array) {
        for (JsonElement element : array) {
            if (element instanceof JsonObject object && object.get("type") instanceof JsonPrimitive type
                    && type.isString() && !type.getAsString().startsWith("minecraft:")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isType(JsonObject object, String type) {
        return object.get("type") instanceof JsonPrimitive value && value.isString()
                && value.getAsString().equals(type);
    }

    private static JsonElement fallbackForModdedProperty(JsonObject object) {
        if (!(object.get("property") instanceof JsonPrimitive property) || !property.isString()
                || !property.getAsString().contains(":")
                || property.getAsString().startsWith(Identifier.DEFAULT_NAMESPACE + ":")) {
            return null;
        }
        if (isType(object, "minecraft:condition")) {
            return object.get("on_false");
        }
        if (isType(object, "minecraft:select") || isType(object, "minecraft:range_dispatch")) {
            JsonElement direct = object.get("fallback");
            if (direct != null) {
                return direct;
            }
            String listName = isType(object, "minecraft:select") ? "cases" : "entries";
            if (object.get(listName) instanceof JsonArray choices && !choices.isEmpty()
                    && choices.get(0) instanceof JsonObject first) {
                return first.get("model");
            }
        }
        return null;
    }

    private static void replace(JsonObject target, JsonObject source) {
        for (String key : Set.copyOf(target.keySet())) {
            target.remove(key);
        }
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            target.add(entry.getKey(), entry.getValue());
        }
    }
}
