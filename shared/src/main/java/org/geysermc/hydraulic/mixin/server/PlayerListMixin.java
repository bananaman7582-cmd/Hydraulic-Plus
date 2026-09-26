package org.geysermc.hydraulic.mixin.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.geysermc.hydraulic.entity.MissingEntityModels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.stream.Collectors;

/**
 * Tells an operator when a mod's mobs will be invisible to Bedrock players, and what to do about it.
 * <p>
 * Entity models live in client-only code, so a newly added entity mod has nothing the server can
 * read until the Java client has been opened once with that mod installed. Nothing about that is
 * visible in game - the mobs are simply absent - and the explanation would otherwise sit in a
 * startup log among thousands of other lines.
 * <p>
 * Only operators are told. A player who cannot restart the server can do nothing with this.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void hydraulic$warnAboutMissingEntityModels(Connection connection, ServerPlayer player,
                                                        CommonListenerCookie cookie, CallbackInfo ci) {
        PlayerList self = (PlayerList) (Object) this;
        if (!self.isOp(player.nameAndId())) {
            return;
        }

        int models = MissingEntityModels.models();
        if (models == 0 && MissingEntityModels.isEmpty()) {
            return; // nothing converted and nothing missing: no modded entities to speak of
        }

        // Say what worked as well as what did not. Whether the trip into a world paid off is the
        // whole question, and counting files in a folder is not a thing to ask anyone to do
        player.sendSystemMessage(Component.literal("[Hydraulic] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(models + " modded mob(s) converted - "
                                + MissingEntityModels.textures() + " with their own texture, "
                                + MissingEntityModels.animations() + " with a recorded walk.")
                        .withStyle(ChatFormatting.GREEN)));

        if (MissingEntityModels.isEmpty()) {
            return;
        }

        String mods = MissingEntityModels.mods().stream().sorted().collect(Collectors.joining(", "));

        player.sendSystemMessage(Component.literal("[Hydraulic] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(MissingEntityModels.count()
                                + " still have no model (" + mods + ").")
                        .withStyle(ChatFormatting.YELLOW)));

        // Only worth advising when nothing at all was read. Once a client has been round, whatever is
        // left is almost always something that has no model in Java either - a thrown item drawn as
        // its own sprite, a portal, a puff of something - and telling an operator to go and fetch
        // models that do not exist reads as a failure every time they join
        if (models == 0) {
            player.sendSystemMessage(Component.literal("[Hydraulic] ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("Models are read from the Java client. Load into a world "
                                    + "there once with these mods, then restart the server.")
                            .withStyle(ChatFormatting.GRAY)));
            return;
        }

        player.sendSystemMessage(Component.literal("[Hydraulic] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Those are usually projectiles and effects, which have no "
                                + "model in Java either - nothing more to fetch for them.")
                        .withStyle(ChatFormatting.GRAY)));
    }
}
