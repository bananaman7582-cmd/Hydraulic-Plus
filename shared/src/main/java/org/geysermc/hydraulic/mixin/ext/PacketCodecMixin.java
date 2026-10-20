package org.geysermc.hydraulic.mixin.ext;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.packet.PacketRegistry;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Set;

/** Retains the failed packet's identity, which Geyser loses when construction throws. */
@Mixin(targets = "org.geysermc.mcprotocollib.network.netty.PacketCodec", remap = false)
public class PacketCodecMixin {
    @Unique
    private static final Logger hydraulic$LOGGER = LogUtils.getLogger();
    @Unique
    private final Set<Integer> hydraulic$reportedPacketErrors = new HashSet<>();

    @WrapOperation(method = "decode", at = @At(value = "INVOKE",
            target = "Lorg/geysermc/mcprotocollib/network/packet/PacketRegistry;createClientboundPacket(ILio/netty/buffer/ByteBuf;)Lorg/geysermc/mcprotocollib/network/packet/Packet;"))
    private Packet hydraulic$identifyDecodeFailure(PacketRegistry registry, int id, ByteBuf bytes,
                                                  Operation<Packet> original) {
        int size = bytes.readableBytes();
        try {
            return original.call(registry, id, bytes);
        } catch (RuntimeException e) {
            // Bound memory and stack traces per connection even if the peer keeps sending bad ids.
            if (this.hydraulic$reportedPacketErrors.size() < 16 && this.hydraulic$reportedPacketErrors.add(id)) {
                String packetName;
                try {
                    packetName = String.valueOf(registry.getClientboundClass(id));
                } catch (IllegalArgumentException ignored) {
                    packetName = "unregistered packet";
                }
                hydraulic$LOGGER.error("Geyser could not decode Java packet {} (id {}, {} payload bytes)",
                        packetName, id, size, e);
            }
            throw e;
        }
    }
}
