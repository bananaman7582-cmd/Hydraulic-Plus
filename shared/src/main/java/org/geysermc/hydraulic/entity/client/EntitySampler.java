package org.geysermc.hydraulic.entity.client;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Asks each modded mob about itself, with a real one to hand.
 * <p>
 * Reading models straight out of the renderers works for the shape, but not for the two things that
 * need a mob to answer: which texture it wears, and what its animation does. Both are decided per
 * creature - a renderer picks a texture from the mob's size or variant, and an animation reads
 * whether it is swimming or angry - so asked with nothing to look at, they simply throw. That is why
 * eleven of a hundred and fifty mobs gave up a texture and none gave up a walk.
 * <p>
 * A mob can be built, though, once there is a world to build it in. So this waits for one, makes a
 * throwaway of each kind, and asks again with something real to answer about. Nothing is put into the
 * world - the mob exists only long enough to be asked - and it happens once per session.
 */
public final class EntitySampler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Whether this has already run. The world can be joined and left repeatedly, and nothing about
     * the answers changes between times.
     */
    private static boolean done;

    private EntitySampler() {
    }

    /**
     * Samples every modded mob the client can draw, now that there is a world to build one in.
     */
    public static void sample(@NotNull ClientLevel level) {
        if (done) {
            return;
        }

        done = true;

        Map<EntityType<?>, String> textures = new HashMap<>();
        Map<EntityType<?>, Object> models = new HashMap<>();

        int built = 0;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (key.getNamespace().equals("minecraft")) {
                continue;
            }

            Entity entity = build(type, level);
            if (entity == null) {
                continue;
            }

            built++;
            ask(entity, type, textures, models);
        }

        LOGGER.info("Built {} modded mob(s) to ask about themselves; {} gave a texture, {} a model",
                built, textures.size(), models.size());

        int animations = EntityGeometryDump.writeRendererModels(models, textures);

        // Said in the world rather than only in the log, because this is the one moment the player
        // needs to know something: the whole reason for loading a world was to read these, and
        // nothing else here will tell them it is finished and they can leave again
        announce(textures.size(), models.size(), animations);
    }

    /**
     * Tells the player what was read, so they know the trip was worth making and can close the world.
     */
    private static void announce(int textures, int models, int animations) {
        say(Component.literal("[Hydraulic] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Read " + models + " modded mob model(s) - " + textures
                                + " textures and " + animations + " animations.")
                        .withStyle(ChatFormatting.GREEN)));

        say(Component.literal("[Hydraulic] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Saved for the Bedrock pack. You can close this world and "
                                + "start the server.")
                        .withStyle(ChatFormatting.GRAY)));
    }

    /**
     * Puts a line in the player's chat, whichever of the two ways is ready.
     * <p>
     * This runs as the world arrives, and the player may not be standing in it yet - so the chat is
     * spoken to directly when there is nobody to speak to.
     */
    private static void say(@NotNull Component message) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            } else {
                LOGGER.info(message.getString());
            }
        } catch (Throwable t) {
            // The chat is a convenience; the log already carries the same thing
        }
    }

    /**
     * Makes a mob without putting it anywhere. Plenty will refuse - anything whose construction wants
     * more than a world - and those are simply left as they were.
     */
    private static Entity build(@NotNull EntityType<?> type, @NotNull ClientLevel level) {
        try {
            return type.create(level, EntitySpawnReason.NATURAL);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Puts the mob in front of its own renderer and writes down what it says.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void ask(@NotNull Entity entity, @NotNull EntityType<?> type,
                            @NotNull Map<EntityType<?>, String> textures,
                            @NotNull Map<EntityType<?>, Object> models) {
        EntityRenderer<?, ?> renderer;
        try {
            renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        } catch (Throwable t) {
            return;
        }

        if (!(renderer instanceof LivingEntityRenderer<?, ?, ?> living)) {
            return;
        }

        Object texture = texture(living, entity);
        if (texture != null) {
            textures.put(type, texture.toString());
        }

        Object model = living.getModel();
        if (model != null) {
            models.put(type, model);
            CitadelAnimation.remember(type, entity);

            // A model built the game's own way poses itself from a render state rather than from the
            // mob, and one has already been made just above to ask about the texture. Keeping it is
            // what lets those models be recorded at all
            CitadelAnimation.rememberState(type, renderState(living, entity));
        }
    }

    /**
     * Asks a renderer which texture it draws this mob with.
     * <p>
     * There is no one way to ask. A vanilla renderer is handed a render state - a snapshot taken of
     * the mob for drawing - while a mod carrying itself forward across versions often keeps a shim
     * that still takes the mob directly, and once compiled both are called {@code getTextureLocation}
     * and differ only in what they accept. Handing one the other's argument is not a wrong answer but
     * an exception, so every version of the method is offered whichever of the two it will take.
     * <p>
     * Asking only the first one found was enough to lose every mob in a mod at once: the shim sorts
     * to the top, was handed a state it could not take, and threw before anything that would have
     * answered was reached.
     */
    @Nullable
    private static Object texture(@NotNull LivingEntityRenderer<?, ?, ?> renderer, @NotNull Entity entity) {
        Object state = renderState(renderer, entity);

        for (var method : renderer.getClass().getMethods()) {
            if (!method.getName().equals("getTextureLocation") || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> wanted = method.getParameterTypes()[0];
            Object argument = wanted.isInstance(entity) ? entity
                    : state != null && wanted.isInstance(state) ? state
                    : null;

            if (argument == null) {
                continue;
            }

            try {
                method.setAccessible(true);

                Object answer = method.invoke(renderer, argument);
                if (answer != null) {
                    return answer;
                }
            } catch (Throwable t) {
                // Not this one; there may be another below it that answers
            }
        }

        return null;
    }

    /**
     * A render state with the mob put into it, for the renderers that ask about one.
     * <p>
     * Deliberately a bare state rather than one extracted from the world: extracting reads a position,
     * a pose and a light level that a mob standing in no world has not got, and throws.
     */
    @Nullable
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object renderState(@NotNull LivingEntityRenderer<?, ?, ?> renderer, @NotNull Entity entity) {
        try {
            Object state = ((EntityRenderer) renderer).createRenderState();
            for (var field : state.getClass().getFields()) {
                if (field.getType().isAssignableFrom(entity.getClass())) {
                    field.set(state, entity);
                }
            }

            return state;
        } catch (Throwable t) {
            return null; // a renderer with no state to make; it may still accept the mob itself
        }
    }
}
