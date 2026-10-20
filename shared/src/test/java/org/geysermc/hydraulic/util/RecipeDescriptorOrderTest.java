package org.geysermc.hydraulic.util;

import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipeDescriptorOrderTest {
    private static final ItemDescriptorWithCount CUSTOM_ITEM = new ItemDescriptorWithCount(
            new DefaultDescriptor(new SimpleItemDefinition("enderscape:celestial_planks", 1521, true), 0), 1);
    private static final ItemDescriptorWithCount TAG = new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:planks"), 1);

    @Test
    void emptySlotsSortBeforeCustomItemsAndTags() {
        assertEquals(-1, RecipeDescriptorOrder.compareEmpty(ItemDescriptorWithCount.EMPTY, CUSTOM_ITEM));
        assertEquals(1, RecipeDescriptorOrder.compareEmpty(CUSTOM_ITEM, ItemDescriptorWithCount.EMPTY));
        assertEquals(0, RecipeDescriptorOrder.compareEmpty(ItemDescriptorWithCount.EMPTY, ItemDescriptorWithCount.EMPTY));
        assertEquals(-1, RecipeDescriptorOrder.compareEmpty(ItemDescriptorWithCount.EMPTY, TAG));
        assertNull(RecipeDescriptorOrder.compareEmpty(CUSTOM_ITEM, TAG));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shapedRecipeSortPreservesEmptySlotsAndOriginalNonemptyOrdering() throws ReflectiveOperationException {
        Class<?> type = Class.forName("org.geysermc.geyser.inventory.recipe.RecipeUtil$ItemDescriptorWithCountComparator");
        Field field = type.getDeclaredField("INSTANCE");
        field.setAccessible(true);
        Comparator<ItemDescriptorWithCount> original = (Comparator<ItemDescriptorWithCount>) field.get(null);
        Comparator<ItemDescriptorWithCount> fixed = (left, right) -> {
            Integer empty = RecipeDescriptorOrder.compareEmpty(left, right);
            return empty == null ? original.compare(left, right) : empty;
        };
        var recipe = new ArrayList<>(List.of(CUSTOM_ITEM, ItemDescriptorWithCount.EMPTY, TAG,
                CUSTOM_ITEM, ItemDescriptorWithCount.EMPTY));
        recipe.sort(fixed);
        assertEquals(List.of(ItemDescriptorWithCount.EMPTY, ItemDescriptorWithCount.EMPTY, TAG,
                CUSTOM_ITEM, CUSTOM_ITEM), recipe);
        for (var a : recipe) {
            for (var b : recipe) {
                assertEquals(-Integer.signum(fixed.compare(b, a)), Integer.signum(fixed.compare(a, b)));
            }
        }
    }
}
