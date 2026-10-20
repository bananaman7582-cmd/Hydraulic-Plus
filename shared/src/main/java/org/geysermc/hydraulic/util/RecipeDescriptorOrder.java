package org.geysermc.hydraulic.util;

import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.InvalidDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.jetbrains.annotations.Nullable;

/** Extends Geyser's recipe ordering to include empty slots in shaped modded recipes. */
public final class RecipeDescriptorOrder {
    private RecipeDescriptorOrder() {
    }

    /** Returns null when Geyser's existing comparator should handle the pair. */
    public static @Nullable Integer compareEmpty(ItemDescriptorWithCount left, ItemDescriptorWithCount right) {
        boolean leftEmpty = left.getDescriptor() instanceof InvalidDescriptor;
        boolean rightEmpty = right.getDescriptor() instanceof InvalidDescriptor;
        return leftEmpty || rightEmpty ? Boolean.compare(rightEmpty, leftEmpty) : null;
    }
}
