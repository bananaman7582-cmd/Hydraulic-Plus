package org.geysermc.hydraulic.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.entity.CustomBedrockEntityDefinition;
import org.geysermc.geyser.entity.properties.GeyserEntityProperties;
import org.geysermc.geyser.entity.properties.type.IntProperty;
import org.geysermc.hydraulic.Constants;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.hydraulic.HydraulicImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Makes Bedrock spawn a mod's mobs as themselves rather than as whatever the protocol could carry.
 * <p>
 * Two things have to line up for that. Geyser has to know the entity exists, which is what
 * registering a {@link CustomEntityDefinition} does - the name given here is the one the pack
 * defines in {@code entity/<name>.entity.json}. And each spawn has to be pointed at that definition
 * instead of the vanilla one it arrived as.
 * <p>
 * That second part is needed because of how the spawn gets here at all. An entity type travels as an
 * index into the entity registry, and Geyser's copy of that registry only has room for the vanilla
 * entries, so a modded entity's index runs off the end and the packet cannot be read - see
 * {@code ServerCommonPacketListenerImplMixin}, which swaps in a readable vanilla type so the spawn
 * survives the trip. By the time it arrives the original type is gone from the packet.
 * <p>
 * It is not gone from the server, though. Hydraulic runs inside the same process, so the entity that
 * was really spawned can simply be looked up by its uuid and asked what it is. No extra information
 * has to be smuggled alongside the packet.
 */
public class EntityListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final HydraulicImpl hydraulic;

    /**
     * The definition registered for each modded entity, so a spawn does not have to build one.
     */
    private final Map<Identifier, CustomEntityDefinition> definitions = new HashMap<>();

    public EntityListener(HydraulicImpl hydraulic) {
        this.hydraulic = hydraulic;
    }

    /**
     * Tells Geyser about every modded entity, whether or not the pack ended up with a model for it.
     * <p>
     * Registering one the pack has no model for costs nothing: Bedrock falls back to drawing nothing
     * rather than refusing the entity, and the alternative - deciding here which models made it into
     * which pack - would mean reaching across into another module's work.
     */
    @Subscribe
    public void onDefineEntities(GeyserDefineEntitiesEvent event) {
        for (Identifier entity : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (entity.getNamespace().equals("minecraft")) {
                continue;
            }

            org.geysermc.geyser.api.util.Identifier bedrock =
                    org.geysermc.geyser.api.util.Identifier.of(entity.getNamespace(), entity.getPath());

            CustomEntityDefinition definition = withAnimationProperty(bedrock, entity);

            event.register(definition);
            this.definitions.put(entity, definition);
        }

        if (!this.definitions.isEmpty()) {
            LOGGER.info("Registered {} modded entity definition(s) with Geyser", this.definitions.size());
        }
    }

    /**
     * Describes a mob to Geyser, with a number on it saying which animation it is performing.
     * <p>
     * Bedrock keeps a small set of named values on an entity that a resource pack can read while
     * deciding what to draw, and Geyser tells the client about them as the mob appears - no behaviour
     * pack involved. That is the whole mechanism for making an animation play: the pack says which
     * number means which animation, the server sets the number, and the client does the rest itself.
     * <p>
     * It is what is left after the direct approach failed. There is a packet whose entire purpose is
     * to play an animation on an entity, and it does nothing here; a number the client reads for
     * itself is the thing that has been shown to work.
     * <p>
     * A mob with no animations worth numbering is registered plainly, as before.
     */
    @NotNull
    private static CustomEntityDefinition withAnimationProperty(
            @NotNull org.geysermc.geyser.api.util.Identifier bedrock, @NotNull Identifier entity) {
        int count = AnimationIndex.forEntity(entity).size();
        if (count == 0) {
            return CustomEntityDefinition.of(bedrock);
        }

        try {
            GeyserEntityProperties properties = new GeyserEntityProperties();
            properties.add(AnimationIndex.PROPERTY, new IntProperty(
                    org.geysermc.geyser.api.util.Identifier.of(Constants.MOD_ID, "animation"),
                    count, 0, 0));

            return new CustomBedrockEntityDefinition(bedrock, properties);
        } catch (Throwable t) {
            // A mob that cannot carry the number simply does not play those animations, which is
            // where it was before any of this
            LOGGER.debug("Could not give {} an animation property", entity, t);
            return CustomEntityDefinition.of(bedrock);
        }
    }

    /**
     * Points a spawning entity at its own definition, if it turns out to be a modded one.
     */
    @Subscribe
    public void onSpawnEntity(ServerSpawnEntityEvent event) {
        if (this.definitions.isEmpty()) {
            return;
        }

        Identifier real = realType(event);
        if (real == null) {
            return;
        }

        // A mod whose models are built by something other than the game's own layer system - Alex's
        // Mobs and anything else on Citadel - has nothing that could be read, so the pack has no
        // definition to point at. Sending one there leaves the mob invisible; leaving it alone keeps
        // the vanilla stand-in it arrived as, which is at least something to see and fight
        if (MissingEntityModels.isMissing(real)) {
            return;
        }

        CustomEntityDefinition definition = this.definitions.get(real);
        if (definition != null) {
            event.definition(definition);
        }
    }

    /**
     * Asks the server what the entity actually is, since the packet no longer says.
     *
     * @param event the spawn on its way to a Bedrock player
     * @return the entity's real type, or null if it is vanilla or could not be found
     */
    @Nullable
    private Identifier realType(@NotNull ServerSpawnEntityEvent event) {
        MinecraftServer server = this.hydraulic.server();
        if (server == null) {
            return null;
        }

        // Any level will do - the lookup covers all of them, which matters because the entity is not
        // necessarily in the same dimension as the player watching it spawn
        Entity entity = server.overworld().getEntityInAnyDimension(event.uuid());
        if (entity == null) {
            return null;
        }

        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return type.getNamespace().equals("minecraft") ? null : type;
    }
}
