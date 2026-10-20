package org.geysermc.hydraulic.util;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;

/** Uses the protocol's inline holder for custom sounds, including sounds attached to moving mobs. */
public final class BedrockSounds {
    private BedrockSounds() {
    }

    public static ClientboundSoundPacket inline(ClientboundSoundPacket packet) {
        if (!needsInline(packet.getSound())) {
            return packet;
        }
        return new ClientboundSoundPacket(Holder.direct(packet.getSound().value()), packet.getSource(),
                packet.getX(), packet.getY(), packet.getZ(), packet.getVolume(), packet.getPitch(), packet.getSeed());
    }

    public static ClientboundSoundEntityPacket inline(ClientboundSoundEntityPacket packet) {
        if (!needsInline(packet.getSound())) {
            return packet;
        }
        // Minecraft exposes only a constructor taking a live Entity, but virtual entities have no
        // world object to look up. Rebuild through the native codec, retaining their exact wire id.
        var bytes = Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(bytes, RegistryAccess.EMPTY);
            SoundEvent.STREAM_CODEC.encode(buffer, Holder.direct(packet.getSound().value()));
            buffer.writeEnum(packet.getSource());
            buffer.writeVarInt(packet.getId());
            buffer.writeFloat(packet.getVolume());
            buffer.writeFloat(packet.getPitch());
            buffer.writeLong(packet.getSeed());
            return ClientboundSoundEntityPacket.STREAM_CODEC.decode(buffer);
        } finally {
            bytes.release();
        }
    }

    private static boolean needsInline(Holder<SoundEvent> sound) {
        return sound.unwrapKey().isPresent() && !sound.value().location().getNamespace().equals("minecraft");
    }
}
