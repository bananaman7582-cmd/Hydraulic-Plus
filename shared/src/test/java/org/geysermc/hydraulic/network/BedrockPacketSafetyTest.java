package org.geysermc.hydraulic.network;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.Identifier;
import org.geysermc.hydraulic.util.BedrockPacketSafety;
import org.geysermc.hydraulic.util.PacketBundles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPacketSafetyTest {
    @Test
    void protectsBothPacketPathsWithoutDroppingTheWholeBundle() {
        var custom = new ClientboundCustomPayloadPacket(new Payload("witherstormmod"));
        var good = new ClientboundSetEntityDataPacket(2, List.of());
        assertTrue(BedrockPacketSafety.shouldDrop(custom));
        var bundle = new ClientboundBundlePacket(List.of(custom, good));
        var safe = (ClientboundBundlePacket) PacketBundles.rewrite(bundle,
                packet -> BedrockPacketSafety.shouldDrop(packet) ? null : packet);
        assertEquals(List.of(good), StreamSupport.stream(safe.subPackets().spliterator(), false).toList());
    }

    @Test
    void preservesMinecraftGeyserAndFloodgatePayloads() {
        for (String namespace : List.of("minecraft", "geyser", "floodgate")) {
            assertFalse(BedrockPacketSafety.shouldDrop(new ClientboundCustomPayloadPacket(new Payload(namespace))));
        }
    }

    @Test
    void rejectsParticlesWithoutAValueRatherThanWritingATruncatedPacket() {
        var packet = new ClientboundLevelParticlesPacket(null, false, false, 0, 0, 0, 0, 0, 0, 0, 1);
        assertTrue(BedrockPacketSafety.shouldDrop(packet));
    }

    private record Payload(String namespace) implements CustomPacketPayload {
        @Override
        public Type<Payload> type() {
            return new Type<>(Identifier.fromNamespaceAndPath(namespace, "test"));
        }
    }
}
