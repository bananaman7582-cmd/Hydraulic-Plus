package org.geysermc.hydraulic.util;

import org.geysermc.geyser.api.GeyserApi;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Predicate;

/** One audience check for packet, menu, and optional Polymer hooks, including linked accounts. */
public final class BedrockPlayers {
    private BedrockPlayers() {
    }

    public static boolean isBedrock(@Nullable UUID uuid) {
        return isBedrock(uuid, id -> {
            GeyserApi api = GeyserApi.api();
            return api != null && api.isBedrockPlayer(id);
        });
    }

    static boolean isBedrock(@Nullable UUID uuid, Predicate<UUID> geyser) {
        // Floodgate's UUID convention also works before Geyser has finished attaching a session.
        return uuid != null && (uuid.getMostSignificantBits() == 0 || geyser.test(uuid));
    }
}
