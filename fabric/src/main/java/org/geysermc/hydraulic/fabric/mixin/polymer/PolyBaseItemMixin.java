package org.geysermc.hydraulic.fabric.mixin.polymer;

import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.geysermc.hydraulic.fabric.polymer.BedrockAudience;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a modded item's own identity in the packet's item id, where the disguise is otherwise
 * stamped as the bytes are written.
 * <p>
 * This is the road every earlier patch missed, and the log proved it: the item is kept whole
 * everywhere the packet is built, yet reaches Bedrock as a trial key with id 1533 - the id of the
 * vanilla trial key. The reason is that the id written to the wire does not come from the stack at
 * all; it comes from {@code getPolymerItem}, which polymer-patcher answers with {@code TRIAL_KEY}
 * for every modded item so a vanilla client sees something. Bedrock does not need that - Hydraulic
 * gives it the real item in its pack - so for a Bedrock recipient the item is left as itself and its
 * true id is written.
 * <p>
 * Polymer-patcher wraps every modded item in {@code PolyBaseItem}, so this one class covers them
 * all. It is named as a pseudo-target because polymer-patcher is not a dependency, and does nothing
 * when it is absent.
 */
@Pseudo
@Mixin(targets = "me.drex.polymerpatcher.item.PolyBaseItem")
public class PolyBaseItemMixin {
    @Inject(method = "getPolymerItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void hydraulic$keepRealIdForBedrock(ItemStack stack, PacketContext context,
                                                CallbackInfoReturnable<Item> cir) {
        if (BedrockAudience.isBedrock(context)) {
            cir.setReturnValue(stack.getItem());
        }
    }
}
