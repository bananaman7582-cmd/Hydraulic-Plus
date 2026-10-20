package org.geysermc.hydraulic.util;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Rewrites every leaf, omitting rejected packets without losing its readable neighbours. */
public final class PacketBundles {
    private PacketBundles() {
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static Packet<?> rewrite(ClientboundBundlePacket bundle,
                                    Function<Packet<?>, @Nullable Packet<?>> rewrite) {
        List<Packet<? super ClientGamePacketListener>> result = new ArrayList<>();
        boolean changed = false;
        for (Packet<? super ClientGamePacketListener> packet : bundle.subPackets()) {
            Packet<?> next = packet instanceof ClientboundBundlePacket nested
                    ? rewrite(nested, rewrite) : rewrite.apply(packet);
            changed |= next != packet;
            if (next != null) {
                result.add((Packet<? super ClientGamePacketListener>) next);
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        return changed ? new ClientboundBundlePacket(result) : bundle;
    }
}
