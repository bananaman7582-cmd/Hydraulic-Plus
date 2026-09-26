package org.geysermc.hydraulic.entity;

import com.google.auto.service.AutoService;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.kyori.adventure.key.Key;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Puts a mod's own entity models into the Bedrock pack, so its mobs appear as themselves.
 * <p>
 * Geometry reaches this from one of two places, both ending up as the same thing - a Bedrock
 * geometry file naming the shape:
 * <ul>
 *   <li>Mods built on GeckoLib already ship {@code assets/<ns>/geo/*.geo.json}, which <i>is</i> the
 *       Bedrock format. Those are copied across untouched and need nothing from the client.</li>
 *   <li>Everything else defines its models in client-only code, which a dedicated server does not
 *       have. Those are converted when the player's own client starts and left in a shared folder
 *       for this to pick up - see
 *       {@link org.geysermc.hydraulic.entity.client.EntityGeometryDump}.</li>
 * </ul>
 * The practical difference is that a GeckoLib mod works the moment it is installed, while any other
 * entity mod needs the Java client opened once before the server has a shape to hand over.
 * <p>
 * Textures are a smaller version of the same problem: which one an entity uses is decided in its
 * renderer, which is also client-only. Mods almost always keep them under
 * {@code textures/entity/<name>.png} though, so that is tried first and the folder searched after.
 */
@AutoService(PackModule.class)
public class EntityPackModule extends TexturePackModule<EntityPackModule> {
    private static final String FORMAT_VERSION = "1.10.0";

    /**
     * The version an entity file has to declare for Bedrock to read all of it.
     * <p>
     * Bedrock reads a client entity against the schema of whatever version it names, and an older one
     * is not merely permissive - fields the version predates are dropped without a word. At
     * {@code 1.10.0} the geometry, textures and render controllers were all honoured while the
     * animations beside them were quietly ignored, which reads in game as a mob that renders
     * perfectly and never moves. This is what Bedrock's own entities declare.
     */
    private static final String ENTITY_FORMAT_VERSION = "1.26.0";

    /**
     * What a thrown item declares. Vanillas snowball still names this older version, and the
     * billboard that turns a flat sprite to face the player is read here.
     */
    private static final String SPRITE_FORMAT_VERSION = "1.10.0";

    /**
     * The render controller written alongside the entities, rather than one of Bedrock's own.
     * <p>
     * Vanilla entities each name a controller built for them - the zombie uses
     * {@code controller.render.zombie.v2} - and an entity naming one that does not exist draws
     * nothing at all, without saying so. Shipping a controller of our own removes the guess: it does
     * the only thing needed here, which is to draw the default geometry with the default texture.
     */
    private static final String RENDER_CONTROLLER = "controller.render." + Constants.MOD_ID + ".entity";

    private static final String OVERLAY_RENDER_CONTROLLER = RENDER_CONTROLLER + "_overlay";

    private static final String BABY_RENDER_CONTROLLER = RENDER_CONTROLLER + "_baby";

    /**
     * How much larger the second skin layer is drawn than the skin beneath it, in pixels. The same
     * quarter of a pixel Java uses, which is enough to clear the surface without visibly puffing the
     * mob up.
     */
    private static final float OVERLAY_INFLATE = 0.25f;

    /**
     * How long Bedrock takes to ease between one animation and the next, in seconds. Long enough to
     * read as movement rather than a jump, short enough that a bite still lands when it should.
     */
    private static final float BLEND = 0.2f;

    private static final String RENDER_CONTROLLER_FILE = "render_controllers/"
            + Constants.MOD_ID + ".render_controllers.json";

    public EntityPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<EntityPackModule> context) {
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<Identifier> entities = moddedEntities(context.mod());
        if (entities.isEmpty()) {
            return;
        }

        int written = 0;
        int recordedTextures = 0;
        int recordedWalks = 0;
        List<Identifier> missing = new ArrayList<>();
        for (Identifier entity : entities) {
            JsonObject geometry = geckoGeometry(context.mod(), entity);
            if (geometry == null) {
                geometry = dumpedGeometry(context, entity);
            }

            if (geometry == null) {
                // A thrown item has no model of its own in Java either - it is drawn as the item it
                // throws. Bedrock does the same thing with a sprite, and has the pieces built in
                if (itemFor(entity) != null) {
                    bedrockPack.addExtraFile(itemSpriteEntity(context, entity),
                            "entity/" + entity.getNamespace() + "." + entity.getPath() + ".entity.json");
                    written++;
                    continue;
                }

                // Not everything without a model is a thrown item. A mod also registers things that
                // are only ever a picture - gas, spit, pollen, a ball of mud - and those have their
                // own texture rather than an item to borrow one from. Given that, the same flat
                // square works, and the alternative is a mob nobody can see
                String sprite = spriteTexture(context, entity);
                if (sprite != null) {
                    bedrockPack.addExtraFile(spriteEntity(entity, sprite),
                            "entity/" + entity.getNamespace() + "." + entity.getPath() + ".entity.json");
                    written++;
                    continue;
                }

                missing.add(entity);
                continue;
            }

            String geometryName = geometryName(geometry);
            if (geometryName == null) {
                context.logger().warn("The geometry for {} has no identifier, skipping it", entity);
                continue;
            }

            String name = entity.getNamespace() + "." + entity.getPath();
            addItemBones(geometry);
            bedrockPack.addExtraFile(geometry, "models/entity/" + name + ".geo.json");

            // The second skin layer needs a model very slightly larger than the one beneath it,
            // exactly as Java draws it. Given the same model twice the two surfaces sit in the same
            // place and fight over which is in front, and the outer one loses more often than not
            String overlayTexture = overlay(context, entity);
            String overlayGeometry = null;
            if (overlayTexture != null) {
                JsonObject inflated = inflate(geometry, geometryName);
                overlayGeometry = geometryName + ".overlay";
                bedrockPack.addExtraFile(inflated, "models/entity/" + name + ".overlay.geo.json");
            }

            // Mods keep a baby's model under its own layer, which the client converted alongside the
            // adult. Without it Bedrock has only the grown model to shrink, which is why babies were
            // adults in miniature rather than the stubbier shape Java draws
            JsonObject baby = dumpedGeometry(context, babyOf(entity));
            String babyGeometry = null;
            if (baby != null) {
                babyGeometry = geometryName(baby);
                if (babyGeometry != null) {
                    bedrockPack.addExtraFile(baby, "models/entity/" + name + ".baby.geo.json");
                }
            }

            // A mod that animates in code has no animation to convert, but the client can watch that
            // code run and write down what it does. Where it managed to, the recording is used in
            // place of Bedrock's humanoid animations, which would be moving bones this mob has not got
            if (recordedTexture(entity) != null) {
                recordedTextures++;
            }

            // A GeckoLib mod wrote its animations in Bedrock's own format, so those are taken over a
            // recording every time - they are what the author drew rather than two cycles sampled off
            // running code
            JsonObject walk = geckoAnimation(context.mod(), entity);
            if (walk == null) {
                walk = recordedWalk(entity);
            }
            // Everything the mob can be seen doing, whether that is the two cycles a recording takes
            // or the whole set a mod wrote out itself. The names inside are whoever wrote them, and
            // are what decides when each one plays
            JsonObject animationFile = null;
            if (walk != null && walk.has("animations") && !walk.getAsJsonObject("animations").isEmpty()) {
                recordedWalks++;
                animationFile = walk;
                bedrockPack.addExtraFile(walk, "animations/" + name + ".animation.json");

                // The controller that eases this mob between the animations it performs, if it has
                // any the server drives.
                //
                // Named without repeating "animation_controllers" after the folder that already says
                // it: with the mod id and the entity name both in there, that suffix pushed the path
                // past the 80 characters some Bedrock platforms refuse to load - Alex's Mobs'
                // alligator snapping turtle reached 84. Bedrock finds these files by scanning the
                // folder rather than by their name, so only the extension has to be kept
                List<String> performed = AnimationIndex.forEntity(entity);
                if (!performed.isEmpty()) {
                    bedrockPack.addExtraFile(animationController(entity, performed),
                            "animation_controllers/" + name + ".json");
                }
            }

            bedrockPack.addExtraFile(
                    clientEntity(entity, geometryName, texture(context, entity), overlayTexture,
                            overlayGeometry, babyGeometry, animationFile),
                    "entity/" + name + ".entity.json");
            written++;
        }

        if (written > 0) {
            bedrockPack.addExtraFile(renderController(), RENDER_CONTROLLER_FILE);
            context.logger().info("Wrote {} entity model(s) for mod {}, {} with a recorded texture and {} with a walk",
                    written, context.mod().id(), recordedTextures, recordedWalks);
        }

        MissingEntityModels.recordConverted(written, recordedTextures, recordedWalks);

        if (missing.isEmpty()) {
            MissingEntityModels.clear(context.mod().id());
        } else {
            MissingEntityModels.record(context.mod().id(), missing);
        }

        if (!missing.isEmpty()) {
            String named = missing.size() > 3 ? missing.subList(0, 3) + " and others" : missing.toString();

            // Only send someone to their client if their client is actually what is missing. Once
            // geometry has been read, what is left over is the entities that have no model to read -
            // a thrown projectile, a puff of gas, a portal - which Java draws from render code rather
            // than from a model, and no number of client launches will produce one. Saying "open your
            // client" regardless sent a reader off to spend an evening on entities that were never
            // going to appear there
            if (hasReadGeometry()) {
                context.logger().warn("No model for {} entity/entities in mod {} ({}). Geometry has been "
                                + "read from a client already, so these are entities with no model of their own in "
                                + "Java either - projectiles and effects it draws in code. Bedrock is given a flat "
                                + "sprite where a texture can be found, and nothing further is needed",
                        missing.size(), context.mod().id(), named);
            } else {
                context.logger().warn("No model for {} entity/entities in mod {} ({}). Open the Java client "
                                + "once with this mod installed so their models can be read, then restart the server. Models "
                                + "are kept in {} once read",
                        missing.size(), context.mod().id(), named, geometryFolder());
            }
        }
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<EntityPackModule> context) {
        return !moddedEntities(context.mod()).isEmpty();
    }

    /**
     * The entity types registered by the mod being converted.
     */
    @NotNull
    private static List<Identifier> moddedEntities(@NotNull ModInfo mod) {
        List<Identifier> entities = new ArrayList<>();
        for (Identifier key : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (key.getNamespace().equals(mod.namespace())) {
                entities.add(key);
            }
        }

        return entities;
    }

    /**
     * Reads a GeckoLib model straight out of the mod, which needs no conversion at all.
     */
    @Nullable
    private static JsonObject geckoGeometry(@NotNull ModInfo mod, @NotNull Identifier entity) {
        // GeckoLib moved house between versions: models used to sit under geo/, and now live under
        // geckolib/models/. Both are looked in, since which one a mod uses is down to the GeckoLib it
        // was built against rather than anything about the mod
        for (String folder : List.of("geckolib/models/entity", "geckolib/models", "geo")) {
            String directory = "assets/" + entity.getNamespace() + "/" + folder;

            Path file = mod.resolveFile(directory + "/" + entity.getPath() + ".geo.json");
            if (file == null) {
                // Mods do not always name the file after the entity
                file = findIn(mod, directory, entity.getPath() + ".geo.json");
            }

            if (file != null) {
                JsonObject geometry = readJson(file);
                return geometry == null ? null : uniquelyNamed(geometry, entity);
            }
        }

        return null;
    }

    /**
     * A GeckoLib mod's own animations, which are already in Bedrock's format.
     * <p>
     * These need no recording and no client: GeckoLib authors its animations as the same JSON Bedrock
     * plays, so the file is carried across as it stands. A mod built this way arrives with its walk,
     * its idle and everything else its author drew, which is more than the two a recording can take.
     */
    @Nullable
    private static JsonObject geckoAnimation(@NotNull ModInfo mod, @NotNull Identifier entity) {
        for (String folder : List.of("geckolib/animations/entity", "geckolib/animations", "animations")) {
            String directory = "assets/" + entity.getNamespace() + "/" + folder;

            Path file = mod.resolveFile(directory + "/" + entity.getPath() + ".animation.json");
            if (file == null) {
                file = findIn(mod, directory, entity.getPath() + ".animation.json");
            }

            if (file == null) {
                // Mods share one set of animations between mobs built the same way: every crop critter
                // walks alike, so the mod writes basic_critter.animation.json once and points all ten
                // at it. Looking only for a file named after the mob left most of them with no
                // animation at all and falling back to Bedrock's humanoid ones, which move bones a
                // critter has not got
                file = sharedAnimation(mod, directory);
            }

            if (file != null) {
                JsonObject animation = readJson(file);
                if (animation == null) {
                    return null;
                }

                unwrapKeyframes(animation);
                return named(animation, entity);
            }
        }

        return null;
    }

    /**
     * A mod's shared animation file, for mobs that do not have one of their own.
     * <p>
     * Only taken when there is exactly one candidate. Which file a mob actually uses is named in code
     * this side cannot read, so a folder holding several is a guess between them - and the wrong
     * animation on a mob is worse than none, since none at least leaves it standing still rather than
     * moving as something else.
     */
    @Nullable
    private static Path sharedAnimation(@NotNull ModInfo mod, @NotNull String directory) {
        // Walked from the mod's roots rather than asked for by name. A mod is a zip as far as this is
        // concerned, and the method that resolves a path inside one answers only for files - a folder
        // comes back as nothing at all, which had this returning "no shared animation" every time
        // without ever opening the folder it was looking in
        for (Path root : mod.roots()) {
            Path folder = root.resolve(directory.replace("/", root.getFileSystem().getSeparator()));
            if (!Files.isDirectory(folder)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(folder)) {
                List<Path> candidates = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".animation.json"))
                        .filter(path -> !namesAnEntity(path))
                        .limit(2)
                        .toList();

                if (candidates.size() == 1) {
                    return candidates.getFirst();
                }
            } catch (IOException e) {
                // A folder that will not be read is one without a shared animation in it
            }
        }

        return null;
    }

    /**
     * Whether a file is named after a mob of its own, and so belongs to that one rather than to
     * whoever has none.
     */
    private static boolean namesAnEntity(@NotNull Path file) {
        String name = file.getFileName().toString().replace(".animation.json", "");
        for (Identifier entity : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (entity.getPath().equals(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gives a mod's own animations names Bedrock will accept.
     * <p>
     * Bedrock requires every animation to be called {@code animation.something}, and rejects the whole
     * entry otherwise - "child 'misc.idle' not valid here" - so a file full of them is a file full of
     * nothing. GeckoLib does not use that form: it names animations {@code misc.idle},
     * {@code move.walk}, {@code attack.eat}, grouped by what they are for. The files are otherwise
     * exactly what Bedrock plays, which is what made them look like they needed no work at all.
     * <p>
     * The grouping is worth reading rather than flattening. The part after the dot is the animation
     * itself - idle, walk, sit - and is precisely what decides when it should play, so that becomes
     * the name and the category in front of it is dropped. Where two would collide, the full name is
     * used instead, so nothing is lost.
     */
    /**
     * Gives a mod's geometry a name of its own.
     * <p>
     * Blockbench writes {@code geometry.unknown} into every model it exports and an author who never
     * renamed it ships a folder of models all claiming to be the same one. Bedrock keeps whichever it
     * read last, so every mob sharing that name is drawn as whichever mob won - eight crop critters
     * are all carrots, or all pumpkins, depending on nothing in particular.
     * <p>
     * Java never notices because it looks models up by file, not by the name inside them. Bedrock
     * looks them up by the name, so the name has to be made unique before it goes in.
     */
    @NotNull
    private static JsonObject uniquelyNamed(@NotNull JsonObject file, @NotNull Identifier entity) {
        JsonElement geometries = file.get("minecraft:geometry");
        if (geometries == null || !geometries.isJsonArray()) {
            return file;
        }

        String wanted = "geometry." + entity.getNamespace() + "." + entity.getPath();

        for (JsonElement element : geometries.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject description = element.getAsJsonObject().getAsJsonObject("description");
            if (description != null) {
                description.addProperty("identifier", wanted);
            }
        }

        return file;
    }

    /**
     * Rewrites a mod's keyframes into the shape Bedrock actually reads.
     * <p>
     * GeckoLib's animations are described as being in Bedrock's format, and very nearly are. Where
     * Bedrock wants the three numbers of a keyframe, GeckoLib wraps them one layer deeper:
     * <pre>
     *   Bedrock:   "0.0": { "post": [0, 1, 0] }
     *   GeckoLib:  "0.0": { "post": { "vector": [0, 1, 0] } }
     * </pre>
     * Bedrock rejects the second - "unknown child schema option type, allowed types: 'array'" - and
     * rejects it per keyframe, of which one small mob has ninety. The file is then not merely
     * degraded but absent, so the entity asking for animations by name finds none of them and reports
     * that it cannot find its walk.
     * <p>
     * That is why copying these files across unchanged never worked, and why the mobs stood still
     * however carefully everything around them was named.
     */
    private static void unwrapKeyframes(@NotNull JsonElement element) {
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                unwrapKeyframes(entry);
            }

            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        for (String key : List.copyOf(object.keySet())) {
            JsonElement value = object.get(key);

            // Anything wrapped in nothing but a vector is the wrapper, wherever it turns up. It sits
            // around the halves of a keyframe, and also directly around a value that never changes -
            // a leg simply held two pixels back for as long as the animation runs. Naming the places
            // it appears missed the second kind; describing its shape does not.
            if (value.isJsonObject()) {
                JsonObject wrapper = value.getAsJsonObject();
                if (wrapper.size() == 1 && wrapper.has("vector")) {
                    object.add(key, wrapper.get("vector"));
                    continue;
                }
            }

            unwrapKeyframes(value);
        }
    }

    @NotNull
    private static JsonObject named(@NotNull JsonObject file, @NotNull Identifier entity) {
        JsonObject animations = file.getAsJsonObject("animations");
        if (animations == null || animations.isEmpty()) {
            return file;
        }

        String prefix = "animation." + entity.getNamespace() + "." + entity.getPath() + ".";

        JsonObject renamed = new JsonObject();
        for (String name : animations.keySet()) {
            if (name.startsWith("animation.")) {
                renamed.add(name, animations.get(name)); // already named the way Bedrock wants
                continue;
            }

            int dot = name.lastIndexOf('.');
            String simple = dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;

            String full = prefix + simple;
            if (renamed.has(full)) {
                full = prefix + name.replace('.', '_');
            }

            renamed.add(full, animations.get(name));
        }

        file.add("animations", renamed);
        return file;
    }

    /**
     * Reads a model the client converted earlier, from either place one may be kept.
     * <p>
     * The client writes into a folder beside the user's home directory, which the server finds when
     * the two share a machine. That covers testing locally and nothing else: a server run on hosting
     * has no such folder and would leave every modded mob invisible. So a folder inside the server's
     * own config is looked in first, which anyone can upload these files to alongside their mods -
     * they are only JSON, and a model converted on one machine is as good as one converted anywhere.
     */
    @Nullable
    private static JsonObject dumpedGeometry(@NotNull PackPostProcessContext<EntityPackModule> context, @NotNull Identifier entity) {
        String name = entity.getNamespace() + "." + entity.getPath() + ".geo.json";

        Path kept = geometryFolder().resolve(name);
        Path local = org.geysermc.hydraulic.entity.client.EntityGeometryDump.directory().resolve(name);

        Path best = newest(name);
        if (best == null) {
            return null;
        }

        // Keep a copy so the server no longer depends on a folder that belongs to the client. It
        // means the models survive the client being moved or removed, and that a server folder taken
        // to hosting brings its entities with it rather than losing them on the way.
        //
        // Replaced rather than only written when absent. The copy used to be the reason the client
        // was never read again: once made, it answered for that mob forever, and a better conversion
        // written the following day sat unused beside it
        if (best.equals(local)) {
            try {
                Files.createDirectories(kept.getParent());
                Files.copy(local, kept, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                context.logger().warn("Could not keep a copy of the model for {}: {}", entity, e.getMessage());
            }
        }

        return readJson(best);
    }

    /**
     * Where the server keeps entity models of its own, whoever converted them.
     */
    @NotNull
    private static Path geometryFolder() {
        return HydraulicImpl.instance().dataFolder(Constants.MOD_ID).resolve("entity-geometry");
    }

    /**
     * Whether a client has ever handed over any geometry at all.
     * <p>
     * The difference between "your client has not run" and "these entities have no model to read"
     * cannot be told apart from a single mod's leftovers, but it can be told from whether the folder
     * has anything in it. One entity read from any mod means the client has done its part, and what
     * remains unread is what was never drawn from a model to begin with.
     */
    private static boolean hasReadGeometry() {
        try (Stream<Path> files = Files.list(geometryFolder())) {
            return files.anyMatch(file -> file.getFileName().toString().endsWith(".geo.json"));
        } catch (IOException e) {
            return false; // no folder yet, so nothing has been read
        }
    }

    /**
     * The item an entity is really a thrown copy of, if there is one.
     * <p>
     * A thrown projectile has no model anywhere - Java draws it as the item that was thrown, which is
     * why nothing turns up to convert and the entity would otherwise be reported as missing. Mods
     * name the two the same, so a matching item is a good sign this is one: {@code rotten_tomato} the
     * entity is thrown by {@code rotten_tomato} the item. Anything with a model of its own never
     * reaches here, so a boat or a painting is not caught by this.
     */
    @Nullable
    private static Item itemFor(@NotNull Identifier entity) {
        return BuiltInRegistries.ITEM.containsKey(entity) ? BuiltInRegistries.ITEM.getValue(entity) : null;
    }

    /**
     * Builds a flat, camera-facing sprite of the thrown item.
     * <p>
     * All three pieces are Bedrock's own - the sprite geometry, the controller that draws it and the
     * billboard animation that keeps it facing the player - so nothing has to be generated but the
     * definition tying them to the mod's item texture.
     */
    @NotNull
    private static JsonObject itemSpriteEntity(@NotNull PackPostProcessContext<EntityPackModule> context,
                                               @NotNull Identifier entity) {
        return spriteEntity(entity,
                getOutputFromModel(context, Key.key(entity.getNamespace(), "item/" + entity.getPath())).replace(".png", ""));
    }

    /**
     * A flat picture that turns to face whoever is looking at it.
     * <p>
     * Plenty of what a mod registers as an entity is never a shape at all - a puff of gas, a glob of
     * something spat, a thrown ball of mud. Java draws those as one square with a picture on it, and
     * so does Bedrock; the difference is that Java can do it with no model file anywhere, which left
     * them falling through every path here and arriving as nothing.
     *
     * @param entity the mob this stands for
     * @param texture where its picture ended up in the pack
     */
    private static JsonObject spriteEntity(@NotNull Identifier entity, @NotNull String texture) {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", "geometry.item_sprite");

        JsonObject textures = new JsonObject();
        textures.addProperty("default", texture);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.item_sprite");

        JsonObject animations = new JsonObject();
        animations.addProperty("flying", "animation.actor.billboard");

        JsonArray animate = new JsonArray();
        animate.add("flying");
        JsonObject scripts = new JsonObject();
        scripts.add("animate", animate);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", entity.toString());
        description.add("materials", materials);
        description.add("textures", textures);
        description.add("geometry", geometry);
        description.add("animations", animations);
        description.add("scripts", scripts);
        description.add("render_controllers", renderControllers);

        JsonObject clientEntity = new JsonObject();
        clientEntity.add("description", description);

        JsonObject root = new JsonObject();
        // Deliberately the version vanillas own snowball declares rather than the newer one used for
        // mobs. The billboard that keeps a sprite facing the player is read at this version; a thrown
        // item that stays edge-on is the sign of having moved past it
        root.addProperty("format_version", SPRITE_FORMAT_VERSION);
        root.add("minecraft:client_entity", clientEntity);
        return root;
    }

    /**
     * Gives the model somewhere to put what the mob is holding.
     * <p>
     * Bedrock hangs a held item off a bone named {@code rightItem} or {@code leftItem}, and a model
     * without them leaves the item at the entity's own origin - down between its feet, which is
     * where a skeleton's bow ended up. Java has no such bones, since it positions held items in the
     * renderer instead, so they have to be added.
     * <p>
     * The offset from the arm is vanilla's: its {@code rightArm} sits at {@code [-5, 22, 0]} and its
     * {@code rightItem} at {@code [-6, 15, 1]}. Taking the difference rather than the absolute
     * position means a mob with arms somewhere unusual still gets the item in its hand.
     *
     * @param geometry the model to add the bones to, modified in place
     */
    private static void addItemBones(@NotNull JsonObject geometry) {
        JsonArray geometries = geometry.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            return;
        }

        JsonArray bones = geometries.get(0).getAsJsonObject().getAsJsonArray("bones");
        if (bones == null) {
            return;
        }

        // An animal carries what it picks up in its mouth, having no hand to put it in - a raccoon
        // with an egg, a fox with a rabbit. Java draws that from the renderer like everything else,
        // so there is no bone for it either, and without one the item falls to the mob's feet
        if (walksOnItsArms(bones)) {
            addMouthItemBone(bones);
            return;
        }

        boolean handed = addItemBone(bones, RIGHT_ARMS, "rightItem", -1.0f);
        addItemBone(bones, LEFT_ARMS, "leftItem", 1.0f);

        if (!handed) {
            addMouthItemBone(bones);
        }
    }

    /**
     * Whether the bones called arms are really the front legs of an animal standing on them.
     * <p>
     * A model saying {@code arm_left} is not saying the mob has hands - a raccoon's front paws are
     * named that and are on the floor. What separates the two is height: a standing mob's arms hang
     * from its shoulders, well above its legs, while an animal's front and back legs reach the same
     * ground. The raccoon's are at {@code y=7} apiece; a humanoid's arms sit ten pixels above its.
     * <p>
     * Being wrong in the cautious direction puts the item in the mouth of something that had hands,
     * rather than halfway up the leg of something that did not.
     */
    private static boolean walksOnItsArms(@NotNull JsonArray bones) {
        Float arm = null;
        Float leg = null;

        for (var element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (!bone.has("name")) {
                continue;
            }

            String name = simplify(bone.get("name").getAsString());
            JsonArray pivot = bone.getAsJsonArray("pivot");
            if (pivot == null || pivot.size() < 2) {
                continue;
            }

            if (arm == null && (RIGHT_ARMS.contains(name) || LEFT_ARMS.contains(name))) {
                arm = pivot.get(1).getAsFloat();
            } else if (leg == null && name.contains("leg")) {
                leg = pivot.get(1).getAsFloat();
            }
        }

        return arm != null && leg != null && Math.abs(arm - leg) <= SHOULDER_HEIGHT;
    }

    /**
     * What a limb holding an item is called, with the parts of the name in either order.
     * <p>
     * Vanilla says {@code rightArm}; mods say {@code arm_right} as readily, and Alex's Mobs does.
     * Matching one spelling meant every model that chose the other was read as having no arms at all
     * and quietly went without.
     */
    private static final List<String> RIGHT_ARMS = List.of("rightarm", "armright", "righthand", "handright");
    private static final List<String> LEFT_ARMS = List.of("leftarm", "armleft", "lefthand", "handleft");

    /**
     * Where a mouth is, nearest the front of the face first, so an item sits at the snout rather than
     * in the middle of the head when a model has both.
     */
    private static final List<String> MOUTHS = List.of("snout", "muzzle", "mouth", "jaw", "beak", "head");

    /**
     * How much higher than the legs an arm has to sit before the mob counts as standing on two of
     * them, in pixels. Comfortably above the nothing that separates an animal's four legs, and well
     * below the ten or so that separate a person's arms from theirs.
     */
    private static final float SHOULDER_HEIGHT = 3.0f;

    /**
     * @return whether a bone was added, so the caller knows whether to look for a mouth instead
     */
    private static boolean addItemBone(@NotNull JsonArray bones, @NotNull List<String> arms,
                                       @NotNull String item, float sideways) {
        JsonArray armPivot = null;
        String armName = null;
        for (var element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (!bone.has("name")) {
                continue;
            }

            String name = bone.get("name").getAsString();

            // Already has one, which a GeckoLib model well might
            if (name.equals(item)) {
                return true;
            }

            if (armPivot == null && arms.contains(simplify(name))) {
                armPivot = bone.getAsJsonArray("pivot");
                armName = name;
            }
        }

        if (armPivot == null) {
            return false; // not a humanoid; nothing to hang an item from
        }

        JsonArray pivot = new JsonArray();
        pivot.add(armPivot.get(0).getAsFloat() + sideways);
        pivot.add(armPivot.get(1).getAsFloat() - 7.0f);
        pivot.add(armPivot.get(2).getAsFloat() + 1.0f);

        bones.add(itemBone(item, armName, pivot));
        return true;
    }

    /**
     * Hangs the held item off the mob's mouth, for an animal that carries things in it.
     * <p>
     * The item sits a little forward of the mouth bone and a little below it, so it reads as being
     * carried rather than growing out of the face. Forward is negative Z here, which is why the
     * raccoon's snout ({@code [0, 9, -13]}) is further out than its head ({@code [0, 10.5, -8]}).
     */
    private static void addMouthItemBone(@NotNull JsonArray bones) {
        for (String wanted : MOUTHS) {
            for (var element : bones) {
                JsonObject bone = element.getAsJsonObject();
                if (!bone.has("name") || !simplify(bone.get("name").getAsString()).equals(wanted)) {
                    continue;
                }

                JsonArray mouth = bone.getAsJsonArray("pivot");
                if (mouth == null) {
                    continue;
                }

                JsonArray pivot = new JsonArray();
                pivot.add(mouth.get(0).getAsFloat());
                pivot.add(mouth.get(1).getAsFloat() - 1.0f);
                pivot.add(mouth.get(2).getAsFloat() - 2.0f);

                bones.add(itemBone("rightItem", bone.get("name").getAsString(), pivot));
                return;
            }
        }
    }

    @NotNull
    private static JsonObject itemBone(@NotNull String name, @NotNull String parent, @NotNull JsonArray pivot) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        bone.addProperty("parent", parent);
        bone.add("pivot", pivot);
        return bone;
    }

    /**
     * A bone's name with the ways of writing it that carry no meaning taken out, so that
     * {@code arm_right}, {@code ArmRight} and {@code armRight} are read as the same bone.
     */
    @NotNull
    private static String simplify(@NotNull String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace(".", "");
    }

    /**
     * A copy of the model grown slightly, for the second skin layer to be drawn on.
     * <p>
     * Java's outer layer is the same shape a quarter of a pixel larger, which is what keeps it
     * clear of the skin underneath. Bedrock has the same idea per cube, as {@code inflate}, so
     * every cube in the copy gets that much added to whatever it already had.
     *
     * @param geometry the model being copied
     * @param geometryName the original's name, which the copy must not reuse
     * @return the grown copy
     */
    @NotNull
    private static JsonObject inflate(@NotNull JsonObject geometry, @NotNull String geometryName) {
        JsonObject copy = geometry.deepCopy();

        JsonArray geometries = copy.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            return copy;
        }

        JsonObject first = geometries.get(0).getAsJsonObject();
        first.getAsJsonObject("description").addProperty("identifier", geometryName + ".overlay");

        JsonArray bones = first.getAsJsonArray("bones");
        if (bones == null) {
            return copy;
        }

        for (var bone : bones) {
            JsonArray cubes = bone.getAsJsonObject().getAsJsonArray("cubes");
            if (cubes == null) {
                continue;
            }

            for (var element : cubes) {
                JsonObject cube = element.getAsJsonObject();
                float existing = cube.has("inflate") ? cube.get("inflate").getAsFloat() : 0.0f;
                cube.addProperty("inflate", existing + OVERLAY_INFLATE);
            }
        }

        return copy;
    }

    /**
     * The animation in this file whose name says it does the given thing, if there is one.
     */
    @Nullable
    /**
     * Ties each of a mob's animations to the moment Bedrock should play it.
     * <p>
     * Everything read off the mob is declared here, including the animations nothing on the Bedrock
     * side can decide the timing of. Those are left declared but unplayed on purpose: an animation
     * has to be named in the entity before it can be started at all, so declaring it is what makes it
     * possible for the server to play it at the moment the mob actually performs it.
     */
    private static void bindAnimations(@NotNull JsonObject description, @NotNull JsonObject file,
                                       @NotNull Identifier entity) {
        JsonObject source = file.getAsJsonObject("animations");

        boolean walks = false;
        for (String full : source.keySet()) {
            if (!BedrockTriggers.isAlways(shortName(full)) && AnimationIndex.numberOf(entity, shortName(full)) == 0) {
                walks = true;
                break;
            }
        }

        JsonObject animations = new JsonObject();
        JsonArray animate = new JsonArray();

        int index = 0;
        // A mob with an animation for being in the air needs its walk asking about the ground as
        // well, or both play at once while it flies
        boolean flies = source.keySet().stream().anyMatch(full -> BedrockTriggers.isFlight(shortName(full)));

        for (String full : source.keySet()) {
            String name = shortName(full);
            if (animations.has(name)) {
                continue; // two of them shortened to the same thing; the first one keeps the name
            }

            animations.addProperty(name, full);

            if (BedrockTriggers.isAlways(name)) {
                JsonObject standing = new JsonObject();
                standing.addProperty(name, BedrockTriggers.always(walks));
                animate.add(standing);
                continue;
            }

            // Everything the client cannot work out for itself is played by watching a number on the
            // mob, and eased in and out by the controller written alongside. Listing it here as well
            // would play it twice, once without the easing
            if (AnimationIndex.numberOf(entity, name) > 0) {
                continue;
            }

            String when = BedrockTriggers.when(name, lengthOf(source.get(full)), index++, flies);
            if (when == null) {
                continue; // nothing here can say when it belongs, so it is not guessed at
            }

            JsonObject conditional = new JsonObject();
            conditional.addProperty(name, when);
            animate.add(conditional);
        }

        // The controller easing the mob into and out of what it performs. Named among the animations
        // like any other, and run once, which is how Bedrock's own mobs reference theirs
        List<String> performed = AnimationIndex.forEntity(entity);
        if (!performed.isEmpty()) {
            animations.addProperty("performs", controllerName(entity));
            animate.add("performs");
        }

        JsonObject scripts = new JsonObject();
        scripts.add("animate", animate);

        description.add("animations", animations);
        description.add("scripts", scripts);
    }

    /**
     * Builds the controller that eases a mob into and out of the animations it performs.
     * <p>
     * Listing an animation against a condition plays it the instant the condition is true and drops it
     * the instant it is not. There is no way to say "over a quarter of a second" in that form, so a
     * mob's arms arrive at the top of a swing already there, and leave it by vanishing - which reads
     * as a mob snapping between poses rather than moving between them.
     * <p>
     * A controller is the form that can say it. It is a set of states with a blend time, and Bedrock
     * eases across every change of state: one state for doing nothing, one per animation, and a
     * transition each way driven by the same number the animations were listed against. This is how
     * Bedrock's own mobs are animated, for the same reason.
     */
    @NotNull
    private static JsonObject animationController(@NotNull Identifier entity, @NotNull List<String> performed) {
        JsonObject states = new JsonObject();

        JsonArray fromRest = new JsonArray();
        for (int i = 0; i < performed.size(); i++) {
            String name = performed.get(i);
            int number = i + 1;

            JsonObject toAnimation = new JsonObject();
            toAnimation.addProperty(name, "query.property('" + AnimationIndex.PROPERTY + "') == " + number);
            fromRest.add(toAnimation);

            JsonArray back = new JsonArray();
            JsonObject toRest = new JsonObject();
            toRest.addProperty("rest", "query.property('" + AnimationIndex.PROPERTY + "') != " + number);
            back.add(toRest);

            JsonArray plays = new JsonArray();
            plays.add(name);

            JsonObject state = new JsonObject();
            state.add("animations", plays);
            state.add("transitions", back);
            state.addProperty("blend_transition", BLEND);
            states.add(name, state);
        }

        JsonObject rest = new JsonObject();
        rest.add("transitions", fromRest);
        rest.addProperty("blend_transition", BLEND);
        states.add("rest", rest);

        JsonObject controller = new JsonObject();
        controller.addProperty("initial_state", "rest");
        controller.add("states", states);

        JsonObject controllers = new JsonObject();
        controllers.add(controllerName(entity), controller);

        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.10.0");
        file.add("animation_controllers", controllers);
        return file;
    }

    /**
     * What the controller easing one mob's animations is called.
     */
    @NotNull
    private static String controllerName(@NotNull Identifier entity) {
        return "controller.animation." + entity.getNamespace() + "." + entity.getPath() + ".performs";
    }

    /**
     * The last part of an animation's full name, which is what the mod called it.
     */
    @NotNull
    private static String shortName(@NotNull String full) {
        int dot = full.lastIndexOf('.');
        return dot >= 0 && dot < full.length() - 1 ? full.substring(dot + 1) : full;
    }

    /**
     * How long an animation runs, in seconds, or a second if it does not say.
     */
    private static double lengthOf(@Nullable JsonElement animation) {
        if (animation == null || !animation.isJsonObject()) {
            return 1.0;
        }

        JsonElement length = animation.getAsJsonObject().get("animation_length");
        return length == null || !length.isJsonPrimitive() ? 1.0 : length.getAsDouble();
    }

    /**
     * The texture the client saw this mob's renderer ask for, if it recorded one.
     */
    @Nullable
    private static String recordedTexture(@NotNull Identifier entity) {
        Path file = newest("textures.json");
        if (file == null) {
            return null;
        }

        JsonObject known = readJson(file);
        return known != null && known.has(entity.toString())
                ? known.get(entity.toString()).getAsString()
                : null;
    }

    /**
     * The walk the client recorded off this mob's animation code, if it managed to.
     */
    @Nullable
    private static JsonObject recordedWalk(@NotNull Identifier entity) {
        Path file = newest(entity.getNamespace() + "." + entity.getPath() + ".animation.json");
        return file == null ? null : readJson(file);
    }

    /**
     * The most recently written of the two copies of a converted file.
     * <p>
     * There are two places these live: a folder beside the server, and the one the player's own client
     * writes into as it converts. Taking the server's copy whenever it existed looked like the obvious
     * order - it is the more deliberate of the two - but it quietly meant that once a file had been
     * written there, nothing the client produced afterwards was ever read again. A folder left behind
     * by an earlier session kept a server building packs from models two days old, including mobs from
     * mods that were no longer installed, and every improvement to the conversion appeared to do
     * nothing at all.
     * <p>
     * Whichever was written last is the one that was meant, which is true in both directions: a
     * hand-placed model still wins until the client converts a newer one.
     */
    @Nullable
    private static Path newest(@NotNull String name) {
        Path best = null;
        long newest = Long.MIN_VALUE;

        for (Path folder : List.of(geometryFolder(),
                org.geysermc.hydraulic.entity.client.EntityGeometryDump.directory())) {
            Path file = folder.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }

            try {
                long written = Files.getLastModifiedTime(file).toMillis();
                if (written > newest) {
                    newest = written;
                    best = file;
                }
            } catch (IOException e) {
                if (best == null) {
                    best = file; // unreadable timestamp is no reason to ignore the file itself
                }
            }
        }

        return best;
    }

    /**
     * The name the geometry gives itself, which the entity has to point at.
     */
    @Nullable
    private static String geometryName(@NotNull JsonObject geometry) {
        JsonArray geometries = geometry.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            return null;
        }

        JsonObject description = geometries.get(0).getAsJsonObject().getAsJsonObject("description");
        return description == null || !description.has("identifier") ? null : description.get("identifier").getAsString();
    }

    /**
     * The controllers every converted entity uses: one for the model, one for a second skin layer
     * drawn over it.
     */
    @NotNull
    private static JsonObject renderController() {
        JsonObject controllers = new JsonObject();
        controllers.add(RENDER_CONTROLLER, controller("default", "default"));
        controllers.add(OVERLAY_RENDER_CONTROLLER, controller("overlay", "overlay"));
        controllers.add(BABY_RENDER_CONTROLLER, babyController());

        JsonObject root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.add("render_controllers", controllers);
        return root;
    }

    /**
     * Draws the young of a mob with their own model rather than a shrunken adult.
     * <p>
     * Mods build a baby as a separate model - a bigger head on a shorter body, not the adult scaled
     * down - and the client converts it alongside. Bedrock picks between the two by index, so both
     * are listed and {@code query.is_baby} chooses. Only mobs that actually have a baby model use
     * this controller; the rest never name a baby geometry, and a controller reaching for one that
     * is not there would draw nothing at all.
     */
    @NotNull
    private static JsonObject babyController() {
        JsonArray options = new JsonArray();
        options.add("Geometry.default");
        options.add("Geometry.baby");

        JsonObject geometries = new JsonObject();
        geometries.add("array.models", options);

        JsonObject arrays = new JsonObject();
        arrays.add("geometries", geometries);

        JsonObject controller = babyLessController();
        controller.add("arrays", arrays);
        controller.addProperty("geometry", "array.models[query.is_baby ? 1 : 0]");
        return controller;
    }

    @NotNull
    private static JsonObject babyLessController() {
        JsonObject wildcard = new JsonObject();
        wildcard.addProperty("*", "Material.default");
        JsonArray materials = new JsonArray();
        materials.add(wildcard);

        JsonArray textures = new JsonArray();
        textures.add("Texture.default");

        JsonObject controller = new JsonObject();
        controller.add("materials", materials);
        controller.add("textures", textures);
        return controller;
    }

    /**
     * The layer a mod keeps a mob's young under, which is the adult's name with {@code _baby} added.
     */
    @NotNull
    private static Identifier babyOf(@NotNull Identifier entity) {
        return Identifier.fromNamespaceAndPath(entity.getNamespace(), entity.getPath() + "_baby");
    }

    /**
     * Draws the default geometry once with the named texture.
     */
    @NotNull
    private static JsonObject controller(@NotNull String layer, @NotNull String geometry) {
        JsonArray textures = new JsonArray();
        textures.add("Texture." + layer);

        JsonObject wildcard = new JsonObject();
        wildcard.addProperty("*", "Material." + layer);
        JsonArray materials = new JsonArray();
        materials.add(wildcard);

        JsonObject controller = new JsonObject();
        controller.addProperty("geometry", "Geometry." + geometry);
        controller.add("materials", materials);
        controller.add("textures", textures);
        return controller;
    }

    /**
     * Bedrock's own humanoid animations, which drive the bones a converted Java model already has.
     * <p>
     * Java animates an entity in code - a walk cycle is arithmetic in the mob's renderer, not data -
     * so there is nothing to convert the way geometry was. What there is instead is the fact that
     * these models are built on the same skeleton Bedrock's own are: {@code head}, {@code body},
     * {@code left_arm} and the rest. Bedrock's humanoid controllers move exactly those bones by name,
     * so pointing a converted mob at them gets a walk, a head that follows the player and arms that
     * swing, without a line of animation being translated.
     * <p>
     * A mob whose model has extra bones keeps them, they simply stay still; one built on a different
     * skeleton entirely gets nothing, which is what it has now.
     */
    @NotNull
    private static JsonObject animations(@NotNull Identifier entity) {
        JsonObject animations = new JsonObject();

        // These keys are not ours to choose. A humanoid controller looks its animations up by name
        // against this very map - the look_at_target controller asks for "look_at_target_default" and
        // the rest - so a missing key leaves the controller with nothing to play, and naming a
        // controller under the key it will itself look up sends it round in a circle. Both are what
        // Bedrock's own humanoids declare, which is why this mirrors them rather than tidying them up
        animations.addProperty("look_at_target_default", "animation.humanoid.look_at_target.default");
        animations.addProperty("look_at_target_gliding", "animation.humanoid.look_at_target.gliding");
        animations.addProperty("look_at_target_swimming", "animation.humanoid.look_at_target.swimming");
        animations.addProperty("look_at_target_controller", "controller.animation.humanoid.look_at_target");

        animations.addProperty("move", "animation.humanoid.move");
        animations.addProperty("move_controller", "controller.animation.humanoid.move");

        animations.addProperty("bob", "animation.humanoid.bob");
        animations.addProperty("bob_controller", "controller.animation.humanoid.bob");

        // Poses the arm around whatever the mob is carrying, and swings it when it attacks
        animations.addProperty("holding", "animation.humanoid.holding");
        animations.addProperty("holding_controller", "controller.animation.humanoid.holding");

        // Draws the bow back as the mob aims, which without this it holds out unmoving. The charging
        // and use-progress pair go with it: they are what say how far through drawing it is, and the
        // bow animation reads that rather than working it out for itself
        animations.addProperty("bow_and_arrow", "animation.humanoid.bow_and_arrow");
        animations.addProperty("bow_and_arrow_controller", "controller.animation.humanoid.bow_and_arrow");

        animations.addProperty("charging", "animation.humanoid.charging");
        animations.addProperty("charging_controller", "controller.animation.humanoid.charging");

        animations.addProperty("use_item_progress", "animation.humanoid.use_item_progress");
        animations.addProperty("use_item_progress_controller", "controller.animation.humanoid.use_item_progress");

        // A skeleton holds its bow differently from anything else, which vanilla gives its own
        // animation. Read from the tag, so a mod's skeleton variant gets it and its zombie does not
        if (isSkeleton(entity)) {
            animations.addProperty("skeleton_attack", "animation.skeleton.attack");
            animations.addProperty("skeleton_attack_controller", "controller.animation.skeleton.attack");
        }

        animations.addProperty("attack.rotations", "animation.humanoid.attack.rotations");
        animations.addProperty("attack_controller", "controller.animation.humanoid.attack");

        // The arms-out lurch is particular to zombies, not something every humanoid does, so it is
        // put on the ones the game itself calls zombies. Reading that from the tag rather than the
        // name means a mod's own zombie variant is caught without knowing anything about the mod
        if (isZombie(entity)) {
            animations.addProperty("zombie_attack_bare_hand", "animation.zombie.attack_bare_hand");
            animations.addProperty("zombie_attack_bare_hand_controller", "controller.animation.zombie.attack_bare_hand");
            // The controller reaches for the young one's version too, whether or not this mob has
            // young - leaving it out gives "can't find animation baby_zombie_attack_bare_hand"
            animations.addProperty("baby_zombie_attack_bare_hand", "animation.zombie.baby_attack_bare_hand");
        }

        return animations;
    }

    /**
     * Whether the game considers this entity a zombie, however a mod chose to name it.
     */
    private static boolean isZombie(@NotNull Identifier entity) {
        return isTagged(entity, EntityTypeTags.ZOMBIES);
    }

    /**
     * Whether the game considers this entity a skeleton, however a mod chose to name it.
     */
    private static boolean isSkeleton(@NotNull Identifier entity) {
        return isTagged(entity, EntityTypeTags.SKELETONS);
    }

    private static boolean isTagged(@NotNull Identifier entity, @NotNull net.minecraft.tags.TagKey<EntityType<?>> tag) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entity);
        return type != null && type.builtInRegistryHolder().is(tag);
    }

    @NotNull
    private static JsonObject animationScripts(@NotNull Identifier entity, boolean hasBabyModel) {
        JsonArray preAnimation = new JsonArray();
        // The walk cycle's phase. Vanilla divides this by a gliding speed it sets up elsewhere; that
        // variable does not exist here, and dividing by nothing would leave every limb still
        preAnimation.add("v.tcos0 = Math.cos(query.modified_distance_moved * 38.17) * query.modified_move_speed * 57.3;");

        // How far through using something the mob is, counted up while the button is held. The bow
        // animation reads this to decide how far back to draw; without it there is nothing to draw by
        // and the bow is simply held out
        preAnimation.add("v.item_use_duration = query.is_using_item ? "
                + "((v.item_use_duration ?? 0.0) + query.delta_time * 20.0) : 0.0;");
        preAnimation.add("v.item_use_normalized = v.item_use_duration / query.main_hand_item_max_duration;");

        // The controllers are what gets played; they pick the animations above for themselves
        JsonArray animate = new JsonArray();
        animate.add("look_at_target_controller");
        animate.add("move_controller");
        animate.add("bob_controller");
        animate.add("holding_controller");
        animate.add("attack_controller");
        animate.add("bow_and_arrow_controller");
        animate.add("charging_controller");
        animate.add("use_item_progress_controller");

        if (isSkeleton(entity)) {
            animate.add("skeleton_attack_controller");
        }

        if (isZombie(entity)) {
            animate.add("zombie_attack_bare_hand_controller");
        }

        JsonObject scripts = new JsonObject();
        scripts.add("pre_animation", preAnimation);
        scripts.add("animate", animate);

        // Bedrock halves a young mob on its own, which is right when it is shrinking the adult model
        // but wrong once it is given a model already built to a baby's proportions - that gets
        // shrunk a second time and comes out squashed. Vanilla answers this by doubling back, and
        // only ever on mobs that have their own baby model
        if (hasBabyModel) {
            scripts.addProperty("scale", "query.is_baby ? 2.0 : 1.0");
        }

        return scripts;
    }

    /**
     * Builds the entity definition that ties the shape, the texture and the material together.
     */
    @NotNull
    private static JsonObject clientEntity(@NotNull Identifier entity, @NotNull String geometryName,
                                           @NotNull String texture, @Nullable String overlay,
                                           @Nullable String overlayGeometry, @Nullable String babyGeometry,
                                           @Nullable JsonObject animationFile) {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", geometryName);
        if (overlayGeometry != null) {
            geometry.addProperty("overlay", overlayGeometry);
        }
        if (babyGeometry != null) {
            geometry.addProperty("baby", babyGeometry);
        }

        JsonObject textures = new JsonObject();
        textures.addProperty("default", texture);

        JsonObject materials = new JsonObject();
        // Entity textures are usually cut out rather than blended, which is what alphatest draws
        materials.addProperty("default", "entity_alphatest");

        JsonArray renderControllers = new JsonArray();
        renderControllers.add(babyGeometry == null ? RENDER_CONTROLLER : BABY_RENDER_CONTROLLER);

        if (overlay != null) {
            textures.addProperty("overlay", overlay);
            materials.addProperty("overlay", "entity_alphatest");
            renderControllers.add(OVERLAY_RENDER_CONTROLLER);
        }

        JsonObject description = new JsonObject();
        description.addProperty("identifier", entity.toString());
        description.add("materials", materials);
        description.add("textures", textures);
        description.add("geometry", geometry);
        if (animationFile == null) {
            description.add("animations", animations(entity));
            description.add("scripts", animationScripts(entity, babyGeometry != null));
        } else {
            // Its own animations rather than Bedrock's humanoid ones, which move bones named for arms
            // and legs that a centipede has not got and would do nothing here but risk complaint
            bindAnimations(description, animationFile, entity);
        }
        description.add("render_controllers", renderControllers);

        // Without this Bedrock draws the mob but nothing it is carrying - no sword, no bow. It is a
        // flag on the entity rather than anything to do with the item, so it has to be set here
        description.addProperty("enable_attachables", true);

        JsonObject clientEntity = new JsonObject();
        clientEntity.add("description", description);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", ENTITY_FORMAT_VERSION);
        root.add("minecraft:client_entity", clientEntity);
        return root;
    }

    /**
     * Where the entity's texture ends up in the Bedrock pack.
     * <p>
     * Which texture a mob actually uses is chosen by its renderer, which this side cannot see, so it
     * has to be found by looking. {@code textures/entity/<name>.png} is the common case, but plenty
     * of mods give each mob a folder of its own - {@code textures/entity/gelid/gelid.png} - and
     * naming the folder alone leaves Bedrock with nothing to draw and nothing to complain about,
     * which is a particularly quiet way to fail.
     */
    @NotNull
    private static String texture(@NotNull PackPostProcessContext<EntityPackModule> context, @NotNull Identifier entity) {
        // The renderer's own answer, where the client managed to record one. Nothing guessed from a
        // name can beat it: a centipede_head is drawn with cave_centipede.png, which no rule relating
        // the two would ever arrive at
        String recorded = recordedTexture(entity);
        if (recorded != null) {
            Identifier key = Identifier.tryParse(recorded);
            if (key != null) {
                String path = key.getPath();
                if (path.startsWith("textures/")) {
                    path = path.substring("textures/".length());
                }
                if (path.endsWith(".png")) {
                    path = path.substring(0, path.length() - ".png".length());
                }

                return output(context, key.getNamespace(), path);
            }
        }

        String textures = "assets/" + entity.getNamespace() + "/textures/";

        if (context.mod().resolveFile(textures + "entity/" + entity.getPath() + ".png") != null) {
            return output(context, entity.getNamespace(), "entity/" + entity.getPath());
        }

        Path found = findIn(context.mod(), textures + "entity", entity.getPath() + ".png");
        String relative = found == null ? null : belowTextures(found);
        if (relative != null) {
            return output(context, entity.getNamespace(), relative);
        }

        // A mob with colour or size variants has no plainly named texture at all - a catfish ships
        // catfish_small, catfish_medium and catfish_large and nothing called catfish. Bedrock is given
        // the first of them, since one variant drawn is better than the untextured block of colour it
        // would get otherwise. Which variant a particular mob should wear is decided by data this side
        // cannot see
        Path prefixed = firstStartingWith(context.mod(), textures + "entity", entity.getPath() + "_");
        String prefixedPath = prefixed == null ? null : belowTextures(prefixed);
        if (prefixedPath != null) {
            context.logger().info("{} has no texture of its own, drawing every one as {}", entity, prefixed.getFileName());
            return output(context, entity.getNamespace(), prefixedPath);
        }

        Path variant = firstIn(context.mod(), textures + "entity/" + entity.getPath());
        String variantPath = variant == null ? null : belowTextures(variant);
        if (variantPath != null) {
            context.logger().info("{} has no texture of its own, drawing every one as {}", entity, variant.getFileName());
            return output(context, entity.getNamespace(), variantPath);
        }

        context.logger().warn("Found no texture for {}, it will draw untextured", entity);
        return output(context, entity.getNamespace(), "entity/" + entity.getPath());
    }

    /**
     * This mob's own picture, and only its own.
     * <p>
     * Deliberately stricter than the texture lookup used for a mob that has a model. That one always
     * answers, falling back through near-misses and finally to a name that may not exist, because a
     * mob with a model is going to be drawn either way and an untextured shape is still a shape.
     * Here the answer decides whether the mob is drawn at all, and inventing one would paint some
     * unrelated picture onto a thing that is meant to be invisible - a portal, a sound, the echo a
     * whale sends out.
     *
     * @return where its picture ended up in the pack, or null if it has none of its own
     */
    @Nullable
    private static String spriteTexture(@NotNull PackPostProcessContext<EntityPackModule> context,
                                        @NotNull Identifier entity) {
        String recorded = recordedTexture(entity);
        if (recorded != null) {
            Identifier key = Identifier.tryParse(recorded);
            if (key != null) {
                String path = key.getPath();
                if (path.startsWith("textures/")) {
                    path = path.substring("textures/".length());
                }
                if (path.endsWith(".png")) {
                    path = path.substring(0, path.length() - ".png".length());
                }

                return output(context, key.getNamespace(), path);
            }
        }

        String textures = "assets/" + entity.getNamespace() + "/textures/";
        if (context.mod().resolveFile(textures + "entity/" + entity.getPath() + ".png") != null) {
            return output(context, entity.getNamespace(), "entity/" + entity.getPath());
        }

        Path found = findIn(context.mod(), textures + "entity", entity.getPath() + ".png");
        String relative = found == null ? null : belowTextures(found);
        return relative == null ? null : output(context, entity.getNamespace(), relative);
    }

    /**
     * The mob's second skin layer, if it has one.
     * <p>
     * Java draws things like a zombie's tattered clothing as another pass over the same model with a
     * second texture. Bedrock does the same given a second render controller, so the layer is looked
     * for under the usual {@code _overlay} name.
     */
    @Nullable
    private static String overlay(@NotNull PackPostProcessContext<EntityPackModule> context, @NotNull Identifier entity) {
        Path found = findIn(context.mod(), "assets/" + entity.getNamespace() + "/textures/entity",
                entity.getPath() + "_overlay.png");

        String relative = found == null ? null : belowTextures(found);
        return relative == null ? null : output(context, entity.getNamespace(), relative);
    }

    /**
     * The first texture whose name begins with this, for mobs whose variants sit beside each other
     * rather than in a folder of their own.
     */
    @Nullable
    private static Path firstStartingWith(@NotNull ModInfo mod, @NotNull String directory, @NotNull String prefix) {
        for (Path root : mod.roots()) {
            Path start = root.resolve(directory.replace("/", root.getFileSystem().getSeparator()));
            if (!Files.isDirectory(start)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(start)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .filter(path -> path.getFileName().toString().endsWith(".png"))
                        .sorted()
                        .findFirst()
                        .orElse(null);

                if (match != null) {
                    return match;
                }
            } catch (IOException e) {
                // A root we cannot walk is simply one that does not have it
            }
        }

        return null;
    }

    /**
     * The first texture in a folder, in a stable order so the choice does not move between runs.
     */
    @Nullable
    private static Path firstIn(@NotNull ModInfo mod, @NotNull String directory) {
        for (Path root : mod.roots()) {
            Path start = root.resolve(directory.replace("/", root.getFileSystem().getSeparator()));
            if (!Files.isDirectory(start)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(start)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".png"))
                        .filter(path -> !path.getFileName().toString().contains("_overlay"))
                        .sorted()
                        .findFirst()
                        .orElse(null);

                if (match != null) {
                    return match;
                }
            } catch (IOException e) {
                // A root we cannot walk is simply one that does not have it
            }
        }

        return null;
    }

    @NotNull
    private static String output(@NotNull PackPostProcessContext<EntityPackModule> context, @NotNull String namespace, @NotNull String path) {
        return getOutputFromModel(context, Key.key(namespace, path)).replace(".png", "");
    }

    /**
     * The part of a texture's path below {@code textures/}, which is what the pack is keyed on.
     */
    @Nullable
    private static String belowTextures(@NotNull Path file) {
        String path = file.toString().replace('\\', '/');
        int index = path.indexOf("/textures/");
        if (index == -1) {
            return null;
        }

        String relative = path.substring(index + "/textures/".length());
        return relative.endsWith(".png") ? relative.substring(0, relative.length() - ".png".length()) : relative;
    }

    /**
     * Looks for a file anywhere beneath a folder in the mod, for mods that nest their assets.
     */
    @Nullable
    private static Path findIn(@NotNull ModInfo mod, @NotNull String directory, @NotNull String fileName) {
        for (Path root : mod.roots()) {
            Path start = root.resolve(directory.replace("/", root.getFileSystem().getSeparator()));
            if (!Files.isDirectory(start)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(start)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(fileName))
                        .findFirst()
                        .orElse(null);

                if (match != null) {
                    return match;
                }
            } catch (IOException e) {
                // A root we cannot walk is simply one that does not have it
            }
        }

        return null;
    }

    @Nullable
    private static JsonObject readJson(@NotNull Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
