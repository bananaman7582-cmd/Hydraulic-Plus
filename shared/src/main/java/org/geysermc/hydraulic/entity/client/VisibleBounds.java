package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * Works out the box a model has to be drawn inside.
 * <p>
 * Bedrock will not draw a model it believes is off screen, and it decides that from a box the model
 * declares rather than from the model itself. The box is not a hint: anything reaching outside it is
 * simply not drawn, so one that is too small quietly removes part of a mob - and because the box is
 * measured from the mob's feet, what goes missing is whatever is furthest out. A moose loses its head
 * and antlers while the body it is attached to stays.
 * <p>
 * Every model here declared the same three-block box, which fitted a sheep and nothing larger. This
 * measures what was actually built instead, so a mob is only ever asked to fit around itself.
 */
public final class VisibleBounds {
    /**
     * Pixels per block, which is what the geometry is written in and the bounds are not.
     */
    private static final float PIXELS = 16.0f;

    /**
     * How much room to leave around the model, in blocks. An animation moves bones outside the shape
     * they rest in - a wing beats, a head turns - and none of that movement is measurable here, so
     * the box is given a margin rather than fitted exactly to the resting pose.
     */
    private static final float MARGIN = 0.5f;

    /**
     * What to fall back to for a model with nothing to measure, matching what Bedrock's own models use.
     */
    private static final float DEFAULT = 2.0f;

    private VisibleBounds() {
    }

    /**
     * Measures the model and writes the box it needs into its description.
     *
     * @param description the geometry description to add the bounds to
     * @param bones the bones of the model, as written for Bedrock
     */
    public static void describe(@NotNull JsonObject description, @NotNull JsonArray bones) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        boolean measured = false;
        for (JsonElement element : bones) {
            JsonElement cubes = element.getAsJsonObject().get("cubes");
            if (cubes == null || !cubes.isJsonArray()) {
                continue;
            }

            for (JsonElement entry : cubes.getAsJsonArray()) {
                JsonObject cube = entry.getAsJsonObject();

                JsonArray origin = cube.getAsJsonArray("origin");
                JsonArray size = cube.getAsJsonArray("size");
                if (origin == null || size == null) {
                    continue;
                }

                float x = origin.get(0).getAsFloat();
                float y = origin.get(1).getAsFloat();
                float z = origin.get(2).getAsFloat();

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);

                maxX = Math.max(maxX, x + size.get(0).getAsFloat());
                maxY = Math.max(maxY, y + size.get(1).getAsFloat());
                maxZ = Math.max(maxZ, z + size.get(2).getAsFloat());

                measured = true;
            }
        }

        if (!measured) {
            description.addProperty("visible_bounds_width", DEFAULT);
            description.addProperty("visible_bounds_height", DEFAULT);
            description.add("visible_bounds_offset", numbers(0, 1, 0));
            return;
        }

        // One width covers both directions along the ground, so it has to be the wider of the two,
        // and it is a full width around the middle rather than a reach out from it
        float width = Math.max(
                Math.max(Math.abs(minX), Math.abs(maxX)),
                Math.max(Math.abs(minZ), Math.abs(maxZ))) * 2.0f / PIXELS;

        float height = (maxY - minY) / PIXELS;

        description.addProperty("visible_bounds_width", round(width + MARGIN * 2.0f));
        description.addProperty("visible_bounds_height", round(height + MARGIN * 2.0f));

        // Measured from the ground the mob stands on, so the box sits around the model's own middle
        // rather than around its feet
        description.add("visible_bounds_offset", numbers(0, round((minY + maxY) / 2.0f / PIXELS), 0));
    }

    private static float round(float value) {
        return Math.round(value * 100.0f) / 100.0f;
    }

    @NotNull
    private static JsonArray numbers(float... values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }

        return array;
    }
}
