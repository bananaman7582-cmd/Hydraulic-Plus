package org.geysermc.hydraulic.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** Pure naming rules shared by legacy armour texture discovery and its tests. */
public final class EquipmentTextures {
    private EquipmentTextures() {
    }

    /**
     * Finds the best old-style armour texture name, including its layer suffix.
     */
    @Nullable
    public static String matchLegacy(@NotNull Set<String> textures, @NotNull String assetPath,
                                     @NotNull String itemPath, boolean leggings) {
        String best = null;
        int bestScore = 0;
        String wantedSuffix = leggings ? "_1" : "_0";

        for (String texture : textures) {
            String base = stripLayer(texture);
            boolean exactLayer = texture.endsWith(wantedSuffix);
            // A suffix-less image is conventionally the body layer. Do not put it on leggings.
            if (leggings && !exactLayer) {
                continue;
            }
            if (!leggings && texture.endsWith("_1")) {
                continue;
            }

            int score;
            if (base.equals(assetPath)) {
                score = 40;
            } else if (base.startsWith(assetPath) || assetPath.startsWith(base)) {
                score = 30;
            } else if (itemPath.startsWith(firstWord(base))) {
                score = 20;
            } else {
                score = 0;
            }
            if (exactLayer) {
                score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = texture;
            }
        }
        return best;
    }

    private static String stripLayer(String name) {
        return name.endsWith("_0") || name.endsWith("_1")
                ? name.substring(0, name.length() - 2) : name;
    }

    private static String firstWord(String name) {
        int underscore = name.indexOf('_');
        return underscore <= 0 ? name : name.substring(0, underscore);
    }
}
