package org.geysermc.hydraulic.mixin.ext;

import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.geysermc.hydraulic.util.RecipeDescriptorOrder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.geysermc.geyser.inventory.recipe.RecipeUtil$ItemDescriptorWithCountComparator", remap = false)
public class RecipeComparatorMixin {
    // Class literals are relocated reliably in the Fabric jar. A string selector containing the
    // same descriptor twice can be only partly rewritten by the shading plugin.
    @Inject(target = @Desc(value = "compare", args = {ItemDescriptorWithCount.class, ItemDescriptorWithCount.class}, ret = int.class),
            at = @At("HEAD"), cancellable = true)
    private void hydraulic$compareEmptySlots(ItemDescriptorWithCount left, ItemDescriptorWithCount right,
                                             CallbackInfoReturnable<Integer> cir) {
        Integer result = RecipeDescriptorOrder.compareEmpty(left, right);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
