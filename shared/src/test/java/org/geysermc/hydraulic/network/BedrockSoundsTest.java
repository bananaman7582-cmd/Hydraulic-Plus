package org.geysermc.hydraulic.network;

import com.mojang.serialization.Lifecycle;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.geysermc.hydraulic.util.BedrockSounds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSoundsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void boundCustomPositionalSoundBecomesInlineAndGeyserCanReadIt() {
        var source = new ClientboundSoundPacket(customSound(), SoundSource.BLOCKS, 4.5, 12, -3,
                .8F, 1.2F, 123L);
        var safe = BedrockSounds.inline(source);
        assertTrue(source.getSound().unwrapKey().isPresent(), "Do not mutate the packet Java players share");
        assertTrue(safe.getSound().unwrapKey().isEmpty());
        var bytes = Unpooled.buffer();
        try {
            ClientboundSoundPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(bytes, RegistryAccess.EMPTY), safe);
            var decoded = new org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket(bytes);
            assertEquals(source.getVolume(), decoded.getVolume());
            assertEquals(source.getPitch(), decoded.getPitch());
            assertEquals(source.getSeed(), decoded.getSeed());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test
    void attachedSoundKeepsVirtualEntityIdAndSeed() {
        var sound = customSound();
        var registry = new MappedRegistry<SoundEvent>(Registries.SOUND_EVENT, Lifecycle.stable());
        var bound = registry.register(sound.unwrapKey().orElseThrow(), sound.value(), RegistrationInfo.BUILT_IN);
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.List.of(registry.freeze()));
        var sourceBytes = Unpooled.buffer();
        ClientboundSoundEntityPacket source;
        try {
            var buffer = new RegistryFriendlyByteBuf(sourceBytes, access);
            SoundEvent.STREAM_CODEC.encode(buffer, bound);
            buffer.writeEnum(SoundSource.HOSTILE);
            buffer.writeVarInt(69304);
            buffer.writeFloat(.7F);
            buffer.writeFloat(.9F);
            buffer.writeLong(456L);
            source = ClientboundSoundEntityPacket.STREAM_CODEC.decode(buffer);
        } finally {
            sourceBytes.release();
        }
        var safe = BedrockSounds.inline(source);
        assertNotNull(safe);
        assertEquals(source.getId(), safe.getId());
        assertEquals(456L, safe.getSeed());
        assertTrue(safe.getSound().unwrapKey().isEmpty());
        assertEquals(69304, safe.getId());
        assertSame(safe, BedrockSounds.inline(safe));
        var bytes = Unpooled.buffer();
        try {
            ClientboundSoundEntityPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(bytes, RegistryAccess.EMPTY), safe);
            var decoded = new org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSoundEntityPacket(bytes);
            assertEquals(source.getId(), decoded.getEntityId());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    private static Holder<SoundEvent> customSound() {
        var id = Identifier.fromNamespaceAndPath("compat_test", "mob.howl");
        var registry = new MappedRegistry<SoundEvent>(Registries.SOUND_EVENT, Lifecycle.stable());
        var holder = registry.register(ResourceKey.create(Registries.SOUND_EVENT, id),
                SoundEvent.createVariableRangeEvent(id), RegistrationInfo.BUILT_IN);
        registry.freeze();
        return holder;
    }
}
