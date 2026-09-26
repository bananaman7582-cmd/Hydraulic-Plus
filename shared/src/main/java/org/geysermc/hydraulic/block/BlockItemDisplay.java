package org.geysermc.hydraulic.block;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.geysermc.hydraulic.mixin.server.EntityAccessor;
import org.geysermc.hydraulic.mixin.server.ItemEntityAccessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Shows the item sitting on a block to Bedrock players, by putting a real item where the block would
 * have drawn one.
 * <p>
 * A cutting board holding a carrot, a pedestal holding a sword - Java draws these from the block
 * entity's own renderer, which is code, and Bedrock has nothing of the kind: a block can be given a
 * model but not a changing item on top of it. So instead of drawing it as part of the block, an item
 * is placed at that spot for Bedrock players alone. Java players never learn of it, because the
 * entity is not in the world at all - it exists only in the packets sent to one player.
 * <p>
 * <b>Nothing here knows what a cutting board is.</b> Block entities tell clients about themselves by
 * sending their state as NBT, and the ones that bother sending an item are, almost by definition,
 * the ones that display it - there is no other reason a client would need to know. So any block
 * entity whose data carries an item gets one shown, and a display pedestal from a mod nobody has
 * heard of works the same as Farmer's Delight does.
 * <p>
 * How many it shows is the one thing that cannot be worked out this way, since a closed container
 * describes its contents in exactly the same shape as a stove describes what is cooking on it. That
 * decision is left to {@link DisplayBlocks}, which shows one item unless it knows better.
 * <p>
 * What it cannot be is exact. Bedrock draws a dropped item bobbing and turning, so it hovers over the
 * board rather than lying flat on it. It is the right item, in the right place, updating as it
 * changes - which is a good deal closer than the empty board Bedrock showed before.
 */
public final class BlockItemDisplay {
    /**
     * Ids for the items we invent. Real entities are numbered upwards from zero as the world fills,
     * so counting down from the top stays clear of them for as long as any server will ever run.
     */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(Integer.MAX_VALUE);

    /**
     * The gap left between the block's surface and the item resting on it, so the two do not overlap.
     */
    private static final double CLEARANCE = 0.05;

    /**
     * How far apart items on the same block are placed, as a fraction of the block. Wide enough to
     * tell them apart, narrow enough that none of them strays over the edge.
     */
    private static final double SPREAD = 0.5;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Kinds of block already reported as carrying no item, so the log says each thing once.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private final Map<BlockPos, Shown> shown = new HashMap<>();

    /**
     * Brings what a Bedrock player sees at a block into line with what the block now holds.
     *
     * @param position the block that just described itself
     * @param data the block entity's data, as it was sent to the client
     * @param send how to reach the player being shown
     */
    public void update(@NotNull BlockPos position, @Nullable CompoundTag data, @NotNull String type,
                       double surface, @NotNull Consumer<Packet<?>> send) {
        List<ItemStack> items = new ArrayList<>();
        if (data != null) {
            findItems(data, 0, items, DisplayBlocks.shown(type));
        }

        Shown existing = this.shown.get(position);

        // Said once per kind of block, not per block. Whether a block entity describes its item at
        // all, and under what name, is up to the mod - so when one turns out to hold nothing this
        // says what it did send, which is the only way to tell "holds nothing" from "holds something
        // somewhere this does not look"
        if (items.isEmpty() && data != null && !data.isEmpty() && REPORTED.add(type)) {
            LOGGER.info("Block entity {} sends {} but no item was found in it", type, data.keySet());
        }

        if (items.isEmpty()) {
            if (existing != null) {
                this.shown.remove(position);
                send.accept(new ClientboundRemoveEntitiesPacket(existing.entityIds()));
            }

            return;
        }

        // How many there are decides where each one sits, so a block that gains or loses one has to
        // lay them all out again rather than leave the rest where they were
        if (existing != null && existing.items().size() == items.size()) {
            for (int slot = 0; slot < items.size(); slot++) {
                if (!ItemStack.matches(existing.items().get(slot), items.get(slot))) {
                    send.accept(itemData(existing.entityIds()[slot], items.get(slot)));
                }
            }

            this.shown.put(position, new Shown(existing.entityIds(), items));
            return;
        }

        if (existing != null) {
            send.accept(new ClientboundRemoveEntitiesPacket(existing.entityIds()));
        }

        int[] ids = new int[items.size()];
        for (int slot = 0; slot < items.size(); slot++) {
            ids[slot] = NEXT_ID.getAndDecrement();

            double[] offset = spread(slot, items.size());

            // Sat on top of the block itself rather than at a fixed height. A cutting board is barely
            // a sixteenth of a block tall, so anything chosen to clear a full block leaves the item
            // hanging in the air above it; asking the block how tall it is suits both
            send.accept(new ClientboundAddEntityPacket(ids[slot], UUID.randomUUID(),
                    position.getX() + 0.5 + offset[0], position.getY() + surface + CLEARANCE,
                    position.getZ() + 0.5 + offset[1],
                    0.0f, 0.0f, EntityTypes.ITEM, 0, Vec3.ZERO, 0.0));
            send.accept(itemData(ids[slot], items.get(slot)));
        }

        this.shown.put(position, new Shown(ids, items));
    }

    /**
     * Where one of several items sits on the block's face.
     * <p>
     * A block holding one thing puts it in the middle, which is where a cutting board's carrot
     * belongs. Several have to be told apart, so they are laid out in a grid across the face - a
     * stove with six things cooking on it reads as six things rather than as one item flickering
     * between them.
     *
     * @return how far to move the item along each of the two horizontal directions
     */
    @NotNull
    private static double[] spread(int slot, int count) {
        if (count <= 1) {
            return new double[] { 0.0, 0.0 };
        }

        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / columns);

        int column = slot % columns;
        int row = slot / columns;

        // Kept well inside the block, so items belonging to one block never look like they belong to
        // the one beside it
        double x = columns == 1 ? 0.0 : (column / (double) (columns - 1) - 0.5) * SPREAD;
        double z = rows == 1 ? 0.0 : (row / (double) (rows - 1) - 0.5) * SPREAD;
        return new double[] { x, z };
    }

    /**
     * Takes away whatever was being shown at a block, because the block itself has changed.
     * <p>
     * A block entity describes itself while it exists; when one is broken there is nothing left to
     * send a final word, so the item resting on it would be left hanging over the empty space forever.
     * Watching the block change is the only notice that comes.
     */
    public void forget(@NotNull BlockPos position, @NotNull Consumer<Packet<?>> send) {
        Shown gone = this.shown.remove(position);
        if (gone != null) {
            send.accept(new ClientboundRemoveEntitiesPacket(gone.entityIds()));
        }
    }

    /**
     * Forgets everything shown, telling the player to drop it too. Used when a player leaves or the
     * world changes under them, so nothing is left hanging in mid-air.
     */
    public void clear(@NotNull Consumer<Packet<?>> send) {
        if (this.shown.isEmpty()) {
            return;
        }

        int[] ids = this.shown.values().stream()
                .flatMapToInt(shown -> Arrays.stream(shown.entityIds()))
                .toArray();

        this.shown.clear();
        send.accept(new ClientboundRemoveEntitiesPacket(ids));
    }

    @NotNull
    private static Packet<?> itemData(int entityId, @NotNull ItemStack item) {
        return new ClientboundSetEntityDataPacket(entityId, List.of(
                SynchedEntityData.DataValue.create(ItemEntityAccessor.hydraulic$dataItem(), item),
                // As far as the client knows this is an item lying in the world, and an item falls.
                // Normally the server would stop it on the ground, but there is no entity here for the
                // server to stop - so left to itself it sinks through the block and out of sight
                SynchedEntityData.DataValue.create(EntityAccessor.hydraulic$dataNoGravity(), true)));
    }

    /**
     * Digs an item out of a block entity's data, wherever the mod happened to put it.
     * <p>
     * There is no agreed place for them - one mod writes {@code Item}, another {@code inventory} with
     * the stacks inside - so the shape of a stack is looked for rather than a particular name: a
     * compound naming an item that exists.
     * <p>
     * All of them are taken rather than the first. A stove cooking six things describes six, and
     * stopping at the first showed one item standing in for the lot - which looked less like a stove
     * in use than like one that could not make up its mind.
     *
     * @param tag the data to search
     * @param depth how far in we already are, to stop a deeply nested inventory costing real time
     * @param into where to collect what is found
     */
    private static void findItems(@NotNull CompoundTag tag, int depth, @NotNull List<ItemStack> into, int limit) {
        if (depth > 4 || into.size() >= limit) {
            return;
        }

        ItemStack direct = asItem(tag);
        if (!direct.isEmpty()) {
            into.add(direct);
            return; // this compound is the item; what hangs off it is the item's own data
        }

        for (String key : tag.keySet()) {
            if (into.size() >= limit) {
                return;
            }

            Tag child = tag.get(key);

            if (child instanceof CompoundTag compound) {
                findItems(compound, depth + 1, into, limit);
            } else if (child instanceof ListTag list) {
                // An inventory is written as a list of stacks even when it only ever holds one, so
                // searching compounds alone walks straight past a cutting board's only item
                for (CompoundTag entry : list.compoundStream().toList()) {
                    findItems(entry, depth + 1, into, limit);
                }
            }
        }
    }

    /**
     * Reads a compound as an item stack, if that is what it is.
     */
    @NotNull
    private static ItemStack asItem(@NotNull CompoundTag tag) {
        String id = tag.getString("id").orElse(null);
        if (id == null) {
            return ItemStack.EMPTY;
        }

        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return ItemStack.EMPTY;
        }

        Item item = BuiltInRegistries.ITEM.getValue(key);
        if (item == null) {
            return ItemStack.EMPTY;
        }

        return new ItemStack(item, Math.max(1, tag.getInt("count").orElse(1)));
    }

    /**
     * An item being shown at a block, and the id invented for it.
     */
    private record Shown(int[] entityIds, @NotNull List<ItemStack> items) {
    }
}
