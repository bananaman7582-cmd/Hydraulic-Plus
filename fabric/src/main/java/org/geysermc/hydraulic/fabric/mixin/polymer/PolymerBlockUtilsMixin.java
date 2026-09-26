package org.geysermc.hydraulic.fabric.mixin.polymer;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.registries.BuiltInRegistries;
import org.geysermc.hydraulic.fabric.polymer.BedrockAudience;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves a modded block as itself when the player being sent it is on Bedrock.
 * <p>
 * This is the one place Polymer turns a modded block into the vanilla one it will be disguised as,
 * so it is the one place the two audiences part company. Java players fall through untouched and
 * still get their disguise.
 */
@Pseudo
@Mixin(PolymerBlockUtils.class)
public class PolymerBlockUtilsMixin {
    @Inject(method = "getPolymerBlockState", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$keepRealBlockForBedrock(BlockState state, PacketContext context,
                                                          CallbackInfoReturnable<BlockState> cir) {
        boolean bedrock = BedrockAudience.isBedrock(context);

        if (bedrock) {
            cir.setReturnValue(state);
        }
    }
}
