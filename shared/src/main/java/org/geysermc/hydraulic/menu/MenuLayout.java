package org.geysermc.hydraulic.menu;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recognizes custom menus that can be represented without lying about slot positions.
 *
 * <p>Bedrock only understands the vanilla chest layouts. Mod menus commonly put the player's
 * inventory in an unusual order and leave visual gaps between machine slots. This analyzer maps
 * those layouts onto a one-to-six row chest while rejecting anything ambiguous or interactive
 * outside of ordinary item slots.</p>
 */
public final class MenuLayout {
    private MenuLayout() {
    }

    public record SlotPoint(int menuIndex, int containerIndex, boolean playerInventory,
                            boolean fake, boolean active, int x, int y) {
    }

    public record Layout(int rows, Map<Integer, Integer> redirectedSlots) {
        public Layout {
            redirectedSlots = Map.copyOf(redirectedSlots);
        }
    }

    /**
     * A compact fallback for ordinary slot-only machines whose Java artwork is not a chest grid.
     * Every slot retains its original server index; only its position in the Bedrock chest changes.
     * Button-driven screens must be rejected by the caller before selecting this layout.
     */
    @Nullable
    public static Layout compact(List<SlotPoint> slots) {
        List<SlotPoint> machine = slots.stream().filter(slot -> !slot.playerInventory())
                .sorted(Comparator.comparingInt(SlotPoint::y).thenComparingInt(SlotPoint::x)
                        .thenComparingInt(SlotPoint::menuIndex)).toList();
        if (machine.isEmpty() || machine.size() > 54 || slots.size() != machine.size() + 36) {
            return null;
        }
        Set<Integer> menuIndexes = new HashSet<>();
        Set<Integer> inventoryIndexes = new HashSet<>();
        Set<Long> positions = new HashSet<>();
        for (SlotPoint slot : slots) {
            if (slot.fake() || !slot.active() || slot.menuIndex() < 0 || !menuIndexes.add(slot.menuIndex())) {
                return null;
            }
            if (slot.playerInventory()) {
                if (slot.containerIndex() < 0 || slot.containerIndex() > 35
                        || !inventoryIndexes.add(slot.containerIndex())) {
                    return null;
                }
            } else if (!positions.add(((long) slot.x() << 32) | (slot.y() & 0xffffffffL))) {
                // Overlapping slots can be alternative controls rather than independent inventory.
                return null;
            }
        }
        if (inventoryIndexes.size() != 36) {
            return null;
        }
        int rows = (machine.size() + 8) / 9;
        Map<Integer, Integer> redirects = new HashMap<>();
        for (int index = 0; index < machine.size(); index++) {
            redirects.put(index, machine.get(index).menuIndex());
        }
        for (SlotPoint slot : slots) {
            if (slot.playerInventory()) {
                int index = slot.containerIndex();
                redirects.put(rows * 9 + (index < 9 ? 27 + index : index - 9), slot.menuIndex());
            }
        }
        return new Layout(rows, redirects);
    }

    @Nullable
    public static Layout analyze(List<SlotPoint> slots) {
        List<SlotPoint> playerSlots = slots.stream().filter(SlotPoint::playerInventory).toList();
        List<SlotPoint> machineSlots = slots.stream().filter(slot -> !slot.playerInventory()).toList();
        if (playerSlots.size() != 36 || machineSlots.isEmpty() || machineSlots.size() > 54) {
            return null;
        }

        Set<Integer> inventoryIndexes = new HashSet<>();
        for (SlotPoint slot : playerSlots) {
            if (slot.fake() || !slot.active() || slot.containerIndex() < 0 || slot.containerIndex() > 35
                    || !inventoryIndexes.add(slot.containerIndex())) {
                return null;
            }
        }
        if (inventoryIndexes.size() != 36) {
            return null;
        }

        SlotPoint firstMainSlot = playerSlots.stream()
                .filter(slot -> slot.containerIndex() == 9)
                .findFirst().orElse(null);
        if (firstMainSlot == null || (firstMainSlot.y() - 31) % 18 != 0) {
            return null;
        }
        int rows = (firstMainSlot.y() - 31) / 18;
        if (rows < 1 || rows > 6) {
            return null;
        }

        int mainTop = 31 + rows * 18;
        for (SlotPoint slot : playerSlots) {
            int inventoryIndex = slot.containerIndex();
            int expectedX;
            int expectedY;
            if (inventoryIndex < 9) {
                expectedX = 8 + inventoryIndex * 18;
                expectedY = mainTop + 58;
            } else {
                expectedX = 8 + ((inventoryIndex - 9) % 9) * 18;
                expectedY = mainTop + ((inventoryIndex - 9) / 9) * 18;
            }
            if (slot.x() != expectedX || slot.y() != expectedY) {
                return null;
            }
        }

        Map<Integer, Integer> redirects = new HashMap<>();
        for (SlotPoint slot : machineSlots) {
            if (slot.fake() || !slot.active() || (slot.x() - 8) % 18 != 0 || (slot.y() - 18) % 18 != 0) {
                return null;
            }
            int column = (slot.x() - 8) / 18;
            int row = (slot.y() - 18) / 18;
            if (column < 0 || column >= 9 || row < 0 || row >= rows
                    || redirects.put(row * 9 + column, slot.menuIndex()) != null) {
                return null;
            }
        }

        int playerStart = rows * 9;
        for (SlotPoint slot : playerSlots) {
            int inventoryIndex = slot.containerIndex();
            int guiIndex = inventoryIndex < 9
                    ? playerStart + 27 + inventoryIndex
                    : playerStart + inventoryIndex - 9;
            if (redirects.put(guiIndex, slot.menuIndex()) != null) {
                return null;
            }
        }
        return new Layout(rows, redirects);
    }
}
