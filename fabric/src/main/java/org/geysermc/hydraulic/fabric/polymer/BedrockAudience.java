package org.geysermc.hydraulic.fabric.polymer;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Tells apart the two audiences a server running both Polymer and Hydraulic is speaking to.
 * <p>
 * The two solve the same problem from opposite ends. Polymer dresses modded content up as vanilla so
 * that an unmodified <i>Java</i> client can see something sensible; Hydraulic converts it properly
 * and ships it to Bedrock in a resource pack. Neither is wrong, but a Bedrock player given both
 * answers sees the disguise, because it arrives already applied - and a barrier block is a barrier
 * block no matter how good the pack is.
 * <p>
 * Polymer decides per packet, and every decision carries who it is for, so the two can be told apart
 * rather than one of them turned off: Java keeps its Polymer patches and Bedrock gets its Hydraulic
 * ones.
 */
// Deliberately NOT in the mixin package. Mixin reserves that package whole - every class in it is
// taken to be a mixin and refuses to load as an ordinary class - so a helper called from a mixin
// must live outside it. Put back and the first item packet sent to any player throws
// IllegalClassLoadError mid-encode and drops them from the server
public final class BedrockAudience {


    private BedrockAudience() {
    }

    /**
     * Whether this packet is bound for a Bedrock player.
     * <p>
     * Floodgate gives Bedrock players a UUID whose most significant bits are zero, which is how
     * Floodgate itself identifies them; a player who has linked their Bedrock account to a Java one
     * keeps their Java UUID and so is not matched here - which is correct, since Geyser sends them
     * the same way regardless of what their account is linked to.
     * <p>
     * The connection is asked before the profile is. Both should say the same thing, but the profile
     * is put into the context at one particular moment during login, whereas the connection is there
     * from the beginning - and the player Geyser connects arrives by a route of its own. Asking the
     * connection also matches how the rest of Hydraulic decides who is on Bedrock, so the two cannot
     * disagree about the same player.
     * <p>
     * A packet with nobody attached is not assumed to be anyone's. Polymer sends those while
     * building things that are not for a particular player, and answering "Bedrock" there would
     * quietly undo the disguise for Java players too.
     */
    public static boolean isBedrock(@Nullable PacketContext context) {
        if (context == null) {
            return decide(null, "a packet addressed to nobody");
        }

        Connection connection = context.get(PacketContext.CONNECTION);
        if (connection != null
                && connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
            return decide(listener.getPlayer().getUUID(), "the connection");
        }

        GameProfile profile = context.get(PacketContext.GAME_PROFILE);
        if (profile != null) {
            return decide(profile.id(), "the profile");
        }

        return decide(null, "a context naming neither connection nor profile");
    }

    /**
     * Whether this packet listener belongs to a Bedrock player.
     * <p>
     * The certain form of the question. Where Polymer hands over the listener itself there is nothing
     * to infer - the player it belongs to is the player being sent to.
     */
    public static boolean isBedrock(@Nullable ServerCommonPacketListenerImpl listener) {
        if (listener instanceof ServerGamePacketListenerImpl game) {
            return decide(game.getPlayer().getUUID(), "the listener it was handed");
        }

        return false;
    }

    /**
     * A Floodgate UUID has zero most significant bits, which is how a Bedrock player is told apart.
     *
     * @param source which part of the context the id came from, for the sake of the caller reading
     */
    private static boolean decide(@Nullable UUID id, @NotNull String source) {
        return id != null && id.getMostSignificantBits() == 0;
    }

}
