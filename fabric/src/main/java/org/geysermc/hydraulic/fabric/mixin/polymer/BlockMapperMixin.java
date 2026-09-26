package org.geysermc.hydraulic.fabric.mixin.polymer;

import eu.pb4.polymer.core.api.block.BlockMapper;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.geysermc.hydraulic.fabric.polymer.BedrockAudience;
import org.geysermc.hydraulic.fabric.polymer.RealBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a Bedrock player a mapper that disguises nothing.
 * <p>
 * Every block Polymer swaps goes through the mapper belonging to whoever is being sent to, so this
 * is the one place that covers all of them. The entry point patched beside this is the common route
 * and would have been enough for most mods; this is what makes it hold for the ones that reach for
 * the mapper directly.
 */
@Pseudo
@Mixin(BlockMapper.class)
public interface BlockMapperMixin {
    @Inject(method = "getFrom(Lnet/fabricmc/fabric/api/networking/v1/context/PacketContext;)Leu/pb4/polymer/core/api/block/BlockMapper;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$realBlocksForBedrock(PacketContext context,
                                                       CallbackInfoReturnable<BlockMapper> cir) {
        if (BedrockAudience.isBedrock(context)) {
            cir.setReturnValue(RealBlocks.INSTANCE);
        }
    }
}
