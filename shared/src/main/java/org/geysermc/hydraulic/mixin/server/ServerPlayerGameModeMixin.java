package org.geysermc.hydraulic.mixin.server;

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swings a Bedrock player's arm when they use something that answers.
 * <p>
 * Bedrock swings the arm itself when it believes an interaction happened, and for a modded block -
 * which it only knows as a custom one - it often does not believe it. Saying so explicitly fills the
 * gap.
 * <p>
 * The important part is <i>when</i>. Doing this for every use swung the arm at any modded block,
 * including ones that do nothing when clicked, which is not how the game behaves. Java swings only
 * when the interaction was taken up by something, and {@link InteractionResult#consumesAction()} is
 * that answer - so a modded door swings and a modded plank does not.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void hydraulic$swingOnUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                      BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (player.getUUID().getMostSignificantBits() != 0) {
            return; // not a Bedrock player
        }

        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return;
        }

        player.connection.send(new ClientboundAnimatePacket(player,
                hand == InteractionHand.MAIN_HAND
                        ? ClientboundAnimatePacket.SWING_MAIN_HAND
                        : ClientboundAnimatePacket.SWING_OFF_HAND));
    }
}
