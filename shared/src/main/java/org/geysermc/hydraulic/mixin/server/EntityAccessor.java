package org.geysermc.hydraulic.mixin.server;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * Reaches the flag that tells a client an entity does not fall.
 * <p>
 * Needed by {@link org.geysermc.hydraulic.block.BlockItemDisplay}: an item shown on a block is a real
 * item as far as the client is concerned, and a real item falls. Since no such entity exists on the
 * server there is nothing to hold it up or land it, so it sinks through the block and keeps going.
 */
@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("DATA_NO_GRAVITY")
    static EntityDataAccessor<Boolean> hydraulic$dataNoGravity() {
        throw new AssertionError();
    }

    /**
     * The name shown above an entity, used to say what a stand-in really is.
     */
    @Accessor("DATA_CUSTOM_NAME")
    static EntityDataAccessor<Optional<Component>> hydraulic$dataCustomName() {
        throw new AssertionError();
    }

    @Accessor("DATA_CUSTOM_NAME_VISIBLE")
    static EntityDataAccessor<Boolean> hydraulic$dataCustomNameVisible() {
        throw new AssertionError();
    }
}
