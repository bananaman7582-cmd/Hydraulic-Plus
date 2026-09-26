package org.geysermc.hydraulic.mixin.server;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import org.geysermc.geyser.api.GeyserApi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes structurally ordinary modded inventories readable to Geyser.
 * <p>
 * A custom menu type is a registry value Bedrock cannot decode even when the menu itself is only a
 * grid of slots. For Bedrock players, menus that can be proven to be exactly one to six rows of nine
 * custom slots followed by the normal 36 player slots are advertised as a vanilla chest of the same
 * size. The original server menu remains open and continues to own all slot and click behavior.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Unique
    private static final Logger hydraulic$LOGGER = LogUtils.getLogger();
    @Unique
    private static final Set<Identifier> hydraulic$REPORTED_MENUS = ConcurrentHashMap.newKeySet();

    @ModifyArg(
            method = "openMenu",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/ClientboundOpenScreenPacket;<init>(ILnet/minecraft/world/inventory/MenuType;Lnet/minecraft/network/chat/Component;)V"
            ),
            index = 1
    )
    private MenuType<?> hydraulic$useReadableMenuType(MenuType<?> original,
                                                      @Local AbstractContainerMenu menu) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!GeyserApi.api().isBedrockPlayer(player.getUUID())
                && player.getUUID().getMostSignificantBits() != 0) {
            return original;
        }

        Identifier identifier = BuiltInRegistries.MENU.getKey(original);
        if (identifier == null || identifier.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return original;
        }

        MenuType<?> replacement = hydraulic$chestType(player, menu);
        if (replacement == null) {
            if (hydraulic$REPORTED_MENUS.add(identifier)) {
                hydraulic$LOGGER.warn(
                        "Cannot expose custom menu {} to Bedrock as a chest: its {} slots are not a safe chest layout",
                        identifier, menu.slots.size()
                );
            }
            return original;
        }

        if (hydraulic$REPORTED_MENUS.add(identifier)) {
            hydraulic$LOGGER.info("Exposing custom menu {} to Bedrock as {}", identifier,
                    BuiltInRegistries.MENU.getKey(replacement));
        }
        return replacement;
    }

    /**
     * Recognizes the convention used by normal chest-like mod menus: all machine/storage slots,
     * then the player's 27 inventory slots and 9 hotbar slots. Anything less exact is left alone so
     * Bedrock cannot send clicks to mismatched slot numbers.
     */
    @Nullable
    @Unique
    private static MenuType<?> hydraulic$chestType(@NotNull ServerPlayer player,
                                                   @NotNull AbstractContainerMenu menu) {
        int customSlots = menu.slots.size() - 36;
        if (customSlots < 9 || customSlots > 54 || customSlots % 9 != 0) {
            return null;
        }

        for (int index = 0; index < customSlots; index++) {
            if (menu.slots.get(index).container == player.getInventory()) {
                return null;
            }
        }
        for (int index = customSlots; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot.container != player.getInventory()) {
                return null;
            }
        }

        return switch (customSlots / 9) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> null;
        };
    }
}
