package org.geysermc.hydraulic.mixin.server;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.geysermc.hydraulic.block.BlockItemDisplay;
import org.geysermc.hydraulic.config.HydraulicConfig;
import org.geysermc.hydraulic.config.PolymerFilter;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.entity.MissingEntityModels;
import org.geysermc.hydraulic.entity.VanillaAncestry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rewrites packets on their way to Bedrock players so Geyser can actually read them.
 * <p>
 * Geyser bundles its own copy of MCProtocolLib, whose registries are fixed-size arrays built for
 * vanilla. A mod's entry lands past the end of one of those arrays, so reading it throws while the
 * packet is still being <i>deserialised</i>. That does not cost only the offending packet: packets
 * arrive batched, so unrelated neighbours in the same read are lost too, which is how a modded sound
 * once stopped doors from opening. Every case below rewrites the value into something vanilla-shaped
 * before it is ever sent, so nothing throws and the batch survives.
 * <p>
 * Java players are unaffected - all of it is gated on the player being a Bedrock one.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerImplMixin {
    @Unique
    private static final Logger hydraulic$LOGGER = LogUtils.getLogger();

    /**
     * Modded entity types already reported, so the log gets one line per type rather than one per
     * spawn.
     */
    @Unique
    private static final Set<Identifier> hydraulic$REPORTED_ENTITIES = new HashSet<>();

    /**
     * Where a mob keeps whether it is burning, sneaking, glowing or invisible. It is the first field
     * every entity has, which is what makes it safe to pass on when the rest cannot be.
     */
    @Unique
    private static final int hydraulic$SHARED_FLAGS = 0;

    /**
     * The items being shown on blocks to this one player. Held per connection, so it is forgotten
     * when they leave without anything having to remember to clean up.
     */
    @Unique
    private final BlockItemDisplay hydraulic$itemDisplay = new BlockItemDisplay();

    /**
     * Entities sent to this player under a borrowed vanilla type.
     * <p>
     * Their metadata still describes the modded entity it really is - more fields than the borrowed
     * type has, and of different kinds - so Geyser reads it against the wrong shape and throws. Every
     * one of those costs the packet and whatever was batched with it, which with a mod the size of
     * Alex's Mobs is a constant stream of lost packets. Knowing which entities are borrowed is what
     * lets that metadata be dropped instead.
     */
    @Unique
    private final Set<Integer> hydraulic$substituted = new HashSet<>();

    /**
     * What each stand-in is really meant to be, so it can be labelled with its own name.
     */
    @Unique
    private final Map<Integer, Component> hydraulic$names = new HashMap<>();

    /**
     * Holds back Polymer's own packets, which describe a world Bedrock is not being shown.
     * <p>
     * Separate from the rewriting below because this stops a packet rather than changing it, and the
     * two cannot be done in the same place.
     */
    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void hydraulic$dropPolymerPackets(Packet<?> packet, ChannelFutureListener whenSent, CallbackInfo ci) {
        if (!HydraulicConfig.get().hidePolymerFromBedrock) {
            return;
        }

        if (!((Object) this instanceof ServerGamePacketListenerImpl listener)
                || !hydraulic$isBedrockPlayer(listener)) {
            return;
        }

        if (PolymerFilter.isPolymer(packet)) {
            ci.cancel();
        }
    }

    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Packet<?> hydraulic$rewriteUnreadablePackets(Packet<?> packet) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener)
                || !hydraulic$isBedrockPlayer(listener)) {
            return packet;
        }

        // A block describing itself may be describing an item it is holding, which Bedrock has no way
        // to draw as part of a block. The packet is passed along untouched either way; what it says
        // is only used to decide whether to put an item there as well
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
            try {
                this.hydraulic$itemDisplay.update(blockEntity.getPos(), blockEntity.getTag(),
                        String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType())),
                        hydraulic$surfaceOf(listener, blockEntity.getPos()), listener::send);
            } catch (Throwable t) {
                // Showing an item is a nicety; never let it cost the packet it was read from
                hydraulic$LOGGER.debug("Could not show the item on the block at {}", blockEntity.getPos(), t);
            }
        }

        // A block being broken or replaced sends no block entity data - there is no block entity left
        // to describe itself - so nothing would ever tell the item shown on it to go away, and it
        // would hang in the air over the empty space
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            this.hydraulic$itemDisplay.forget(update.getPos(), listener::send);
        }

        return this.hydraulic$rewrite(packet);
    }

    /**
     * The part of a mob's metadata that means the same thing whatever the mob is.
     * <p>
     * Dropping all of it was the safe thing to do and cost more than it looked. Metadata is a list of
     * numbered fields, and the numbering runs from the general to the particular: every entity in the
     * game, modded or not, keeps the same handful at the front, and a mod's own fields are added after
     * those. Only the tail is unreadable against a borrowed type - the head is as true of a modded
     * bear as of a pig.
     * <p>
     * The first of those fields is the one carrying whether the mob is burning, sneaking, glowing or
     * invisible, so throwing it away meant a modded mob standing in a fire looked entirely comfortable
     * doing it.
     */
    @Unique
    @NotNull
    private static List<SynchedEntityData.DataValue<?>> hydraulic$universalData(
            @NotNull ClientboundSetEntityDataPacket packet) {
        List<SynchedEntityData.DataValue<?>> kept = new ArrayList<>();

        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            if (value.id() == hydraulic$SHARED_FLAGS) {
                kept.add(value);
            }
        }

        return kept;
    }

    /**
     * Whether a mod, rather than the game, provides this entity type.
     */
    @Unique
    private static boolean hydraulic$isModded(@NotNull EntityType<?> type) {
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return key != null && !key.getNamespace().equals("minecraft");
    }

    /**
     * How tall the block at this position stands, so an item can be put on top of it.
     * <p>
     * A cutting board is barely a sixteenth of a block; a pedestal is a whole one. Measuring rather
     * than assuming means an item rests on either.
     *
     * @return the height of the block's top face, or a whole block if it cannot be measured
     */
    @Unique
    private static double hydraulic$surfaceOf(@NotNull ServerGamePacketListenerImpl listener, @NotNull BlockPos position) {
        try {
            VoxelShape shape = listener.getPlayer().level().getBlockState(position)
                    .getShape(listener.getPlayer().level(), position);

            return shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /**
     * Picks the right rewrite for a packet, looking inside bundles for more.
     */
    @Unique
    private Packet<?> hydraulic$rewrite(@NotNull Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            return this.hydraulic$rewriteBundle(bundle);
        }

        if (packet instanceof ClientboundSoundPacket soundPacket) {
            return hydraulic$inlineModdedSound(soundPacket);
        }

        // A spawn and the metadata describing it travel together in one bundle, so both of these have
        // to be seen from in here rather than from the outside
        if (packet instanceof ClientboundSetEntityDataPacket data
                && this.hydraulic$substituted.contains(data.id())) {
            // Metadata for an entity whose type was swapped describes something the client is not
            // being shown, and cannot be read against the type it was sent as. Dropping it costs the
            // mob its finer details - whether it is a baby, which variant - and saves everything else.
            //
            // What can be put back is its name. A stand-in is a vanilla mob wearing none of the right
            // detail, and without a label there is nothing at all to say which modded creature it is
            // meant to be - every one of them reads as a stray zombie. Saying so is the difference
            // between a mob that looks wrong and a mob nobody can identify
            List<SynchedEntityData.DataValue<?>> kept = new ArrayList<>(hydraulic$universalData(data));

            Component name = this.hydraulic$names.get(data.id());
            if (name == null) {
                return new ClientboundSetEntityDataPacket(data.id(), kept);
            }

            kept.add(SynchedEntityData.DataValue.create(EntityAccessor.hydraulic$dataCustomName(),
                    Optional.of(name)));
            kept.add(SynchedEntityData.DataValue.create(EntityAccessor.hydraulic$dataCustomNameVisible(),
                    true));

            return new ClientboundSetEntityDataPacket(data.id(), kept);
        }

        if (packet instanceof ClientboundRemoveEntitiesPacket removed) {
            removed.getEntityIds().forEach(id -> {
                this.hydraulic$substituted.remove(id);
                this.hydraulic$names.remove(id);
            });
            return packet;
        }

        if (packet instanceof ClientboundAddEntityPacket addEntityPacket) {
            if (hydraulic$isModded(addEntityPacket.getType())) {
                this.hydraulic$substituted.add(addEntityPacket.getId());

                // Named only where the name is all there is. A mob the pack has a model for is
                // restored to itself on arrival and needs no label - it looks like what it is - and
                // labelling those put a name tag over every modded creature in the world. It is the
                // ones with no model that arrive as a pig and have nothing to say what they are
                Identifier real = BuiltInRegistries.ENTITY_TYPE.getKey(addEntityPacket.getType());
                if (real != null && MissingEntityModels.isMissing(real)) {
                    this.hydraulic$names.put(addEntityPacket.getId(),
                            addEntityPacket.getType().getDescription());
                }
            }

            return hydraulic$substituteModdedEntity(addEntityPacket);
        }

        if (packet instanceof ClientboundUpdateAttributesPacket attributesPacket) {
            return hydraulic$dropModdedAttributes(attributesPacket);
        }

        if (packet instanceof ClientboundLevelParticlesPacket particlesPacket) {
            return hydraulic$substituteModdedParticle(particlesPacket);
        }

        if (packet instanceof ClientboundRecipeBookAddPacket recipePacket) {
            return hydraulic$filterModdedRecipes(recipePacket);
        }

        if (packet instanceof ClientboundUpdateMobEffectPacket effectPacket) {
            return hydraulic$substituteModdedEffect(effectPacket);
        }

        if (packet instanceof ClientboundRemoveMobEffectPacket removePacket) {
            Holder<MobEffect> vanilla = hydraulic$vanillaEffect(removePacket.effect());
            return vanilla == null ? packet
                    : new ClientboundRemoveMobEffectPacket(removePacket.entityId(), vanilla);
        }

        return packet;
    }

    /**
     * Sends a modded effect as the vanilla one it most resembles.
     * <p>
     * An effect travels as an index into the effect registry, and Geyser's copy of that registry is a
     * fixed list of the vanilla ones - so a mod's effect runs off the end and the packet is lost,
     * along with whatever was batched beside it. The effect itself still happens: it is applied on the
     * server, so a Bedrock player is poisoned or slowed exactly as a Java player is. What travels here
     * is only what the icon in the corner should say.
     * <p>
     * <b>Bedrock cannot be given a new effect.</b> Its effects are a closed set with no way for a pack
     * to add an icon or a name, so the choice is between showing a vanilla one and showing nothing.
     * The name is read for a likely match - a mod's {@code venom} shows as poison - because when the
     * guess is right the client's own handling of that effect is right too. Anything unrecognised
     * falls back to luck, which is chosen for doing nothing at all: an effect that changed movement
     * would have the client predicting a speed the server never granted.
     *
     * @param packet the effect on its way to a Bedrock player
     * @return the packet, naming a vanilla effect if a mod supplied it
     */
    @Unique
    private static Packet<?> hydraulic$substituteModdedEffect(@NotNull ClientboundUpdateMobEffectPacket packet) {
        Holder<MobEffect> vanilla = hydraulic$vanillaEffect(packet.getEffect());
        if (vanilla == null) {
            return packet;
        }

        return new ClientboundUpdateMobEffectPacket(packet.getEntityId(),
                new MobEffectInstance(vanilla, packet.getEffectDurationTicks(), packet.getEffectAmplifier(),
                        packet.isEffectAmbient(), packet.isEffectVisible(), packet.effectShowsIcon()),
                packet.shouldBlend());
    }

    /**
     * The vanilla effect to show in place of a modded one, or null if it is already vanilla.
     */
    @Unique
    @Nullable
    private static Holder<MobEffect> hydraulic$vanillaEffect(@NotNull Holder<MobEffect> effect) {
        Identifier key = effect.unwrapKey().map(k -> k.identifier()).orElse(null);
        if (key == null || key.getNamespace().equals("minecraft")) {
            return null;
        }

        String name = key.getPath();
        if (name.contains("poison") || name.contains("venom") || name.contains("toxic")) {
            return MobEffects.POISON;
        } else if (name.contains("wither") || name.contains("bleed") || name.contains("decay")) {
            return MobEffects.WITHER;
        } else if (name.contains("slow") || name.contains("heavy") || name.contains("sluggish")) {
            return MobEffects.SLOWNESS;
        } else if (name.contains("speed") || name.contains("swift") || name.contains("haste")) {
            return MobEffects.SPEED;
        } else if (name.contains("strength") || name.contains("might") || name.contains("power")) {
            return MobEffects.STRENGTH;
        } else if (name.contains("weak")) {
            return MobEffects.WEAKNESS;
        } else if (name.contains("regen") || name.contains("heal")) {
            return MobEffects.REGENERATION;
        } else if (name.contains("resist") || name.contains("tough")) {
            return MobEffects.RESISTANCE;
        } else if (name.contains("blind") || name.contains("dark")) {
            return MobEffects.BLINDNESS;
        } else if (name.contains("glow")) {
            return MobEffects.GLOWING;
        } else if (name.contains("invis")) {
            return MobEffects.INVISIBILITY;
        } else if (name.contains("levitat") || name.contains("float")) {
            return MobEffects.LEVITATION;
        }

        return MobEffects.LUCK;
    }

    /**
     * Rewrites the packets carried inside a bundle.
     * <p>
     * A spawning entity does not arrive on its own: the server gathers the spawn, its metadata and
     * its attributes and sends them as one bundle, so a check for the spawn packet alone never
     * matches and the modded entity inside travels untouched - straight into the failed read this
     * class exists to prevent. The bundle has to be opened and put back together.
     *
     * @param bundle the bundle on its way to a Bedrock player
     * @return the bundle, or a new one when something inside it needed changing
     */
    @Unique
    @SuppressWarnings("unchecked")
    private Packet<?> hydraulic$rewriteBundle(@NotNull ClientboundBundlePacket bundle) {
        List<Packet<? super ClientGamePacketListener>> rewritten = new ArrayList<>();

        boolean changed = false;
        for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            Packet<?> next = this.hydraulic$rewrite(sub);
            changed |= next != sub;
            rewritten.add((Packet<? super ClientGamePacketListener>) next);
        }

        return changed ? new ClientboundBundlePacket(rewritten) : bundle;
    }

    /**
     * Drops recipes whose display type a mod added, which Geyser cannot read.
     */
    @Unique
    private static Packet<?> hydraulic$filterModdedRecipes(@NotNull ClientboundRecipeBookAddPacket packet) {
        List<ClientboundRecipeBookAddPacket.Entry> readable = packet.entries().stream()
                .filter(entry -> hydraulic$isVanillaDisplay(entry.contents().display()))
                .toList();

        int dropped = packet.entries().size() - readable.size();
        if (dropped == 0) {
            return packet;
        }

        hydraulic$LOGGER.debug("Dropped {} recipe(s) with a modded display type", dropped);
        return new ClientboundRecipeBookAddPacket(readable, packet.replace());
    }

    /**
     * Sends a modded sound by name rather than by registry id.
     * <p>
     * Sounds normally travel as an index into the sound registry, but Geyser's copy of MCProtocolLib
     * only knows the vanilla entries, so a mod's sound sits past the end of its table and reading the
     * packet throws {@code ArrayIndexOutOfBoundsException} - the sound is lost, and with it things
     * like the noise a modded door makes. The protocol can carry the sound inline instead, which
     * Geyser reads happily and forwards to Bedrock under its own name; the converted pack defines
     * that name, so the sound actually plays.
     *
     * @param packet the sound packet on its way to a Bedrock player
     * @return the packet, rewritten to name the sound if a mod provides it
     */
    @Unique
    private static Packet<?> hydraulic$inlineModdedSound(@NotNull ClientboundSoundPacket packet) {
        SoundEvent event = packet.getSound().value();
        if (event.location().getNamespace().equals("minecraft")) {
            return packet; // Geyser knows every vanilla sound, so an id is fine
        }

        return new ClientboundSoundPacket(Holder.direct(event), packet.getSource(),
                packet.getX(), packet.getY(), packet.getZ(),
                packet.getVolume(), packet.getPitch(), packet.getSeed());
    }

    /**
     * Carries a modded mob's spawn under a vanilla type so the packet can be read.
     * <p>
     * An entity type travels as a registry id with no inline form to fall back on, so unlike a sound
     * it cannot be sent under its own name - {@code EntityType.from(164)} against a table of 158
     * simply throws and the spawn, plus whatever shared its batch, is gone. Naming a vanilla type
     * instead gets the spawn across intact, with its entity id, position and movement untouched.
     * <p>
     * <b>The mob does not stay this type.</b> {@code EntityListener} looks the entity up on the
     * server by its uuid the moment the spawn arrives and points it back at its own definition, so
     * what a Bedrock player sees is the mod's real model. This only has to survive the trip; the type
     * chosen still matters a little, since Geyser builds the entity on it before the swap, which is
     * why it is picked to match the kind of creature rather than being the same every time.
     *
     * @param packet the spawn packet on its way to a Bedrock player
     * @return the packet, with a modded type swapped for a readable vanilla one of the same sort
     */
    @Unique
    private static Packet<?> hydraulic$substituteModdedEntity(@NotNull ClientboundAddEntityPacket packet) {
        EntityType<?> type = packet.getType();
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (key.getNamespace().equals("minecraft")) {
            return packet;
        }

        EntityType<?> substitute = hydraulic$vanillaStandIn(type, key, packet.getUUID());
        if (hydraulic$REPORTED_ENTITIES.add(key)) {
            hydraulic$LOGGER.info("Sending {} to Bedrock players as {} so the spawn can be read; its real model is restored on arrival",
                    key, BuiltInRegistries.ENTITY_TYPE.getKey(substitute));
        }

        return new ClientboundAddEntityPacket(packet.getId(), packet.getUUID(),
                packet.getX(), packet.getY(), packet.getZ(),
                packet.getXRot(), packet.getYRot(), substitute, packet.getData(),
                packet.getMovement(), packet.getYHeadRot());
    }

    /**
     * Picks the vanilla entity closest in kind to a modded one, so a modded fish does not surface as
     * a zombie.
     */
    @Unique
    private static EntityType<?> hydraulic$vanillaStandIn(@NotNull EntityType<?> type, @NotNull Identifier key,
                                                          @NotNull java.util.UUID uuid) {
        // What the mod itself says the creature is, before falling back to what sort of thing it is.
        // A mod that varies a vanilla creature subclasses it, and Bedrock already draws that creature
        // properly - shape, walk and all - so there is nothing to approximate
        EntityType<?> ancestor = VanillaAncestry.standInFor(type, HydraulicImpl.instance().server(), uuid);
        if (ancestor != null) {
            return ancestor;
        }

        MobCategory category = type.getCategory();

        // The type chosen decides how the client moves the thing between updates, not just how it
        // looks. A thrown item carried as an armor stand is interpolated like something standing
        // still, which reads as heavy lag and a warped sprite - worst over short distances, where it
        // may never appear at all. A snowball is the same sort of object and moves like one
        if (category == MobCategory.MISC && BuiltInRegistries.ITEM.containsKey(key)) {
            // The type carried decides how the client turns the thing as well as how it moves, and a
            // thrown item is drawn as a flat sprite that has to face the player to be seen at all. An
            // arrow points along its own flight, and that rotation is applied to whatever is attached,
            // leaving the sprite edge-on and invisible. A snowball has no such heading.
            //
            // The cost is that Bedrock scatters snow where a snowball lands, so a modded tomato bursts
            // into snowflakes. Being visible in flight is worth more than the right dust on landing,
            // and both come from this one choice
            return EntityTypes.SNOWBALL;
        }

        if (category == MobCategory.MONSTER) {
            return EntityTypes.ZOMBIE;
        } else if (category == MobCategory.CREATURE) {
            return EntityTypes.PIG;
        } else if (category == MobCategory.AMBIENT) {
            return EntityTypes.BAT;
        } else if (category == MobCategory.WATER_CREATURE
                || category == MobCategory.WATER_AMBIENT
                || category == MobCategory.UNDERGROUND_WATER_CREATURE) {
            return EntityTypes.COD;
        } else if (category == MobCategory.AXOLOTLS) {
            return EntityTypes.AXOLOTL;
        }

        // Anything else is some non-living object - a modded boat, projectile or display entity.
        // An armor stand is the least surprising thing to put in its place: it is solid, silent and
        // does not wander off.
        return EntityTypes.ARMOR_STAND;
    }

    /**
     * Removes modded attributes from an attribute update.
     * <p>
     * An attribute is read by looking its id up in MCProtocolLib's table, which returns {@code null}
     * for a mod's entry and then throws on a non-null check, taking the whole update with it. Unlike
     * an entity there is nothing worth substituting: Bedrock has no concept of a modded attribute, so
     * it could not act on one. Dropping just those entries lets the vanilla attributes in the same
     * packet - health, movement speed, attack damage - arrive as normal.
     *
     * @param packet the attribute update on its way to a Bedrock player
     * @return the packet, carrying only attributes Geyser can read
     */
    @Unique
    private static Packet<?> hydraulic$dropModdedAttributes(@NotNull ClientboundUpdateAttributesPacket packet) {
        List<ClientboundUpdateAttributesPacket.AttributeSnapshot> readable = packet.getValues().stream()
                .filter(snapshot -> hydraulic$isVanillaAttribute(snapshot.attribute()))
                .toList();

        if (readable.size() == packet.getValues().size()) {
            return packet;
        }

        // The packet is only built from live attribute instances, so the snapshots have to be turned
        // back into instances to rebuild it
        List<AttributeInstance> instances = new ArrayList<>(readable.size());
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : readable) {
            AttributeInstance instance = new AttributeInstance(snapshot.attribute(), attribute -> {});
            instance.setBaseValue(snapshot.base());
            instance.addPermanentModifiers(snapshot.modifiers());
            instances.add(instance);
        }

        return new ClientboundUpdateAttributesPacket(packet.getEntityId(), instances);
    }

    /**
     * Shows a modded particle as the vanilla particle it most resembles.
     * <p>
     * Like an entity type a particle travels as a registry id, so a mod's own particle is unreadable
     * and takes its packet - and whatever was batched alongside it - down with it. Bedrock cannot be
     * told about a new particle either: the pack can define one (see
     * {@code org.geysermc.hydraulic.particle.ParticlePackModule}) but nothing in the protocol can
     * name it, so the only thing that can arrive is a vanilla particle.
     * <p>
     * Which vanilla particle is guessed from the modded one's name, since that is the only clue
     * available - a mod's {@code steam} becomes a cloud rather than a puff of smoke. It is a
     * likeness, not a translation, and picking wrong costs only the wrong-looking effect.
     *
     * @param packet the particle packet on its way to a Bedrock player
     * @return the packet, with a modded particle swapped for its closest vanilla match
     */
    @Unique
    private static Packet<?> hydraulic$substituteModdedParticle(@NotNull ClientboundLevelParticlesPacket packet) {
        ParticleOptions particle = packet.getParticle();
        Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType());
        if (key == null || key.getNamespace().equals("minecraft")) {
            return packet;
        }

        return new ClientboundLevelParticlesPacket(hydraulic$vanillaLookalike(key.getPath()),
                packet.isOverrideLimiter(), packet.alwaysShow(),
                packet.getX(), packet.getY(), packet.getZ(),
                packet.getXDist(), packet.getYDist(), packet.getZDist(),
                packet.getMaxSpeed(), packet.getCount());
    }

    /**
     * Reads a modded particle's name for what it is trying to look like.
     */
    @Unique
    private static SimpleParticleType hydraulic$vanillaLookalike(@NotNull String name) {
        if (name.contains("steam") || name.contains("smoke") || name.contains("vapor") || name.contains("cloud")) {
            return ParticleTypes.CLOUD;
        } else if (name.contains("flame") || name.contains("fire") || name.contains("ember") || name.contains("burn")) {
            return ParticleTypes.FLAME;
        } else if (name.contains("spark") || name.contains("star") || name.contains("glint") || name.contains("shine")
                || name.contains("glow") || name.contains("magic")) {
            return ParticleTypes.END_ROD;
        } else if (name.contains("crit") || name.contains("hit") || name.contains("damage")) {
            return ParticleTypes.CRIT;
        } else if (name.contains("heal") || name.contains("happy") || name.contains("bonus")) {
            return ParticleTypes.HAPPY_VILLAGER;
        }

        return ParticleTypes.POOF;
    }

    @Unique
    private static boolean hydraulic$isVanillaAttribute(@NotNull Holder<Attribute> attribute) {
        return attribute.unwrapKey()
                .map(key -> key.identifier().getNamespace().equals("minecraft"))
                .orElse(false);
    }

    /**
     * Floodgate gives Bedrock players a UUID whose most significant bits are zero, which is how
     * Floodgate itself identifies them. Players who linked their Bedrock account to a Java one keep
     * their Java UUID and so aren't matched here.
     */
    @Unique
    private static boolean hydraulic$isBedrockPlayer(ServerGamePacketListenerImpl listener) {
        return listener.getPlayer().getUUID().getMostSignificantBits() == 0;
    }

    @Unique
    private static boolean hydraulic$isVanillaDisplay(RecipeDisplay display) {
        Identifier key = BuiltInRegistries.RECIPE_DISPLAY.getKey(display.type());
        return key != null && key.getNamespace().equals("minecraft");
    }
}
