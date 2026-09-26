package org.geysermc.hydraulic.fabric.mixin.polymer;

import eu.pb4.polymer.core.impl.networking.PacketPatcher;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.geysermc.hydraulic.fabric.polymer.BedrockAudience;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves a whole packet as it is when the player being sent it is on Bedrock.
 * <p>
 * Polymer rewrites packets in two places: as they are turned into bytes, and here, where it is
 * handed a packet and the listener it is bound for. The difference matters. Everywhere else the
 * recipient has to be worked out from the context Polymer happens to be carrying, and being wrong
 * about that is indistinguishable from not running at all; here the listener is passed outright, so
 * the player it belongs to can simply be asked.
 * <p>
 * That makes this the one place the answer cannot be mistaken, which is why it is patched even
 * though the entry points beside it should already cover the same ground.
 */
@Pseudo
@Mixin(PacketPatcher.class)
public class PacketPatcherMixin {
    @Inject(method = "replace", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$leavePacketAloneForBedrock(ServerCommonPacketListenerImpl listener,
                                                             Packet<?> packet,
                                                             CallbackInfoReturnable<Packet<?>> cir) {
        if (BedrockAudience.isBedrock(listener)) {
            cir.setReturnValue(packet);
        }
    }
}
