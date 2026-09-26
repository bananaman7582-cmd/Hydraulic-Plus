package org.geysermc.hydraulic.block;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * How many of the items a block holds it actually shows.
 * <p>
 * Working out that a block is displaying something can be done without knowing what the block is: a
 * block entity that tells clients about an item is, almost always, one that draws it. Working out
 * <i>how many</i> cannot. A cooking pot describes the six ingredients inside it and draws none of
 * them; a stove describes the six things on top of it and draws all six. The data is the same shape
 * either way, and nothing in it says which case this is.
 * <p>
 * So the general rule is one - the single item on a cutting board, a pedestal, a plate - which is
 * right for almost everything and never turns a closed container into a pile of floating ingredients.
 * Blocks that genuinely display more are named here.
 * <p>
 * <b>This is the one place in the block code that knows about particular mods,</b> and it is meant to
 * stay that way. Everything else works from the shape of the data; only the count needs telling.
 */
public final class DisplayBlocks {
    /**
     * What a block shows unless it is known to show more. One item covers a cutting board, a skillet
     * and every display pedestal, and keeps a container that happens to sync its contents from
     * emptying itself into the air.
     */
    private static final int DEFAULT = 1;

    /**
     * Blocks that draw more than one of the items they carry, and how many they draw.
     */
    private static final Map<String, Integer> KNOWN = Map.of(
            "farmersdelight:stove", 6);

    private DisplayBlocks() {
    }

    /**
     * How many items to show on a block of this kind.
     *
     * @param type the block entity's registered name, such as {@code farmersdelight:stove}
     */
    public static int shown(@NotNull String type) {
        Integer known = KNOWN.get(type);
        if (known != null) {
            return known;
        }

        // Matched on the name alone as well, so another mod's stove - or a fork of this one under a
        // different namespace - is not left showing one thing on a six-hob cooker
        int colon = type.indexOf(':');
        if (colon >= 0) {
            for (Map.Entry<String, Integer> entry : KNOWN.entrySet()) {
                if (entry.getKey().endsWith(":" + type.substring(colon + 1))) {
                    return entry.getValue();
                }
            }
        }

        return DEFAULT;
    }
}
