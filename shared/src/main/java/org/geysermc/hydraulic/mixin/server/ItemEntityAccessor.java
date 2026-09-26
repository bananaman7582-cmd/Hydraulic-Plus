package org.geysermc.hydraulic.mixin.server;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the field an item entity keeps its stack in.
 * <p>
 * Telling a client which item an entity is means naming the same slot the game does, and that slot
 * is private. It is needed to show the item on a block without there being an entity at all - see
 * {@link org.geysermc.hydraulic.block.BlockItemDisplay}.
 */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("DATA_ITEM")
    static EntityDataAccessor<ItemStack> hydraulic$dataItem() {
        throw new AssertionError();
    }
}
