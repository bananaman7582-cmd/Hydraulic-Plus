package org.geysermc.hydraulic.menu;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedirectedChestMenuTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Registry bootstrap alone does not run 26.2's data-pack component binding phase.
        for (var item : new net.minecraft.world.item.Item[] {Items.DIAMOND, Items.IRON_INGOT}) {
            if (!item.builtInRegistryHolder().areComponentsBound()) {
                item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                        .set(DataComponents.MAX_STACK_SIZE, 64).build());
            }
        }
    }

    @Test
    void redirectsClicksAndOutsideDragEventsButDoesNotForwardFiller() {
        var source = new Machine();
        var menu = new RedirectedChestMenu(source, new MenuLayout.Layout(1, Map.of(4, 0)));
        menu.setCarried(new ItemStack(Items.DIAMOND, 3));
        menu.clicked(4, 0, ContainerInput.PICKUP, null);
        assertEquals(0, source.lastSlot);
        assertEquals(3, source.getCarried().getCount());
        menu.clicked(5, 0, ContainerInput.PICKUP, null);
        assertEquals(1, source.clicks);
        menu.clicked(-999, 0, ContainerInput.QUICK_CRAFT, null);
        assertEquals(-999, source.lastSlot);
        assertEquals(ContainerInput.QUICK_CRAFT, source.lastInput);
    }

    @Test
    void slotContentsAndMachineTickHookRemainBackedByOriginalMenu() {
        var source = new Machine();
        var menu = new RedirectedChestMenu(source, new MenuLayout.Layout(1, Map.of(4, 0)));
        source.getSlot(0).set(new ItemStack(Items.IRON_INGOT, 2));
        assertSame(source.getSlot(0).getItem(), menu.getSlot(4).getItem());
        menu.broadcastChanges();
        assertTrue(source.broadcasts > 0);
        assertTrue(menu.getSlot(5).getItem().isEmpty());
        assertFalse(menu.getSlot(5).mayPlace(new ItemStack(Items.DIAMOND)));
    }

    private static class Machine extends AbstractContainerMenu {
        int lastSlot;
        int clicks;
        int broadcasts;
        ContainerInput lastInput;

        Machine() {
            super(MenuType.GENERIC_9x1, 1);
            addSlot(new Slot(new SimpleContainer(1), 0, 17, 47));
        }

        @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
            lastSlot = slot;
            lastInput = input;
            clicks++;
        }
        @Override public void broadcastChanges() { broadcasts++; super.broadcastChanges(); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
