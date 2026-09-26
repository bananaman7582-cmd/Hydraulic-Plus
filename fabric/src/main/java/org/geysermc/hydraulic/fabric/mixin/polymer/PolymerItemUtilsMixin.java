package org.geysermc.hydraulic.fabric.mixin.polymer;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.geysermc.hydraulic.fabric.polymer.BedrockAudience;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves a modded item as itself when the player being sent it is on Bedrock.
 * <p>
 * The same parting of ways as the block side, at the point Polymer swaps a modded item for a vanilla
 * one wearing a model. Both overloads are covered because Polymer picks between them by whether a
 * tooltip is being built, and an item that keeps its identity in one and loses it in the other shows
 * up as an item that changes when you look at it.
 */
@Pseudo
@Mixin(PolymerItemUtils.class)
public class PolymerItemUtilsMixin {
    @Inject(method = "getPolymerItemStack(Lnet/minecraft/world/item/ItemStack;Lnet/fabricmc/fabric/api/networking/v1/context/PacketContext;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$keepRealItemForBedrock(ItemStack stack, PacketContext context,
                                                         HolderLookup.Provider lookup,
                                                         CallbackInfoReturnable<ItemStack> cir) {
        if (BedrockAudience.isBedrock(context)) {
            cir.setReturnValue(stack);
        }
    }

    @Inject(method = "getPolymerItemStack(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/TooltipFlag;Lnet/fabricmc/fabric/api/networking/v1/context/PacketContext;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$keepRealItemForBedrock(ItemStack stack, TooltipFlag flag,
                                                         PacketContext context, HolderLookup.Provider lookup,
                                                         CallbackInfoReturnable<ItemStack> cir) {
        if (BedrockAudience.isBedrock(context)) {
            cir.setReturnValue(stack);
        }
    }

    /**
     * Says that nothing is a thing Polymer should stand in for, when the player being sent to is on
     * Bedrock.
     *
     * <p>The entry point above is where a stand-in is built, and cancelling it covers the mods that
     * ask for one the usual way. This is the question Polymer asks <i>before</i> that, and the answer
     * other parts of it act on without ever building a stack - the inventory synchroniser is one -
     * so it is closed here too rather than trusting that they all take the same road.
     */
    @Inject(method = "isPolymerServerItem(Lnet/minecraft/world/item/ItemInstance;Lnet/fabricmc/fabric/api/networking/v1/context/PacketContext;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$nothingStandsInForBedrock(ItemInstance instance, PacketContext context,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (BedrockAudience.isBedrock(context)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Keeps a modded item real where Polymer re-sends the slot after it is used, not only where the
     * whole inventory is drawn.
     * <p>
     * This is the road the other patches were missing, and the one that matches the symptom exactly.
     * A held item looks right until it is <i>used</i> - a block placed, a spawn egg hatched - and then
     * turns into a trial key. The reason is that using an item makes the client guess at the result
     * and the server correct it, and that correction is a single slot re-sent through here, by
     * {@code ServerGamePacketListenerImpl}, rather than through the stack builder the rest of the
     * inventory goes through. It answers with the item and the model the client should show, so for
     * Bedrock it is answered with the item itself and no model, which is what an undisguised item is.
     * <p>
     * The other three overloads all lead here, so this is the only one that has to be caught.
     */
    @Inject(method = "getItemSafely(Leu/pb4/polymer/core/api/item/PolymerItem;Lnet/minecraft/world/item/ItemStack;Lnet/fabricmc/fabric/api/networking/v1/context/PacketContext;ILnet/minecraft/core/HolderLookup$Provider;)Leu/pb4/polymer/core/api/item/PolymerItemUtils$ItemWithMetadata;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void hydraulic$keepRealSlotForBedrock(PolymerItem polymerItem, ItemStack stack,
                                                         PacketContext context, int maxLength,
                                                         HolderLookup.Provider lookup,
                                                         CallbackInfoReturnable<PolymerItemUtils.ItemWithMetadata> cir) {
        if (BedrockAudience.isBedrock(context)) {
            cir.setReturnValue(new PolymerItemUtils.ItemWithMetadata(stack.getItem(), null));
        }
    }
}
