package org.geysermc.hydraulic.menu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MenuLayoutTest {
    @Test
    void mapsSparseMachineSlotsAndReorderedPlayerSlots() {
        List<MenuLayout.SlotPoint> slots = new ArrayList<>();
        slots.add(new MenuLayout.SlotPoint(0, 0, false, false, true, 8, 18));
        slots.add(new MenuLayout.SlotPoint(1, 1, false, false, true, 44, 36));

        List<MenuLayout.SlotPoint> player = playerSlots(2, 3);
        Collections.reverse(player);
        slots.addAll(player);

        MenuLayout.Layout layout = MenuLayout.analyze(slots);
        assertNotNull(layout);
        assertEquals(3, layout.rows());
        assertEquals(0, layout.redirectedSlots().get(0));
        assertEquals(1, layout.redirectedSlots().get(11));
        assertEquals(2, layout.redirectedSlots().get(27));
        assertEquals(29, layout.redirectedSlots().get(54));
    }

    @Test
    void rejectsOverlappingMachineSlots() {
        List<MenuLayout.SlotPoint> slots = new ArrayList<>();
        slots.add(new MenuLayout.SlotPoint(0, 0, false, false, true, 8, 18));
        slots.add(new MenuLayout.SlotPoint(1, 1, false, false, true, 8, 18));
        slots.addAll(playerSlots(2, 3));

        assertNull(MenuLayout.analyze(slots));
    }

    @Test
    void mapsAlexsCavesNuclearFurnaceToFunctionalChestCells() {
        List<MenuLayout.SlotPoint> slots = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            slots.add(new MenuLayout.SlotPoint(index, index, false, false, true, 30 + index, 40));
        }
        slots.addAll(playerSlots(5, 3));

        MenuLayout.Layout layout = KnownMenuLayouts.find(
                "com.github.alexmodguy.alexscaves.server.inventory.NuclearFurnaceMenu", slots);
        assertNotNull(layout);
        assertEquals(0, layout.redirectedSlots().get(3));
        assertEquals(3, layout.redirectedSlots().get(16));
        assertEquals(4, layout.redirectedSlots().get(2));
    }

    private static List<MenuLayout.SlotPoint> playerSlots(int firstMenuIndex, int rows) {
        List<MenuLayout.SlotPoint> slots = new ArrayList<>();
        int menuIndex = firstMenuIndex;
        int mainTop = 31 + rows * 18;
        for (int inventoryIndex = 9; inventoryIndex < 36; inventoryIndex++) {
            slots.add(new MenuLayout.SlotPoint(menuIndex++, inventoryIndex, true, false, true,
                    8 + ((inventoryIndex - 9) % 9) * 18,
                    mainTop + ((inventoryIndex - 9) / 9) * 18));
        }
        for (int inventoryIndex = 0; inventoryIndex < 9; inventoryIndex++) {
            slots.add(new MenuLayout.SlotPoint(menuIndex++, inventoryIndex, true, false, true,
                    8 + inventoryIndex * 18, mainTop + 58));
        }
        return slots;
    }
}
