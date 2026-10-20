package org.geysermc.hydraulic.network;

import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import org.geysermc.hydraulic.util.PacketBundles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class PacketBundlesTest {
    @Test
    void unchangedBundleIsReused() {
        var bundle = new ClientboundBundlePacket(List.of(data(1), data(2)));
        assertSame(bundle, PacketBundles.rewrite(bundle, packet -> packet));
    }

    @Test
    void nestedRejectionPreservesReadableNeighboursAndOrder() {
        var first = data(1);
        var rejected = data(2);
        var last = data(3);
        var replacement = data(4);
        var nested = new ClientboundBundlePacket(List.of(rejected, last));
        var bundle = new ClientboundBundlePacket(List.of(first, nested));
        var result = (ClientboundBundlePacket) PacketBundles.rewrite(bundle,
                packet -> packet == rejected ? null : packet == last ? replacement : packet);
        var packets = StreamSupport.stream(result.subPackets().spliterator(), false).toList();
        assertSame(first, packets.getFirst());
        var inner = (ClientboundBundlePacket) packets.get(1);
        assertEquals(List.of(replacement), StreamSupport.stream(inner.subPackets().spliterator(), false).toList());
    }

    @Test
    void emptyBundlesAreOmitted() {
        var bundle = new ClientboundBundlePacket(List.of(new ClientboundBundlePacket(List.of(data(1)))));
        assertNull(PacketBundles.rewrite(bundle, packet -> null));
    }

    private static ClientboundSetEntityDataPacket data(int id) {
        return new ClientboundSetEntityDataPacket(id, List.of());
    }
}
