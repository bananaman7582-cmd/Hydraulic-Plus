package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import org.jetbrains.annotations.NotNull;

/**
 * Turns a Java bone rotation into the numbers a Bedrock animation is written with.
 * <p>
 * <b>Nothing is turned around.</b> That is the whole of it, and it is worth saying plainly because
 * the obvious guess is the other one.
 * <p>
 * The <i>shape</i> of a model does need turning around: Java measures downwards from a mob's head and
 * Bedrock upwards from its feet, so a pivot's height becomes {@code 24 - y}, and the rotations stored
 * alongside it are mirrored to match. Every converted model here does that, and they come out right.
 * <p>
 * An animation is a different thing. It does not describe where a bone is, but how far it has turned
 * from wherever that is - and a bone carries its own frame with it, so the turn means the same in
 * both games without anything being done to it. Mirroring it as well turned every animation inside
 * out, and did it in a way that was easy to miss: a walk swings a leg one way and then the other, so
 * mirrored it looks like a walk that happens to start on the other foot. It only became obvious on
 * the movements that go one way and stay there - a head lowering to eat, a raccoon rearing up, a
 * zombie's arms coming forward. Those came out backwards every time, and were reported four separate
 * times before the pattern was clear: the kangaroo's neck, the zombie's arms, the raccoon standing up
 * into a handstand, and the emu's head facing the wrong way.
 */
public final class BoneRotation {
    private BoneRotation() {
    }

    /**
     * A bone's turn, in the degrees Bedrock reads.
     *
     * @param radians the turn about each axis, as Java holds it
     */
    @NotNull
    public static JsonArray degrees(@NotNull float[] radians) {
        JsonArray values = new JsonArray();
        values.add(Math.toDegrees(radians[0]));
        values.add(Math.toDegrees(radians[1]));
        values.add(Math.toDegrees(radians[2]));
        return values;
    }

    /**
     * A bone's movement, in the pixels Bedrock reads.
     * <p>
     * Position is the one that does flip, and for the reason the shape does: it is a place rather
     * than a turn, and the two games measure height in opposite directions.
     */
    @NotNull
    public static JsonArray offset(@NotNull float[] pixels) {
        JsonArray values = new JsonArray();
        values.add(pixels[0]);
        values.add(-pixels[1]);
        values.add(pixels[2]);
        return values;
    }
}
