package org.geysermc.hydraulic.config;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps Polymer's own packets away from Bedrock players.
 * <p>
 * Polymer and Hydraulic answer the same question for different people. Polymer dresses modded content
 * up as vanilla so that an unmodified <i>Java</i> client can see something sensible; Hydraulic
 * converts it properly and hands Bedrock the real thing. A server running both tells a Bedrock player
 * both stories at once.
 * <p>
 * <b>What this does:</b> drops the packets Polymer sends on its own channels - the handshake that
 * tells a client Polymer is present, and the extra data it sends afterwards. To Bedrock the server
 * then looks like one without Polymer installed.
 * <p>
 * <b>What this does not do,</b> and it matters: Polymer's main work is not a packet of its own. It
 * rewrites ordinary packets on the way out, turning a modded block into whichever vanilla block it
 * chose to disguise it as, and by the time anything here sees one it is already a vanilla block and
 * indistinguishable from a real one. Stopping that means stopping Polymer from doing it in the first
 * place, which is a decision only Polymer can make, through hooks of its own.
 * <p>
 * So this removes the confusion Polymer's own channels cause and no more. Whether it is enough
 * depends on what the mods in question actually use Polymer for.
 */
public final class PolymerFilter {
    /**
     * The namespace Polymer sends its own packets under. Matched by name because Hydraulic is not
     * built against Polymer and must work whether or not it is installed.
     */
    private static final String POLYMER = "polymer";

    private PolymerFilter() {
    }

    /**
     * Whether this packet is one of Polymer's, and so should not be sent to a Bedrock player.
     */
    public static boolean isPolymer(@NotNull Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket payload)) {
            return false;
        }

        Identifier channel = payload.payload().type().id();
        return channel != null && channel.getNamespace().startsWith(POLYMER);
    }
}
