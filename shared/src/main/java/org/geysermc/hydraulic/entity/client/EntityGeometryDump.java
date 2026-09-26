package org.geysermc.hydraulic.entity.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Writes converted entity geometry somewhere the server can find it.
 * <p>
 * Entity models exist only on the client, and the conversion to a Bedrock pack happens on the
 * server, so the two halves cannot simply call each other. The client writes what it knows to a
 * shared folder and the server reads it back the next time it builds a pack.
 * <p>
 * <b>This assumes the client and server are the same machine</b>, which is true when testing a
 * server locally but not in general. A server with no dump to read simply produces no entity
 * geometry, so a remote setup loses the feature rather than breaking.
 */
public final class EntityGeometryDump {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Only the layer every entity has; the others are hats, armour and other extras that Bedrock
     * would not know how to attach.
     */
    private static final String MAIN_LAYER = "main";

    /**
     * What this version of the converter produces, bumped whenever the output changes shape.
     * <p>
     * Converted models are kept between runs and only written when missing, which is what makes the
     * trip into a world a one-off rather than something to repeat. The cost is that improving the
     * conversion changes nothing: every mob already has a file, so every mob keeps the old one, and
     * a fix looks exactly like a fix that did not work. Recording which version wrote the folder lets
     * a newer one throw the old work away and mean it.
     */
    private static final int FORMAT = 18;

    private EntityGeometryDump() {
    }

    /**
     * Whether the old work has already been cleared this run. Both passes ask, and the second must
     * not throw away what the first has just written.
     */
    private static boolean cleared;

    /**
     * Throws away converted models left by an older version of the converter.
     */
    private static void discardStale(@NotNull Path directory) {
        if (cleared) {
            return;
        }

        cleared = true;

        Path stamp = directory.resolve("format");
        try {
            if (!Files.isDirectory(directory)) {
                return;
            }

            String written = Files.isRegularFile(stamp) ? Files.readString(stamp).trim() : "";
            if (written.equals(String.valueOf(FORMAT))) {
                return;
            }

            int removed = 0;
            try (var entries = Files.list(directory)) {
                for (Path file : entries.toList()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".geo.json") || name.endsWith(".animation.json")) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                }
            }

            LOGGER.info("The converter has changed since these {} model(s) were written; converting "
                    + "them again", removed);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not clear previously converted entity models", e);
        }
    }

    /**
     * Records which version of the converter wrote this folder.
     */
    private static void stamp(@NotNull Path directory) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("format"), String.valueOf(FORMAT));
        } catch (IOException e) {
            // Only costs a needless reconversion next time
        }
    }

    /**
     * The folder both sides agree on, beside the user's home directory so the server can find it
     * without knowing where the client is installed.
     */
    @NotNull
    public static Path directory() {
        return Paths.get(System.getProperty("user.home"), ".hydraulic", "entity-geometry");
    }

    /**
     * Every layer the client has loaded, kept so a mob that reuses one of the game's own can still
     * be given geometry. Reading them is the layer registry's business and happens well before any
     * mob is asked about itself, so by then this is filled in.
     */
    private static volatile Map<ModelLayerLocation, LayerDefinition> layers = Map.of();

    /**
     * Converts every modded entity model the client knows and writes it out.
     * <p>
     * Vanilla models are skipped: Bedrock already has its own, and using them would replace every
     * mob in the game with a converted copy of itself for no gain.
     *
     * @param roots every model layer the client has loaded
     */
    public static void write(@NotNull Map<ModelLayerLocation, LayerDefinition> roots) {
        Path directory = directory();
        layers = roots;
        discardStale(directory);

        int written = 0;
        int failed = 0;
        for (Map.Entry<ModelLayerLocation, LayerDefinition> entry : roots.entrySet()) {
            ModelLayerLocation location = entry.getKey();
            if (location.model().getNamespace().equals("minecraft") || !location.layer().equals(MAIN_LAYER)) {
                continue;
            }

            String name = location.model().getNamespace() + "." + location.model().getPath();
            try {
                JsonObject geometry = EntityGeometryConverter.convert(name, entry.getValue());

                Files.createDirectories(directory);
                Path file = directory.resolve(name + ".geo.json");
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(geometry, writer);
                }

                written++;
            } catch (IOException | RuntimeException e) {
                // One unusual model must not cost the rest of them
                LOGGER.warn("Could not convert the entity model {}", location, e);
                failed++;
            }
        }

        if (written > 0 || failed > 0) {
            LOGGER.info("Wrote {} modded entity model(s) to {} for Bedrock conversion{}",
                    written, directory, failed == 0 ? "" : ", " + failed + " could not be converted");
        }
    }

    /**
     * Converts models taken straight from the renderers, for mods that never registered them.
     * <p>
     * Only ones the layer registry did not already account for are written, and only ones built by a
     * model system this understands - at present Citadel's, which Alex's Mobs and its relatives use.
     * Anything else is left for {@link #write} to have handled or for the server to report missing.
     *
     * @param models each entity type and the model its renderer draws with
     */
    public static int writeRendererModels(@NotNull Map<EntityType<?>, Object> models,
                                          @NotNull Map<EntityType<?>, String> textures) {
        Path directory = directory();
        discardStale(directory);
        writeTextures(directory, textures);

        int written = 0;
        int animated = 0;
        int unknown = 0;
        for (Map.Entry<EntityType<?>, Object> entry : models.entrySet()) {
            Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entry.getKey());
            if (type.getNamespace().equals("minecraft")) {
                continue;
            }

            String name = type.getNamespace() + "." + type.getPath();
            Path file = directory.resolve(name + ".geo.json");

            // The shape may already have been written by an earlier pass, but the animation never has
            // - and skipping the whole mob on that basis meant not one walk was ever recorded, since
            // every model had its geometry by then
            if (Files.isRegularFile(file)) {
                if (writeAnimation(directory, name, entry.getValue(), CitadelAnimation.subjectFor(entry.getKey()), entry.getKey())) {
                    animated++;
                }

                continue;
            }

            JsonObject geometry;
            if (CitadelGeometry.isCitadelModel(entry.getValue())) {
                geometry = CitadelGeometry.convert(name, entry.getValue(),
                        CitadelAnimation.subjectFor(entry.getKey()));
            } else {
                // A mod that varies one of the game's own creatures reuses its model outright, so
                // there is no modded layer to have converted and nothing Citadel-shaped to read. The
                // shape is still there to be had - it is the vanilla one - and writing it out under
                // this mob's name is what lets it wear the mod's texture instead of arriving as
                // whatever a category guess produced
                geometry = vanillaGeometry(name, entry.getValue());
            }

            if (geometry == null) {
                unknown++;
                continue;
            }

            try {
                Files.createDirectories(directory);
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(geometry, writer);
                }

                written++;
            } catch (IOException e) {
                LOGGER.warn("Could not write the model for {}", type, e);
                continue;
            }

            // Having the model in hand is also the one chance to record what its animation code does,
            // since that code exists only here and only while the client is running
            if (writeAnimation(directory, name, entry.getValue(), CitadelAnimation.subjectFor(entry.getKey()), entry.getKey())) {
                animated++;
            }
        }

        if (written > 0) {
            LOGGER.info("Wrote {} entity model(s) read from their renderers, {} of them with a recorded walk",
                    written, animated);
        }

        if (unknown > 0) {
            LOGGER.info("{} modded entity model(s) use a model system that cannot be read; those mobs "
                    + "will appear to Bedrock players as a vanilla stand-in", unknown);
        }

        stamp(directory);

        return animated;
    }

    /**
     * Writes down which texture each mob is drawn with, straight from its renderer.
     * <p>
     * One file rather than one per mob, since it is only a name each. The server prefers this to
     * working the name out from the mob's own, which goes wrong wherever a mod does not match them up.
     */
    private static void writeTextures(@NotNull Path directory, @NotNull Map<EntityType<?>, String> textures) {
        JsonObject known = new JsonObject();
        for (Map.Entry<EntityType<?>, String> entry : textures.entrySet()) {
            Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entry.getKey());
            if (!type.getNamespace().equals("minecraft")) {
                known.addProperty(type.toString(), entry.getValue());
            }
        }

        if (known.isEmpty()) {
            return;
        }

        try {
            Files.createDirectories(directory);
            try (Writer writer = Files.newBufferedWriter(directory.resolve("textures.json"))) {
                GSON.toJson(known, writer);
            }

            LOGGER.info("Recorded the texture of {} modded entity/entities", known.size());
        } catch (IOException e) {
            LOGGER.warn("Could not record entity textures", e);
        }
    }

    /**
     * Records a model's walk beside its shape, if the animation can be driven without a live mob.
     *
     * @return whether anything was recorded
     */
    private static boolean writeAnimation(@NotNull Path directory, @NotNull String name, @NotNull Object model, Object subject, @NotNull Object type) {
        try {
            // Two kinds of model, two ways of being posed. The first knows the older shape, where a
            // model is handed the mob and the state of its stride; the second knows the one the game
            // uses now, where it is handed a single object describing the mob. A model that will not
            // answer the first is not a model that cannot be recorded - it is usually one built the
            // way the game builds its own, and thirty-one of those had a shape and no movement
            JsonObject animation = CitadelAnimation.sample(name, model, CitadelGeometry.partsOf(model), subject);
            if (animation == null) {
                animation = VanillaAnimation.sample(name, model, CitadelAnimation.stateFor(type));
            }

            if (animation == null) {
                return false;
            }

            try (Writer writer = Files.newBufferedWriter(directory.resolve(name + ".animation.json"))) {
                GSON.toJson(animation, writer);
            }

            return true;
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            // A mob that will not be driven keeps the still model already written for it
            return false;
        }
    }
    /**
     * The vanilla shape a mod's variant of a creature is drawn with, written under the mod's name.
     * <p>
     * Bedrock has the vanilla creature already, so converting its shape would normally be pointless
     * work. It stops being pointless the moment a mod wants that shape wearing its own texture: the
     * mob needs an entity of its own to hang the texture on, and an entity needs geometry.
     *
     * @param name what to call the converted shape, which is the mod's mob rather than the vanilla one
     * @param model the model its renderer draws with
     * @return the converted geometry, or null if this model is not one of the game's own
     */
    @Nullable
    private static JsonObject vanillaGeometry(@NotNull String name, @NotNull Object model) {
        LayerDefinition shape = VanillaShapes.shapeOf(model, layers);
        if (shape == null) {
            return null;
        }

        try {
            return EntityGeometryConverter.convert(name, shape);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not convert the vanilla shape {} is drawn with", name, e);
            return null;
        }
    }

}
