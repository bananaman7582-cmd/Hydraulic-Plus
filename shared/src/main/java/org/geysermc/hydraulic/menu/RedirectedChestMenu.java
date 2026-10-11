package org.geysermc.hydraulic.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/** A vanilla chest facade whose slots and clicks are backed by the original custom menu. */
public final class RedirectedChestMenu extends AbstractContainerMenu {
    private final AbstractContainerMenu wrapped;
    private final Map<Integer, Integer> redirects;

    public RedirectedChestMenu(AbstractContainerMenu wrapped, MenuLayout.Layout layout) {
        super(chestType(layout.rows()), wrapped.containerId);
        this.wrapped = wrapped;
        this.redirects = layout.redirectedSlots();

        int chestSlots = layout.rows() * 9;
        Container filler = new SimpleContainer(chestSlots);
        for (int guiIndex = 0; guiIndex < chestSlots + 36; guiIndex++) {
            Integer wrappedIndex = redirects.get(guiIndex);
            if (wrappedIndex == null) {
                addSlot(new InertSlot(filler, guiIndex, chestX(guiIndex), chestY(guiIndex)));
            } else {
                Slot source = wrapped.getSlot(wrappedIndex);
                addSlot(new RedirectedSlot(source, chestX(guiIndex), chestY(guiIndex)));
            }
        }
        setCarried(wrapped.getCarried());
    }

    private static MenuType<?> chestType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Unsupported chest row count: " + rows);
        };
    }

    private static int chestX(int guiIndex) {
        return 8 + (guiIndex % 9) * 18;
    }

    private static int chestY(int guiIndex) {
        return 18 + (guiIndex / 9) * 18;
    }

    @Override
    public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
        Integer wrappedIndex = redirects.get(slotIndex);
        if (wrappedIndex == null) {
            return;
        }
        wrapped.setCarried(getCarried());
        wrapped.clicked(wrappedIndex, button, input, player);
        setCarried(wrapped.getCarried());
        wrapped.broadcastChanges();
        broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Integer wrappedIndex = redirects.get(slotIndex);
        if (wrappedIndex == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = wrapped.quickMoveStack(player, wrappedIndex);
        setCarried(wrapped.getCarried());
        wrapped.broadcastChanges();
        broadcastChanges();
        return result;
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        return wrapped.clickMenuButton(player, button);
    }

    @Override
    public boolean stillValid(Player player) {
        return wrapped.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        wrapped.setCarried(getCarried());
        wrapped.removed(player);
    }

    @Override
    public void slotsChanged(Container container) {
        wrapped.slotsChanged(container);
        broadcastChanges();
    }

    private static final class InertSlot extends Slot {
        private InertSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    private static final class RedirectedSlot extends Slot {
        private final Slot delegate;

        private RedirectedSlot(Slot delegate, int x, int y) {
            super(delegate.container, delegate.getContainerSlot(), x, y);
            this.delegate = delegate;
        }

        @Override public boolean mayPlace(ItemStack stack) { return delegate.mayPlace(stack); }
        @Override public ItemStack getItem() { return delegate.getItem(); }
        @Override public boolean hasItem() { return delegate.hasItem(); }
        @Override public void setByPlayer(ItemStack stack) { delegate.setByPlayer(stack); }
        @Override public void setByPlayer(ItemStack stack, ItemStack previous) { delegate.setByPlayer(stack, previous); }
        @Override public void set(ItemStack stack) { delegate.set(stack); }
        @Override public void setChanged() { delegate.setChanged(); }
        @Override public int getMaxStackSize() { return delegate.getMaxStackSize(); }
        @Override public int getMaxStackSize(ItemStack stack) { return delegate.getMaxStackSize(stack); }
        @Override public ItemStack remove(int amount) { return delegate.remove(amount); }
        @Override public boolean mayPickup(Player player) { return delegate.mayPickup(player); }
        @Override public boolean isActive() { return delegate.isActive(); }
        @Override public boolean isHighlightable() { return delegate.isHighlightable(); }
        @Override public boolean isFake() { return delegate.isFake(); }
        @Override public void onTake(Player player, ItemStack stack) { delegate.onTake(player, stack); }
    }
}
