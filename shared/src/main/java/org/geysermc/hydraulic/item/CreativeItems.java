package org.geysermc.hydraulic.item;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.geysermc.hydraulic.HydraulicImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Which items a player is actually meant to be able to get hold of.
 * <p>
 * Being registered is not the same as being real. A mod may register an item purely to carry a model
 * - one for the shape held in the hand, another for the shape lying in the inventory - and never mean
 * either to exist as a thing. Java hides them by simply leaving them out of every creative tab, which
 * is enough there because the tabs are the only way to ask for an item you have not found.
 * <p>
 * Bedrock's menu was being filled from the item registry instead, so those helpers arrived alongside
 * the real thing, sharing its name and doing nothing. Alex's Mobs keeps four of them beside its stink
 * ray, which is why there appeared to be five stink rays and only one of them worked.
 * <p>
 * <b>This fails open.</b> An empty answer, taken at face value, would mean hiding every item in the
 * game, so nothing is hidden unless the tabs have something in them to check against.
 * <p>
 * A dedicated server never fills the tabs in by itself - that is the client's doing, and a server has
 * no creative menu to show - so they are built here before being read. Without that they were empty
 * every time and this check quietly did nothing on every server it ran on.
 */
public final class CreativeItems {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Every item any creative tab offers, worked out once.
     */
    private static Set<Item> obtainable;

    private CreativeItems() {
    }

    /**
     * Whether a player could be given this item from the creative menu.
     */
    public static boolean isObtainable(@NotNull Item item) {
        Set<Item> known = obtainable;
        if (known == null) {
            known = gather();
            if (known == null) {
                // Asked before the server was up, so there is nothing to build the tabs from yet.
                // Deliberately not remembered, so that the next item to ask gets a real answer
                // rather than this one standing in for the rest of the run
                return true;
            }

            obtainable = known;
        }

        // Nothing to check against, so nothing is refused. See the note about failing open above
        return known.isEmpty() || known.contains(item);
    }

    /**
     * Fills the creative tabs in, which a dedicated server otherwise never does.
     * <p>
     * The tabs hold no items until something asks for them to be built, and on Java that something
     * is the client - a server has no creative menu of its own to show. So every tab was empty here
     * no matter how late it was asked, and the check below fell open on every dedicated server,
     * which is every server this runs on. Bedrock's menu is built from what Geyser is told at
     * startup, so this is the only chance to know.
     * <p>
     * Operator items are asked for as well: the question is what a mod put in a tab at all, not what
     * this or that player may take from it.
     */
    private static void buildTabs(@NotNull MinecraftServer server) {
        try {
            CreativeModeTabs.tryRebuildTabContents(
                    server.getWorldData().enabledFeatures(), true, server.registryAccess());
        } catch (Throwable t) {
            // A mod's own tab deciding it cannot be built is not a reason to stop; whatever the
            // other tabs managed is still worth reading
            LOGGER.debug("Could not build the creative tabs", t);
        }
    }

    /**
     * @return every item a creative tab offers, or null if the server is not up to be asked yet
     */
    @Nullable
    private static Set<Item> gather() {
        MinecraftServer server = HydraulicImpl.instance().server();
        if (server == null) {
            return null;
        }

        Set<Item> found = new HashSet<>();

        buildTabs(server);

        try {
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                for (ItemStack stack : tab.getDisplayItems()) {
                    found.add(stack.getItem());
                }
            }
        } catch (Throwable t) {
            // Asked before the tabs were built, or a tab that will not be read. Either way the answer
            // is to check nothing rather than to hide everything
            LOGGER.debug("Could not read the creative tabs; every item will be offered", t);
            return Set.of();
        }

        // Said out loud rather than quietly, because the two outcomes look identical from the outside:
        // an item hidden because no tab offers it, and an item shown because the tabs could not be
        // read at all, both end in the menu being whatever it was going to be anyway
        if (found.isEmpty()) {
            LOGGER.warn("The creative tabs could not be read, so every registered item will be offered "
                    + "to Bedrock - including any a mod registered only to hold a model");
        } else {
            LOGGER.info("{} item(s) are offered by a creative tab; anything else is a mod's own "
                    + "book-keeping and is kept out of Bedrock's menu", found.size());
        }

        return found;
    }
}
