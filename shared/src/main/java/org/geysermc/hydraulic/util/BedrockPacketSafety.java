package org.geysermc.hydraulic.util;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.geysermc.hydraulic.config.PolymerFilter;

import java.util.concurrent.atomic.AtomicBoolean;

/** Rejections shared by standalone and bundled packet handling. */
public final class BedrockPacketSafety {
    private static final AtomicBoolean EMPTY_PARTICLE_REPORTED = new AtomicBoolean();

    private BedrockPacketSafety() {
    }

    public static boolean shouldDrop(Packet<?> packet) {
        if (PolymerFilter.isUnreadableModPayload(packet)) {
            return true;
        }
        if (packet instanceof ClientboundLevelParticlesPacket particles && particles.getParticle() == null) {
            if (EMPTY_PARTICLE_REPORTED.compareAndSet(false, true)) {
                LogUtils.getLogger().warn("Dropped a particle packet without a particle before it could disconnect a Bedrock player",
                        new IllegalStateException("Particle send origin"));
            }
            return true;
        }
        return false;
    }
}
