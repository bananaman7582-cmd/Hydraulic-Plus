package org.geysermc.hydraulic.menu;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bedrock chest layouts for installed mods whose screens are not ordinary grids. */
public final class KnownMenuLayouts {
    private static final String NUCLEAR_FURNACE =
            "com.github.alexmodguy.alexscaves.server.inventory.NuclearFurnaceMenu";
    private static final String IMBUING_TABLE =
            "fuzs.illagerinvasion.common.world.inventory.ImbuingMenu";

    private KnownMenuLayouts() {
    }

    @Nullable
    public static MenuLayout.Layout find(String className, List<MenuLayout.SlotPoint> slots) {
        return switch (className) {
            // Alex's Caves order: input, rod, empty barrel, result, filled waste barrel.
            case NUCLEAR_FURNACE -> fixed(slots, 5, Map.of(3, 0, 21, 1, 20, 2, 16, 3, 2, 4));
            // Illager Invasion order: enchanted book, target item, hallowed gem, result.
            case IMBUING_TABLE -> fixed(slots, 4, Map.of(19, 0, 22, 1, 25, 2, 4, 3));
            default -> null;
        };
    }

    @Nullable
    private static MenuLayout.Layout fixed(List<MenuLayout.SlotPoint> slots, int machineCount,
                                            Map<Integer, Integer> machineGuiToMenu) {
        if (slots.size() != machineCount + 36) {
            return null;
        }
        Map<Integer, Integer> redirects = new HashMap<>(machineGuiToMenu);
        Set<Integer> inventoryIndexes = new HashSet<>();
        for (MenuLayout.SlotPoint slot : slots) {
            if (!slot.playerInventory()) {
                if (slot.menuIndex() >= machineCount || slot.fake() || !slot.active()) {
                    return null;
                }
                continue;
            }
            int inventoryIndex = slot.containerIndex();
            if (slot.fake() || !slot.active() || inventoryIndex < 0 || inventoryIndex > 35
                    || !inventoryIndexes.add(inventoryIndex)) {
                return null;
            }
            int guiIndex = inventoryIndex < 9 ? 54 + inventoryIndex : 27 + inventoryIndex - 9;
            redirects.put(guiIndex, slot.menuIndex());
        }
        return inventoryIndexes.size() == 36 ? new MenuLayout.Layout(3, redirects) : null;
    }
}
