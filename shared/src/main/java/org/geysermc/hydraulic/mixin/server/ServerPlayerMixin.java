package org.geysermc.hydraulic.mixin.server;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.geysermc.hydraulic.util.BedrockPlayers;
import org.geysermc.hydraulic.menu.MenuLayout;
import org.geysermc.hydraulic.menu.KnownMenuLayouts;
import org.geysermc.hydraulic.menu.RedirectedChestMenu;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Gives safe, ordinary modded inventories a functional vanilla chest facade for Bedrock. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Unique
    private static final Logger hydraulic$LOGGER = LogUtils.getLogger();
    @Unique
    private static final Set<Identifier> hydraulic$REPORTED_MENUS = ConcurrentHashMap.newKeySet();

    /**
     * Replaces the local before Minecraft sends, initializes, and installs it. Merely changing the
     * packet's menu type is unsafe because Bedrock's slot numbers can differ from the server menu.
     */
    @ModifyVariable(method = "openMenu", at = @At("STORE"), ordinal = 0)
    private AbstractContainerMenu hydraulic$createBedrockMenu(AbstractContainerMenu original) {
        if (original == null) {
            return null;
        }
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!hydraulic$isBedrock(player)) {
            return original;
        }

        Identifier identifier = BuiltInRegistries.MENU.getKey(original.getType());
        if (identifier == null || identifier.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return original;
        }

        if (hydraulic$overridesButtons(original)) {
            hydraulic$reportUnsupported(identifier,
                    "it uses menu buttons that a Bedrock chest cannot represent");
            return original;
        }

        List<MenuLayout.SlotPoint> points = new ArrayList<>(original.slots.size());
        for (int index = 0; index < original.slots.size(); index++) {
            Slot slot = original.slots.get(index);
            points.add(new MenuLayout.SlotPoint(index, slot.getContainerSlot(),
                    slot.container == player.getInventory(), slot.isFake(), slot.isActive(), slot.x, slot.y));
        }
        MenuLayout.Layout layout = MenuLayout.analyze(points);
        if (layout == null) {
            layout = KnownMenuLayouts.find(original.getClass().getName(), points);
        }
        if (layout == null) {
            layout = MenuLayout.compact(points);
        }
        if (layout == null) {
            hydraulic$reportUnsupported(identifier,
                    "its slot geometry cannot be represented safely by a Bedrock chest");
            return original;
        }

        if (hydraulic$REPORTED_MENUS.add(identifier)) {
            hydraulic$LOGGER.info("Exposing custom menu {} to Bedrock as a redirected {}-row chest",
                    identifier, layout.rows());
        }
        return new RedirectedChestMenu(original, layout);
    }

    @Unique
    private static boolean hydraulic$isBedrock(ServerPlayer player) {
        return BedrockPlayers.isBedrock(player.getUUID());
    }

    @Unique
    private static boolean hydraulic$overridesButtons(AbstractContainerMenu menu) {
        try {
            Method method = menu.getClass().getMethod("clickMenuButton", Player.class, int.class);
            return method.getDeclaringClass() != AbstractContainerMenu.class;
        } catch (ReflectiveOperationException ignored) {
            return true;
        }
    }

    @Unique
    private static void hydraulic$reportUnsupported(Identifier identifier, String reason) {
        if (hydraulic$REPORTED_MENUS.add(identifier)) {
            hydraulic$LOGGER.warn("Cannot expose custom menu {} to Bedrock: {}", identifier, reason);
        }
    }
}
