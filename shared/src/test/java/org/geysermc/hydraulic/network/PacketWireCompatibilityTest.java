package org.geysermc.hydraulic.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketWireCompatibilityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emptyMetadataHasTerminatorAndGeyserCanReadIt() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            var packet = new ClientboundSetEntityDataPacket(69304, List.of());
            ClientboundSetEntityDataPacket.STREAM_CODEC.encode(
                    new RegistryFriendlyByteBuf(bytes, RegistryAccess.EMPTY), packet);
            assertEquals(4, bytes.readableBytes());
            assertEquals(255, bytes.getUnsignedByte(bytes.writerIndex() - 1));
            var decoded = new org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity
                    .ClientboundSetEntityDataPacket(bytes);
            assertEquals(69304, decoded.getEntityId());
            assertEquals(0, decoded.getMetadata().length);
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test
    void zeroMotionCanBeReadByGeyser() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            ClientboundSetEntityMotionPacket.STREAM_CODEC.encode(bytes,
                    new ClientboundSetEntityMotionPacket(69304, Vec3.ZERO));
            var decoded = new org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity
                    .ClientboundSetEntityMotionPacket(bytes);
            assertEquals(69304, decoded.getEntityId());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test
    void packetIdsAndSmallEntityPacketsMatchGeyser() {
        ByteBuf headBytes = Unpooled.buffer();
        try {
            FriendlyByteBuf input = new FriendlyByteBuf(headBytes);
            input.writeVarInt(1000);
            input.writeByte(40);
            assertPacketReadable(ClientboundRotateHeadPacket.STREAM_CODEC.decode(input));
            assertPacketReadable(new ClientboundMoveEntityPacket.Rot(1000, (byte) 40, (byte) 0, true));
            assertPacketReadable(new ClientboundSetEntityDataPacket(1000, List.of()));
            assertPacketReadable(new ClientboundSetEntityMotionPacket(1000, Vec3.ZERO));
        } finally {
            headBytes.release();
        }
    }

    private static void assertPacketReadable(Packet<? super ClientGamePacketListener> packet) {
        ByteBuf bytes = Unpooled.buffer();
        try {
            GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY))
                    .codec().encode(bytes, packet);
            int id = new FriendlyByteBuf(bytes).readVarInt();
            var registry = org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec.CODEC.getCodec(
                    org.geysermc.mcprotocollib.protocol.data.ProtocolState.GAME);
            assertNotNull(registry.createClientboundPacket(id, bytes), packet.getClass().getSimpleName());
            assertFalse(bytes.isReadable(), packet.getClass().getSimpleName());
        } finally {
            bytes.release();
        }
    }
}
