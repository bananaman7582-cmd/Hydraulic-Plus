package org.geysermc.hydraulic.block;

import com.google.auto.service.AutoService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.geysermc.geyser.api.block.custom.CustomBlockData;
import org.geysermc.geyser.api.block.custom.CustomBlockPermutation;
import org.geysermc.geyser.api.block.custom.CustomBlockState;
import org.geysermc.geyser.api.block.custom.NonVanillaCustomBlockData;
import org.geysermc.geyser.api.block.custom.component.BoxComponent;
import org.geysermc.geyser.api.block.custom.component.CustomBlockComponents;
import org.geysermc.geyser.api.block.custom.component.GeometryComponent;
import org.geysermc.geyser.api.block.custom.component.MaterialInstance;
import org.geysermc.geyser.api.block.custom.component.TransformationComponent;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBlockState;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBoundingBox;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.level.physics.PistonBehavior;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.hydraulic.Constants;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.item.CreativeMappings;
import org.geysermc.hydraulic.pack.PackLogListener;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.context.PackContext;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.pack.context.PackPreProcessContext;
import org.geysermc.hydraulic.storage.ModStorage;
import org.geysermc.hydraulic.util.GeoUtil;
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.hydraulic.util.SingletonBlockGetter;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Description;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.blockstate.Condition;
import team.unnamed.creative.blockstate.MultiVariant;
import team.unnamed.creative.blockstate.Selector;
import team.unnamed.creative.blockstate.Variant;
import team.unnamed.creative.metadata.animation.AnimationMeta;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;
import team.unnamed.creative.model.ModelTextures;
import team.unnamed.creative.texture.Texture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

@AutoService(PackModule.class)
public class BlockPackModule extends PackModule<BlockPackModule> {
    private static final String STATE_CONDITION = "query.block_property('%s') == %s";

    /**
     * The largest a block's geometry may measure on an axis before Bedrock refuses to render it -
     * {@code 1 + 14/16} blocks. See {@link org.geysermc.hydraulic.util.GeoUtil}.
     */
    private static final float MAX_VISIBLE_BOUNDS = 1.875f;

    /**
     * The furthest apart a model's boxes may be along any axis, in pixels. Bedrock allows a model to
     * span 1+14/16 blocks and refuses the geometry outright beyond that - which reads in the log as
     * "Total length of parts ... is greater than 1 + 14/16ths" and in game as a block that does not
     * render at all.
     */
    private static final float MAX_SPAN = 30.0f;

    /**
     * How far a block's own cube reaches on each axis, in pixels.
     */
    private static final float UNIT_CUBE = 16.0f;

    /**
     * Stand-in geometry for blocks whose model we cannot supply. It renders nothing, but it exists,
     * which is what matters: a block pointing at a geometry the pack does not contain is thrown out
     * by the client entirely, and a thrown-out block shifts every block after it in the palette.
     */
    private static final String EMPTY_GEOMETRY = "geometry." + Constants.MOD_ID + ".empty";

    /**
     * The most values Bedrock accepts in a single block state property.
     */
    private static final int MAX_PROPERTY_VALUES = 16;

    private final Map<String, StateDefinition> blockStates = new HashMap<>();
    private final Set<String> emptyModels = new HashSet<>();

    /**
     * Geometry that reaches outside the block's own cube, by identifier.
     * <p>
     * Bedrock holds a block that carries a rotation to a stricter rule than one that does not: the
     * model has to sit inside the unit cube, not merely within the 1+14/16 block span every other
     * block is measured against. A wall torch or an overhanging mushroom fails that, and the cost is
     * not the rotation but the whole definition - the log says "is not included within the unit cube"
     * and then "cannot find geometry JSON", and the block draws nothing at all.
     */
    private final Set<String> outsideUnitCube = new HashSet<>();

    public BlockPackModule() {
        this.listenOn(GeyserDefineCustomBlocksEvent.class, this::onDefineCustomBlocks);

        this.preProcess(this::preProcess);
        this.postProcess(this::postProcess);
    }

    private void preProcess(@NotNull PackPreProcessContext<BlockPackModule> context) {
        // One listener for the whole pass. Stitching reports a missing parent model once per model
        // that wanted it, and mods share the same few parents across hundreds of models, so a
        // listener made fresh for each one has nothing to recognise as a repeat and every copy is
        // printed
        PackLogListener listener = new PackLogListener(context.logger());

        for (var blockState : context.assets(ResourcePack::blockStates)) {
            this.blockStates.put(blockState.key().toString(), new StateDefinition(blockState, context.modelProvider()));
        }

        ModStorage storage = context.storage();
        if (storage.materials().materials().isEmpty()) {
            Materials materials = new Materials();
            for (Model model : context.assets(ResourcePack::models)) {
                Model stitchedModel = new ModelStitcher(context.modelProvider(), model, listener).stitch();
                if (stitchedModel == null) {
                    context.logger().warn("Could not find a stitched model for block {}", model.key());
                    continue;
                }

                Map<String, String> textures = new HashMap<>();
                Map<String, ModelTexture> modelTextures = getTextures(stitchedModel.textures());
                for (Map.Entry<String, ModelTexture> entry : modelTextures.entrySet()) {
                    ModelTexture modelTexture = getModelTexture(modelTextures, entry.getKey());
                    if (modelTexture == null || modelTexture.key() == null) {
                        // LOGGER.warn("Could not find a texture for key {} in model {}", entry.getKey(), model.key());
                        continue;
                    }

                    textures.put(entry.getKey(), modelTexture.key().toString());
                }

                Materials.Material material = new Materials.Material(textures);
                materials.addMaterial(model.key().toString(), material);
            }

            storage.materials(materials);
            storage.save();
        }

        // Check for empty models
        List<Block> blocks = context.registryValues(BuiltInRegistries.BLOCK);
        DefaultedRegistry<Block> registry = BuiltInRegistries.BLOCK;
        for (Block block : blocks) {
            Identifier blockLocation = registry.getKey(block);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelDefinition definition = getModel(context, blockLocation, state);
                if (definition == null) {
                    continue;
                }

                Model model = definition.model();
                Key key = model.key();

                // Skip unit cube models
                if (isUnitCube(model.parent())) {
                    continue;
                }

                // Check if the model is empty
                Model stitchedModel = new ModelStitcher(context.modelProvider(), model, listener).stitch();
                if (!stitchedModel.elements().isEmpty()) {
                    continue;
                }

                emptyModels.add(key.toString());
            }
        }

        int suppressed = listener.suppressed();
        if (suppressed > 0) {
            context.logger().info("Held back {} repeated model warning(s) for mod {}", suppressed, context.mod().id());
        }
    }

    private void postProcess(@NotNull PackPostProcessContext<BlockPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        writeBlockSounds(context, bedrockPack);
        fixGeometryBounds(context, bedrockPack);

        for (Texture texture : assets.textures()) {
            Key key = texture.key();
            String value = key.value();

            if (value.startsWith("block/")) {
                String cleanPath = value.replace("block/", "").replace(".png", "");

                String outputLoc = String.format(Constants.BEDROCK_TEXTURE_LOCATION, "blocks/" + context.mod().id() + "/" + cleanPath).replace(".png", "");
                String id = key.namespace() + ":" + cleanPath;
                bedrockPack.addBlockTexture(id, outputLoc);

                // If the texture is animated, add it to the flipbook textures
                if (texture.hasMetadata()) {
                    AnimationMeta animationMeta = texture.meta().meta(AnimationMeta.class);
                    if (animationMeta != null) {
                        bedrockPack.addFlipbookTexture(id, outputLoc, animationMeta.frameTime());
                    }
                }
            }
        }
    }

    /**
     * Drops geometry boxes that fall outside the bounds Bedrock allows for a block.
     * <p>
     * Bedrock only renders a custom block whose boxes stay within {@code -0.875} to {@code 1.875}
     * blocks on every axis, and it does not fail gracefully: a single offending box makes
     * <b>every custom block in the chunk disappear</b> (GeyserMC/Hydraulic#11, and upstream
     * MCPE-152191). Java has no such limit, so any mod model reaching further than that would take
     * unrelated blocks down with it. Removing just the offending boxes keeps the rest of the model -
     * and every other block in the chunk - rendering.
     */
    private void fixGeometryBounds(@NotNull PackPostProcessContext<BlockPackModule> context, @NotNull BedrockResourcePack bedrockPack) {
        Map<String, ModelEntity> models = bedrockPack.blockModels();
        if (models == null || models.isEmpty()) {
            return;
        }

        int removed = 0;
        int clamped = 0;
        int narrowed = 0;
        Set<String> affected = new HashSet<>();
        for (Map.Entry<String, ModelEntity> entry : models.entrySet()) {
            ModelEntity model = entry.getValue();
            if (model == null || model.geometry() == null) {
                continue;
            }

            for (Geometry geometry : model.geometry()) {
                // The converter declares visible bounds of 2 blocks, which is over the limit Bedrock
                // allows a block's geometry to measure, so it refuses to render the block at all.
                // Only blocks whose model became a unit cube escaped this, because those are given
                // Bedrock's own full block geometry instead of the converted one.
                Description description = geometry.description();
                if (description != null) {
                    if (description.visibleBoundsWidth() != null && description.visibleBoundsWidth() > MAX_VISIBLE_BOUNDS) {
                        description.visibleBoundsWidth(MAX_VISIBLE_BOUNDS);
                        clamped++;
                    }
                    if (description.visibleBoundsHeight() != null && description.visibleBoundsHeight() > MAX_VISIBLE_BOUNDS) {
                        description.visibleBoundsHeight(MAX_VISIBLE_BOUNDS);
                    }
                }

                if (geometry.bones() == null) {
                    continue;
                }

                // Bedrock measures the model as a whole as well as box by box, and refuses one whose
                // parts span more than 1+14/16 blocks along any axis - "Total length of parts ... is
                // greater than 1 + 14/16ths". A model can sit comfortably inside the per-box limits
                // and still fail this, since two boxes at opposite extremes are each allowed where
                // the distance between them is not
                if (pullInside(geometry)) {
                    affected.add(entry.getKey());
                    narrowed++;
                }

                // Noted rather than altered. Squashing a wall torch into the cube would be a worse
                // block than one that hangs correctly and does not turn, so the rotation is dropped
                // later instead - see the field this records into
                if (description != null && description.identifier() != null && !fitsUnitCube(geometry)) {
                    this.outsideUnitCube.add(description.identifier());
                }

                for (Bones bone : geometry.bones()) {
                    List<Cubes> cubes = bone.cubes();
                    if (cubes == null || cubes.isEmpty()) {
                        continue;
                    }

                    List<Cubes> kept = cubes.stream().filter(BlockPackModule::withinBedrockBounds).toList();
                    if (kept.size() != cubes.size()) {
                        removed += cubes.size() - kept.size();
                        affected.add(entry.getKey());
                        bone.cubes(kept);
                    }
                }
            }
        }

        if (clamped > 0) {
            context.logger().info("Clamped the visible bounds of {} block geometry/geometries to {} blocks so Bedrock will render them",
                    clamped, MAX_VISIBLE_BOUNDS);
        }

        if (narrowed > 0) {
            context.logger().info("Pulled {} block model(s) back inside the {} blocks Bedrock lets a model span; "
                    + "without this Bedrock refuses the geometry and the block does not render at all",
                    narrowed, MAX_VISIBLE_BOUNDS);
        }

        if (removed > 0) {
            context.logger().warn("Removed {} geometry box(es) reaching outside Bedrock's block bounds from {} model(s); " +
                    "those blocks will render incompletely, but leaving them in hides every custom block in the chunk. Models: {}",
                    removed, affected.size(), affected);
        }
    }

    /**
     * Brings a model's boxes inside the span Bedrock allows a block to occupy.
     * <p>
     * The limit is on the model as a whole: however far apart its furthest two boxes are along an
     * axis, that distance may not exceed 1+14/16 blocks. Java has no such rule, so a rug that
     * overhangs its block or a rope that hangs below one comes across too wide and Bedrock throws the
     * whole geometry out - which is why those blocks then report "cannot find geometry JSON" and draw
     * nothing whatsoever.
     * <p>
     * Boxes are pulled in to the edge of the allowed window rather than dropped. A box outside it is
     * usually the largest part of the model - the rug itself - and removing it leaves a block that
     * renders correctly and is invisible, which is no better than the geometry being refused.
     *
     * @return whether anything had to be moved
     */
    /**
     * Whether every part of a model sits inside the block's own cube.
     * <p>
     * Only asked of blocks that would carry a rotation, since that is the only time Bedrock enforces
     * it. The cube runs from 0 to 16 on each axis; a box starting before 0 or ending past 16 is
     * outside it, however narrow the model is overall.
     */
    /**
     * Widens a box's measured extent to cover the space it sweeps once it is turned.
     * <p>
     * The eight corners are turned about the cube's pivot and the result measured around them. Doing
     * it properly matters because the error runs one way: a turned box always reaches at least as far
     * as the untilted one, so measuring the untilted figure reports a model as fitting when Bedrock
     * will find it does not, and refuse the geometry outright.
     *
     * @param low the box's lowest corner per axis, widened in place
     * @param high the box's highest corner per axis, widened in place
     */
    private static void expandForRotation(@NotNull Cubes cube, float[] low, float[] high) {
        float[] rotation = cube.rotation();
        if (rotation == null || rotation.length < 3
                || (rotation[0] == 0 && rotation[1] == 0 && rotation[2] == 0)) {
            return;
        }

        float[] pivot = cube.pivot();
        if (pivot == null || pivot.length < 3) {
            pivot = new float[] { 0, 0, 0 };
        }

        double x = Math.toRadians(rotation[0]);
        double y = Math.toRadians(rotation[1]);
        double z = Math.toRadians(rotation[2]);

        float[] turnedLow = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        float[] turnedHigh = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };

        for (int corner = 0; corner < 8; corner++) {
            double px = ((corner & 1) == 0 ? low[0] : high[0]) - pivot[0];
            double py = ((corner & 2) == 0 ? low[1] : high[1]) - pivot[1];
            double pz = ((corner & 4) == 0 ? low[2] : high[2]) - pivot[2];

            // X, then Y, then Z, which is the order Bedrock turns a cube in
            double y1 = py * Math.cos(x) - pz * Math.sin(x);
            double z1 = py * Math.sin(x) + pz * Math.cos(x);

            double x2 = px * Math.cos(y) + z1 * Math.sin(y);
            double z2 = -px * Math.sin(y) + z1 * Math.cos(y);

            double x3 = x2 * Math.cos(z) - y1 * Math.sin(z);
            double y3 = x2 * Math.sin(z) + y1 * Math.cos(z);

            double[] turned = { x3 + pivot[0], y3 + pivot[1], z2 + pivot[2] };
            for (int axis = 0; axis < 3; axis++) {
                turnedLow[axis] = Math.min(turnedLow[axis], (float) turned[axis]);
                turnedHigh[axis] = Math.max(turnedHigh[axis], (float) turned[axis]);
            }
        }

        System.arraycopy(turnedLow, 0, low, 0, 3);
        System.arraycopy(turnedHigh, 0, high, 0, 3);
    }

    /**
     * The property that puts a block beyond what Bedrock can describe, if it has one.
     * <p>
     * Bedrock allows a state property sixteen values; a mod's growth stage or spread counter can run
     * to a hundred and more. Such a block is left unregistered, and everything written about it has
     * to agree with that - naming it in {@code blocks.json} while it is absent from the registry
     * tells the client about a block it does not have.
     */
    @Nullable
    private static Property<?> oversizedProperty(@NotNull Block block) {
        return block.getStateDefinition().getProperties().stream()
                .filter(property -> property.getPossibleValues().size() > MAX_PROPERTY_VALUES)
                .findFirst()
                .orElse(null);
    }

    private static boolean fitsUnitCube(@NotNull Geometry geometry) {
        if (geometry.bones() == null) {
            return true;
        }

        for (Bones bone : geometry.bones()) {
            if (bone.cubes() == null) {
                continue;
            }

            for (Cubes cube : bone.cubes()) {
                float[] origin = cube.origin();
                float[] size = cube.size();
                if (origin == null || size == null || origin.length < 3 || size.length < 3) {
                    continue;
                }

                float[] low = new float[3];
                float[] high = new float[3];
                for (int axis = 0; axis < 3; axis++) {
                    low[axis] = Math.min(origin[axis], origin[axis] + size[axis]);
                    high[axis] = Math.max(origin[axis], origin[axis] + size[axis]);
                }

                // Turned boxes reach further than they read, here as much as anywhere
                expandForRotation(cube, low, high);

                for (int axis = 0; axis < 3; axis++) {
                    if (low[axis] < 0 || high[axis] > UNIT_CUBE) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static boolean pullInside(@NotNull Geometry geometry) {
        if (geometry.bones() == null) {
            return false;
        }

        // What the model actually measures, before touching any of it. The limit is on the distance
        // between the furthest two boxes, so a model that reaches outside the block but stays narrow
        // is perfectly legal - a door hanging a little past its own edges measures well under the
        // limit, and moving it would only shift it off the hitbox it is supposed to sit on
        float[] low = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        float[] high = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };

        for (Bones bone : geometry.bones()) {
            if (bone.cubes() == null) {
                continue;
            }

            for (Cubes cube : bone.cubes()) {
                float[] origin = cube.origin();
                float[] size = cube.size();
                if (origin == null || size == null || origin.length < 3 || size.length < 3) {
                    continue;
                }

                float[] cubeLow = new float[3];
                float[] cubeHigh = new float[3];
                for (int axis = 0; axis < 3; axis++) {
                    cubeLow[axis] = Math.min(origin[axis], origin[axis] + size[axis]);
                    cubeHigh[axis] = Math.max(origin[axis], origin[axis] + size[axis]);
                }

                // A turned box sweeps a wider space than the one its origin and size describe: stand a
                // plank on its corner and it reaches further across than it does flat. Bedrock measures
                // what the box actually occupies, so measuring the untilted figure reported the model
                // as fitting when it did not, and the block was refused anyway - Alex's Mobs' bison fur
                // is one, and its cubes are all turned
                expandForRotation(cube, cubeLow, cubeHigh);

                for (int axis = 0; axis < 3; axis++) {
                    low[axis] = Math.min(low[axis], cubeLow[axis]);
                    high[axis] = Math.max(high[axis], cubeHigh[axis]);
                }
            }
        }

        // Only the axes that genuinely overrun are touched, and each is given a window of exactly the
        // allowed width placed over the middle of what the model already occupies, so whatever is
        // trimmed is taken evenly off both ends rather than shunting the model to one side
        float[] windowMin = new float[3];
        float[] windowMax = new float[3];
        boolean overruns = false;

        for (int axis = 0; axis < 3; axis++) {
            if (low[axis] > high[axis] || high[axis] - low[axis] <= MAX_SPAN) {
                windowMin[axis] = -Float.MAX_VALUE;
                windowMax[axis] = Float.MAX_VALUE;
                continue;
            }

            float middle = (low[axis] + high[axis]) / 2.0f;
            windowMin[axis] = middle - MAX_SPAN / 2.0f;
            windowMax[axis] = middle + MAX_SPAN / 2.0f;
            overruns = true;
        }

        if (!overruns) {
            return false;
        }

        boolean changed = false;
        for (Bones bone : geometry.bones()) {
            if (bone.cubes() == null) {
                continue;
            }

            for (Cubes cube : bone.cubes()) {
                float[] origin = cube.origin();
                float[] size = cube.size();
                if (origin == null || size == null || origin.length < 3 || size.length < 3) {
                    continue;
                }

                for (int axis = 0; axis < 3; axis++) {
                    float min = Math.min(origin[axis], origin[axis] + size[axis]);
                    float max = Math.max(origin[axis], origin[axis] + size[axis]);

                    float pulledMin = Math.max(min, windowMin[axis]);
                    float pulledMax = Math.min(max, windowMax[axis]);

                    if (pulledMin == min && pulledMax == max) {
                        continue;
                    }

                    // A box entirely outside the window has nothing left to keep; collapsing it to
                    // nothing is what dropping it would do, without disturbing the list being walked
                    origin[axis] = pulledMin;
                    size[axis] = Math.max(0.0f, pulledMax - pulledMin);
                    changed = true;
                }
            }
        }

        return changed;
    }

    /**
     * Checks whether a geometry box stays inside the region Bedrock will render a block in.
     * <p>
     * Bedrock allows {@code -0.875} to {@code 1.875} blocks on each axis. In the coordinates its
     * block geometry uses, the block itself spans {@code -8..8} horizontally and {@code 0..16}
     * vertically, which puts the limits at {@code -22..22} and {@code -14..30} respectively.
     */
    private static boolean withinBedrockBounds(@NotNull Cubes cube) {
        float[] origin = cube.origin();
        float[] size = cube.size();
        if (origin == null || size == null || origin.length < 3 || size.length < 3) {
            return true; // nothing we can measure, leave it alone
        }

        // "inflate" grows the box on every side, so it counts towards the bounds
        float inflate = cube.inflate() == null ? 0 : cube.inflate();

        for (int axis = 0; axis < 3; axis++) {
            float start = origin[axis];
            float end = origin[axis] + size[axis];

            float min = Math.min(start, end) - inflate;
            float max = Math.max(start, end) + inflate;

            float lowerBound = axis == 1 ? -14 : -22;
            float upperBound = axis == 1 ? 30 : 22;

            if (min < lowerBound || max > upperBound) {
                return false;
            }
        }

        return true;
    }

    /**
     * Writes the pack's {@code blocks.json}, which is where Bedrock reads a block's step, dig and
     * place sounds from. Without it modded blocks are silent to walk on and break, and the client
     * logs "No sound found for block type 'normal'".
     */
    private void writeBlockSounds(@NotNull PackPostProcessContext<BlockPackModule> context, @NotNull BedrockResourcePack bedrockPack) {
        List<Block> blocks = context.registryValues(BuiltInRegistries.BLOCK);
        if (blocks.isEmpty()) {
            return;
        }

        JsonObject blocksJson = new JsonObject();

        JsonArray formatVersion = new JsonArray();
        formatVersion.add(1);
        formatVersion.add(1);
        formatVersion.add(0);
        blocksJson.add("format_version", formatVersion);

        int written = 0;
        for (Block block : blocks) {
            String sound = BlockSounds.bedrockSound(block.defaultBlockState().getSoundType());
            if (sound == null) {
                continue;
            }


            JsonObject entry = new JsonObject();
            entry.addProperty("sound", sound);
            blocksJson.add(BuiltInRegistries.BLOCK.getKey(block).toString(), entry);
            written++;
        }

        if (written == 0) {
            return;
        }

        bedrockPack.addExtraFile(blocksJson, "blocks.json");
        context.logger().info("Wrote sounds for {} blocks in mod {}", written, context.mod().id());
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<BlockPackModule> context) {
        return !context.registryValues(BuiltInRegistries.BLOCK).isEmpty();
    }

    private void onDefineCustomBlocks(PackEventContext<GeyserDefineCustomBlocksEvent, BlockPackModule> context) {
        GeyserDefineCustomBlocksEvent event = context.event();
        List<Block> blocks = context.registryValues(BuiltInRegistries.BLOCK);

        Set<Identifier> warnedEmptyModels = new HashSet<>();

        // Both of these run once per block state, and a block with several properties has hundreds,
        // so without this a single missing material fills the log by itself
        Set<Key> warnedMaterials = new HashSet<>();
        DefaultedRegistry<Block> registry = BuiltInRegistries.BLOCK;
        for (Block block : blocks) {
            Identifier blockLocation = registry.getKey(block);
            CustomBlockData.Builder builder = NonVanillaCustomBlockData.builder()
                    .name(blockLocation.getPath())
                    .namespace(blockLocation.getNamespace())
                    .includedInCreativeInventory(true);

            CreativeMappings.setupBlock(block, builder);

            // Bedrock takes at most sixteen values per property, and a block that breaks that rule is
            // one the client cannot parse. It does not skip just that block: every block registered
            // after it shifts position in the palette, so the world is drawn with the wrong blocks
            // entirely - stone comes out as bookshelf and so on. Leaving the block unregistered costs
            // only that block, which is why it is dropped here rather than passed on and hoped for.
            Property<?> oversized = oversizedProperty(block);
            if (oversized != null) {
                // Dropping the block was worse than the problem. Enderscape's void shale counts its
                // spread to forty-nine and its blinklight vines their age to twenty-six, and neither
                // number is anything you can see - but an unregistered block is absent from the
                // client's registry entirely, so it draws nothing and collides with nothing, which is
                // felt as being shoved about while walking over ground that is not there.
                //
                // The property is left out of what Bedrock is told instead. Every value of it shares
                // one appearance, which for a counter is no loss at all, and the block exists.
                context.logger().info("Registering block {} without its {} property: {} values is more "
                                + "than the {} Bedrock allows, so they share one appearance",
                        blockLocation, oversized.getName(), oversized.getPossibleValues().size(), MAX_PROPERTY_VALUES);
            }

            for (Property<?> property : block.getStateDefinition().getProperties()) {
                if (property == oversized) {
                    continue;
                }

                if (property instanceof IntegerProperty intProperty) {
                    builder.intProperty(property.getName(), List.copyOf(intProperty.getPossibleValues()));
                } else if (property instanceof BooleanProperty) {
                    builder.booleanProperty(property.getName());
                } else if (property instanceof EnumProperty<?> enumProperty) {
                    builder.stringProperty(enumProperty.getName(), enumProperty.getPossibleValues().stream().map(StringRepresentable::getSerializedName).toList());
                } else {
                    throw new IllegalArgumentException("Unknown property type: " + property.getClass().getName());
                }
            }

            List<CustomBlockPermutation> permutations = new ArrayList<>();
            Set<String> conditionsSeen = new HashSet<>();
            CustomBlockComponents.Builder baseComponentBuilder = CustomBlockComponents.builder();
            // Only set when the base builder inherits a state's transformation, i.e. when the block
            // has no properties. Otherwise the base carries no rotation for Bedrock to apply.
            int baseRotation = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelDefinition definition = getModel(context, blockLocation, state);
                if (definition == null) {
                    continue;
                }

                Model model = definition.model();
                Key key = model.key();

                CustomBlockComponents.Builder componentsBuilder = CustomBlockComponents.builder();

                // How brightly this state glows. Set per state rather than per block because whether
                // a block gives light is often what its states are for - a lit furnace, a redstone
                // lamp switched on - and Bedrock reads the same nought-to-fifteen scale Java does.
                // Without it every modded lamp, lantern and glowing ore sat in the dark
                componentsBuilder.lightEmission(state.getLightEmission());

                // Bedrock rotates the geometry and createBoxComponent undoes that rotation on the
                // boxes, so the two have to agree on whether a rotation happens at all. This is
                // cleared when the block falls back to the empty geometry, which must not be rotated
                boolean rotated = true;

                if (!isUnitCube(model.parent())) {
                    String geoName = geometryName(key);

                    if (geoName == null || emptyModels.contains(key.toString())) {
                        // Blocks drawn from code rather than from a model file - a sign, whose board
                        // and text are painted by its block entity - ship a deliberately empty model,
                        // and are invisible here as a result.
                        //
                        // Standing in the block's own shape for the missing model was tried and was
                        // worse than the gap it filled. A shape is what a block collides with, not
                        // what it looks like: a sign collides as a fat post, so the signs came out as
                        // stumps. And an empty model carries no texture to paint one with, so the
                        // stumps arrived in Bedrock's missing-texture checkerboard. Drawing something
                        // wrong in the right place is not an improvement on drawing nothing.
                        //
                        // Doing this properly means a sign-shaped geometry and the plank texture to
                        // go on it, which is a thing to build rather than a fallback to infer.
                        if (warnedEmptyModels.add(blockLocation)) {
                            context.logger().warn("Missing block model for block {}", blockLocation);
                        }

                        geoName = EMPTY_GEOMETRY;
                        rotated = false;
                    }

                    componentsBuilder.geometry(GeometryComponent.builder()
                            .identifier(geoName)
                            .build());

                    // A model that reaches outside the block cannot also be turned: Bedrock measures
                    // a rotated block against the unit cube and throws the whole definition away when
                    // it does not fit, leaving nothing to draw. Facing the wrong way is a far smaller
                    // loss than being invisible, so the rotation is what gives
                    if (this.outsideUnitCube.contains(geoName)) {
                        rotated = false;
                    }

                    VoxelShape shape = state.getShape(new SingletonBlockGetter(state), BlockPos.ZERO);
                    VoxelShape collisionShape = state.getCollisionShape(new SingletonBlockGetter(state), BlockPos.ZERO);

                    int rotation = rotated ? definition.variant().y() : 0;
                    componentsBuilder.selectionBox(createBoxComponent(shape, rotation));
                    componentsBuilder.collisionBox(createBoxComponent(collisionShape, rotation));
                } else {
                    componentsBuilder.geometry(GeometryComponent.builder()
                            .identifier("minecraft:geometry.full_block")
                            .build());
                }

                if (rotated) {
                    applyTransformation(componentsBuilder, definition);
                }

                // TODO: Work this out based on block state/texture? as this isn't perfect
                // https://wiki.bedrock.dev/blocks/block-components.html#render-methods
                // "opaque" tells Bedrock there is nothing to see through, so it draws every pixel -
                // including the ones meant to be invisible, which come out black. A full cube can be
                // taken at its word, but a block with a model of its own very often has cut-out parts
                // in its texture, and alpha_test is what lets those be nothing at all
                String renderMethod;
                if (!state.canOcclude()) {
                    renderMethod = "blend";
                } else {
                    renderMethod = isUnitCube(model.parent()) ? "opaque" : "alpha_test";
                }

                // If the model is a cross block (EG a flower), we need to use alpha_test_single_sided
                if (model.parent() != null && model.parent().value().equals("block/cross")) {
                    renderMethod = "alpha_test_single_sided";
                }

                Materials materials = context.storage().materials();
                Materials.Material material = materials.material(key.toString());
                if (material != null) {
                    // Add a default texture, can be replaced by the below (I think)
                    Map.Entry<String, String> firstEntry = material.textures().entrySet().iterator().next();

                    String name = PackUtil.getTextureName(firstEntry.getValue());

                    componentsBuilder.materialInstance("*", MaterialInstance.builder()
                            .texture(name)
                            .renderMethod(renderMethod)
                            .faceDimming(true)
                            .ambientOcclusion(model.ambientOcclusion())
                            .build());

                    Map<String, String> faceMapping = getFaceMapping(model.parent());
                    if (!faceMapping.isEmpty()) {
                        for (Map.Entry<String, String> face : faceMapping.entrySet()) {
                            if (!material.textures().containsKey(face.getValue())) continue;

                            String textureName = PackUtil.getTextureName(material.textures().get(face.getValue()));

                            componentsBuilder.materialInstance(face.getKey(), MaterialInstance.builder()
                                    .texture(textureName)
                                    .renderMethod(renderMethod)
                                    .faceDimming(true)
                                    .ambientOcclusion(model.ambientOcclusion())
                                    .build());
                        }
                    } else {
                        for (Map.Entry<String, String> entry : material.textures().entrySet()) {
                            String materialKey = entry.getKey();

                            // Bedrock uses "*" for the particle texture
                            if ("particle".equals(materialKey)) {
                                materialKey = "*";
                            }

                            componentsBuilder.materialInstance(materialKey, MaterialInstance.builder()
                                    .texture(PackUtil.getTextureName(entry.getValue()))
                                    .renderMethod(renderMethod)
                                    .faceDimming(true)
                                    .ambientOcclusion(model.ambientOcclusion())
                                    .build());
                        }
                    }
                } else {
                    componentsBuilder.materialInstance("*", MaterialInstance.builder()
                            .texture(PackUtil.getTextureName(key.toString()))
                            .renderMethod(renderMethod)
                            .faceDimming(true)
                            .ambientOcclusion(model.ambientOcclusion())
                            .build());
                    if (warnedMaterials.add(key)) {
                        context.logger().warn("Could not find material for block {}", key);
                    }
                }

                // No properties exist on this state, so there's only one
                // blockstate that can exist. Update the base builder so that
                // the code that creates the component for the base block
                // persists everything we did above
                if (state.getProperties().isEmpty()) {
                    baseComponentBuilder = componentsBuilder;
                    // Missing/vanilla models use the empty geometry and deliberately receive no
                    // transformation. Keep their base collision boxes unrotated as well; otherwise
                    // this later base-component pass would undo a rotation Bedrock never applies.
                    baseRotation = rotated ? definition.variant().y() : 0;
                    continue;
                }

                List<String> conditions = new ArrayList<>();
                for (Property<?> property : state.getProperties()) {
                    // Bedrock was not told about this one, so it cannot be asked about either
                    if (property == oversized) {
                        continue;
                    }

                    String propValue = state.getValue(property).toString();
                    if (property instanceof EnumProperty<?>) {
                        propValue = "'" + propValue.toLowerCase() + "'";
                    }

                    conditions.add(String.format(STATE_CONDITION, property.getName(), propValue));
                }

                String condition = String.join(" && ", conditions);

                // With a property left out, every state that differed only by it now says the same
                // thing, and Bedrock would be handed the same permutation forty-nine times over
                if (!conditionsSeen.add(condition)) {
                    continue;
                }

                permutations.add(new CustomBlockPermutation(componentsBuilder.build(), condition));
            }

            if (isFluid(block)) {
                // Asked of every fluid, not only the ones that got this far with nothing drawn. A
                // fluid's model is not a model: Java draws fluids in code and ships a file carrying
                // nothing but a particle texture, so a fluid that *does* have a blockstate - as
                // Enderscape's void lachryma does - comes through the ordinary path, converts a model
                // with no shapes in it, and arrives as an invisible block. Waiting for the shape to be
                // missing was waiting for the wrong thing.
                //
                // Whatever was built from that empty model is dropped, and the fluid is drawn as a
                // full block of its own still texture. Bedrock has no fluids to give, so a block
                // standing in for one is the whole of what can be done.
                permutations.clear();
                context.logger().info("Drawing fluid {} as a still translucent block", blockLocation);
                applyFluidAppearance(baseComponentBuilder, blockLocation);
            }

            builder.permutations(permutations);

            BlockState defaultState = block.defaultBlockState();
            VoxelShape shape = defaultState.getShape(new SingletonBlockGetter(defaultState), BlockPos.ZERO);
            VoxelShape collisionShape = defaultState.getCollisionShape(new SingletonBlockGetter(defaultState), BlockPos.ZERO);

            CustomBlockComponents.Builder componentsBuilder = baseComponentBuilder
                    .displayName("%" + block.getDescriptionId())
                    .friction(Math.min(1 - block.getFriction(), 0.9f))
                    .lightEmission(defaultState.getLightEmission())
                    .destructibleByMining(secondsToDestroy(defaultState))
                    // .unitCube(true) // TODO: Geometry conversion
                    .selectionBox(createBoxComponent(shape, baseRotation))
                    .collisionBox(createBoxComponent(collisionShape, baseRotation))
                    .tags(toolTags(defaultState));

            builder.components(componentsBuilder.build());

            CustomBlockData blockData = builder.build();
            try {
                event.register(blockData);
            } catch (IllegalArgumentException e) {
                context.logger().error("Failed to register block {}: {}", blockLocation, e.getMessage());
                continue;
            }

            int blockId = registry.getId(block);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                CustomBlockState.Builder stateBuilder = blockData.blockStateBuilder();
                for (Property<?> property : state.getProperties()) {
                    if (property instanceof IntegerProperty intProperty) {
                        stateBuilder.intProperty(property.getName(), state.getValue(intProperty));
                    } else if (property instanceof BooleanProperty booleanProperty) {
                        stateBuilder.booleanProperty(property.getName(), state.getValue(booleanProperty));
                    } else if (property instanceof EnumProperty<?> enumProperty) {
                        stateBuilder.stringProperty(enumProperty.getName(), state.getValue(enumProperty).getSerializedName());
                    } else {
                        throw new IllegalArgumentException("Unknown property type: " + property.getClass().getName());
                    }
                }

                PistonBehavior pistonBehavior = switch (state.getPistonPushReaction()) {
                    case BLOCK -> PistonBehavior.BLOCK;
                    case DESTROY -> PistonBehavior.DESTROY;
                    case PUSH_ONLY -> PistonBehavior.PUSH_ONLY;
                    default -> PistonBehavior.NORMAL;
                };

                CustomBlockState customBlockState = stateBuilder.build();
                JavaBlockState.Builder javaBlockStateBuilder = JavaBlockState.builder()
                        .identifier(BlockStateParser.serialize(state))
                        .javaId(Block.getId(state))
                        .blockHardness(block.defaultDestroyTime()) // TODO: Check
                        .canBreakWithHand(!state.requiresCorrectToolForDrops())
                        .waterlogged(state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
                        .stateGroupId(blockId)
                        .pistonBehavior(pistonBehavior.name());

                // TODO Work out if we need to prefix with _item so we can remove InventoryUtilsMixin
                try {
                    ItemStack pickItem = state.getCloneItemStack(HydraulicImpl.instance().server().overworld(), BlockPos.ZERO, false);
                    String itemId = BuiltInRegistries.ITEM.getKey(pickItem.getItem()).toString();

                    // If the method is annotated with `@Environment(EnvType.CLIENT)` then we get air back, so lets ignore that
                    if (!itemId.equals("minecraft:air")) {
                        javaBlockStateBuilder.pickItem(itemId);
                    }
                } catch (Exception e) {
                    context.logger().warn("Failed to get pick item for block {}: {}", blockLocation, e.getMessage());
                }

                /*
                List<AABB> aabbs = collisionShape.toAabbs();
                JavaBoundingBox[] bbs = new JavaBoundingBox[aabbs.size()];
                for (int i = 0; i < aabbs.size(); i++) {
                    AABB aabb = aabbs.get(i);
                    bbs[i] = new JavaBoundingBox(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
                }

                javaBlockStateBuilder.collision(bbs);
                 */
                javaBlockStateBuilder.collision(new JavaBoundingBox[0]); // TODO

                event.registerOverride(javaBlockStateBuilder.build(), customBlockState);
            }
        }
    }

    @Nullable
    private ModelDefinition getModel(@NotNull PackContext<?> context, @NotNull Identifier blockLocation, @NotNull BlockState state) {
        StateDefinition definition = this.blockStates.get(blockLocation.toString());
        if (definition == null) {
            context.logger().warn("Missing blockstate for block {}", blockLocation);
            return null;
        }

        team.unnamed.creative.blockstate.BlockState packState = definition.state();

        // Check if we have a variant match
        MultiVariant multiVariant = matchState(state, packState.variants());
        if (multiVariant == null || multiVariant.variants().isEmpty()) {
            // No variant, check if we have a default
            multiVariant = packState.variants().get("");
        }

        // Try and match the state
        // TODO Handle multiple variants since we only take the first match
        //      Will likely need to generate more geometry files and then alter bone visibility for each part
        if (multiVariant == null) {
            for (Selector selector : packState.multipart()) {
                // Ignore none conditions
                if (selector.condition() == Condition.NONE) {
                    continue;
                }

                List<Condition> conditions = new ArrayList<>();
                BiFunction<Boolean, Boolean, Boolean> comparator = (a, b) -> false;
                if (selector.condition() instanceof Condition.And andCondition) {
                    conditions.addAll(andCondition.conditions());
                    comparator = Boolean::logicalAnd;
                } else if (selector.condition() instanceof Condition.Or orCondition) {
                    conditions.addAll(orCondition.conditions());
                    comparator = Boolean::logicalOr;
                } else if (selector.condition() instanceof Condition.Match) {
                    conditions.add(selector.condition());
                }

                boolean first = true;
                boolean result = true;
                for (Condition condition : conditions) {
                    if (!(condition instanceof Condition.Match match)) {
                        context.logger().warn("Non match condition found in {}", blockLocation);
                        continue;
                    }

                    Property<?> foundProperty = null;
                    for (Property<?> property : state.getProperties()) {
                        if (property.getName().equals(match.key())) {
                            foundProperty = property;
                            break;
                        }
                    }

                    if (foundProperty == null) {
                        result = false;
                        continue;
                    }

                    boolean test = state.getValue(foundProperty).toString().equals(match.value().toString());
                    if (!first) {
                        result = comparator.apply(result, test);
                    } else {
                        result = test;
                        first = false;
                    }
                }

                if (result) {
                    multiVariant = selector.variant();
                    break;
                }
            }
        }

        // Get the default multipart variant if we have no match
        if (multiVariant == null) {
            Optional<Selector> selector = packState.multipart().stream().filter(multipart -> multipart.condition() == Condition.NONE).findFirst();
            if (selector.isPresent()) {
                multiVariant = selector.get().variant();
            }

            // LOGGER.warn("Missing multipart state conversion for block {} {}", blockLocation, state);
        }

        // We have a match! Now we need to find the model
        if (multiVariant != null && !multiVariant.variants().isEmpty()) {
            // TODO: Handle multiple variants?
            Variant variant = multiVariant.variants().get(0);
            Key modelKey = variant.model();

            Model model = definition.modelProvider().model(modelKey);
            if (model == null) {
                context.logger().warn("Missing model {} for block {}", modelKey, blockLocation);
            } else {
                return new ModelDefinition(model, variant);
            }
        }

        return null;
    }

    private static MultiVariant matchState(@NotNull BlockState state, @NotNull Map<String, MultiVariant> variants) {
        List<String> properties = new ArrayList<>();
        for (Property<?> property : state.getProperties()) {
            properties.add(property.getName() + "=" + state.getValue(property).toString().toLowerCase());
        }

        for (Map.Entry<String, MultiVariant> entry : variants.entrySet()) {
            String variant = entry.getKey();

            String[] property = variant.split(",");
            boolean match = true;
            for (String prop : property) {
                if (!properties.contains(prop)) {
                    match = false;
                    break;
                }
            }

            if (match) {
                return entry.getValue();
            }
        }

        return null;
    }

    @Nullable
    private static ModelTexture getModelTexture(@NotNull Map<String, ModelTexture> textures, @NotNull String key) {
        return getModelTexture(textures, key, new HashSet<>());
    }

    @Nullable
    private static ModelTexture getModelTexture(@NotNull Map<String, ModelTexture> textures, @NotNull String key, @NotNull Set<String> visited) {
        if (!visited.add(key)) {
            return null;
        }

        // Texture references the value of another texture
        ModelTexture value = textures.get(key);
        if (value != null && value.reference() != null) {
            return getModelTexture(textures, value.reference(), visited);
        }

        return value;
    }

    private static Map<String, ModelTexture> getTextures(@NotNull ModelTextures modelTextures) {
        Map<String, ModelTexture> textures = new HashMap<>(modelTextures.variables());
        textures.put("particle", modelTextures.particle());
        for (int i = 0; i < modelTextures.layers().size(); i++) {
            textures.put("layer" + i, modelTextures.layers().get(i));
        }

        return textures;
    }

    private boolean isUnitCube(Key parent) {
        if (parent == null) {
            return false;
        }
        return parent.namespace().equals("minecraft") && (parent.value().startsWith("block/cube") || parent.value().startsWith("block/orientable"));
    }

    /**
     * Get the face mapping for the given parent model.
     * This is due to some cube models having texture names bedrock doesn't understand.
     *
     * @param parent The parent model
     * @return The face mapping if any
     */
    private Map<String, String> getFaceMapping(Key parent) {
        // Destination <- Source
        Map<String, String> mapping = new HashMap<>();
//        {{
//            put("*", "particle");
//            put("up", "up");
//            put("down", "down");
//            put("north", "north");
//            put("south", "south");
//            put("west", "west");
//            put("east", "east");
//        }};

        // No parent, so return empty
        if (parent == null) {
            return mapping;
        }

        if ("block/cube_all".equals(parent.value())) {
            mapping.put("*", "all");
        } else if ("block/cube_bottom_top".equals(parent.value())) {
            mapping.put("*", "side");
            mapping.put("up", "top");
            mapping.put("down", "bottom");
            mapping.put("north", "side");
            mapping.put("south", "side");
            mapping.put("west", "side");
            mapping.put("east", "side");
        } else if ("block/cube_column".equals(parent.value())) {
            mapping.put("*", "side");
            mapping.put("up", "end");
            mapping.put("down", "end");
            mapping.put("north", "side");
            mapping.put("south", "side");
            mapping.put("west", "side");
            mapping.put("east", "side");
        }

        return mapping;
    }

    /**
     * Gives the block the Bedrock tags that say which tool mines it.
     * <p>
     * Bedrock decides how fast a tool mines a block from the tool's {@code destroy_speeds}, whose
     * rules match blocks by tag. Vanilla blocks carry tags such as
     * {@code minecraft:is_pickaxe_item_destructible}; custom blocks carry none unless we add them,
     * so no tool rule could ever match one. Copying across Java's {@code mineable/*} tags lets the
     * same rules apply - see {@link org.geysermc.hydraulic.mixin.ext.CustomItemRegistryPopulatorMixin},
     * which writes the matching rules onto the tools.
     *
     * @param state the block state to read Java's mineable tags from
     * @return the Bedrock tags this block should carry
     */
    @NotNull
    private static Set<String> toolTags(@NotNull BlockState state) {
        Set<String> tags = new HashSet<>();

        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            tags.add("minecraft:is_pickaxe_item_destructible");
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            tags.add("minecraft:is_axe_item_destructible");
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            tags.add("minecraft:is_shovel_item_destructible");
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            tags.add("minecraft:is_hoe_item_destructible");
        }

        return tags;
    }

    /**
     * Converts a block's Java hardness into the value Bedrock's {@code destructible_by_mining}
     * component expects.
     * <p>
     * Java's hardness is not a duration: the time to break a block by hand is
     * {@code hardness * 1.5} seconds when the block drops without a specific tool, and
     * {@code hardness * 5} seconds when it requires one (both derived from Java's
     * {@code damage = speed / hardness / (canHarvest ? 30 : 100)} per tick, at 20 ticks a second and
     * a bare-hand speed of 1). Bedrock's component is the bare-handed break time, which tools then
     * speed up via the {@code destroy_speeds} Geyser derives from the item's tool component - so
     * passing the raw hardness made every modded block break at the wrong speed.
     *
     * @param state the block state to calculate for
     * @return the number of seconds it takes to break this block by hand
     */
    private static float secondsToDestroy(@NotNull BlockState state) {
        float hardness = state.getBlock().defaultDestroyTime();

        // Java marks unbreakable blocks with a negative hardness. Bedrock has no direct equivalent
        // here, and a negative value would make the block break instantly, so use a duration long
        // enough to be unbreakable in practice.
        if (hardness < 0) {
            return 3600;
        }

        return hardness * (state.requiresCorrectToolForDrops() ? 5f : 1.5f);
    }

    /**
     * Gives a modded fluid something to look like on Bedrock.
     * <p>
     * A fluid's appearance lives in the mod's rendering code rather than in any file that can be read
     * here, so this is a stand-in rather than a conversion: a full translucent cube wearing the
     * texture mods conventionally name {@code <fluid>_still}. A mod that names its texture otherwise
     * gets an untextured block, which is still better than the hole left by skipping it.
     * <p>
     * <b>It looks like a liquid but does not behave as one.</b> Bedrock has no idea this block is a
     * fluid, so it applies no swimming, no drag and no fog, and its own movement prediction will
     * disagree with the server - which does know - leaving a Bedrock player swimming through it
     * jittery as the server corrects them. The block is left without collision so they at least sink
     * into it rather than standing on top. Making this behave properly needs Bedrock to be told the
     * block is a liquid, which the custom block API cannot express.
     *
     * @param builder the components being built for the fluid
     * @param fluid the fluid block's identifier
     */
    /**
     * Whether this block is a body of fluid rather than something you can stand on.
     * <p>
     * Asked of the block's own state rather than its class. {@link LiquidBlock} is what vanilla uses
     * and what most mods extend, but a mod is free to write its own and several do - and one that
     * does was reaching none of this, so its fluid had no model, no fallback, and drew nothing at
     * all. A block that reports a fluid state is a fluid whatever its class says.
     * <p>
     * Waterlogged blocks report a fluid state too, but they are not caught here: they have models of
     * their own, and the caller only asks once nothing has been drawn.
     */
    private static boolean isFluid(@NotNull Block block) {
        return block instanceof LiquidBlock || !block.defaultBlockState().getFluidState().isEmpty();
    }

    private static void applyFluidAppearance(@NotNull CustomBlockComponents.Builder builder, @NotNull Identifier fluid) {
        builder.geometry(GeometryComponent.builder()
                .identifier("minecraft:geometry.full_block")
                .build());

        builder.materialInstance("*", MaterialInstance.builder()
                .texture(PackUtil.getTextureName(fluid.getNamespace() + ":block/" + fluid.getPath() + "_still"))
                .renderMethod("blend")
                .faceDimming(false)
                .ambientOcclusion(false)
                .build());
    }

    /**
     * The name of the Bedrock geometry generated for a Java model, or {@code null} if there is none.
     * <p>
     * Only the mod's own models are converted, so a block whose model comes from vanilla - a modded
     * campfire reusing {@code minecraft:block/campfire_off}, say - has no geometry to point at. Named
     * one anyway it would reference a missing asset, and Bedrock answers that by discarding the whole
     * block definition, so callers fall back to {@link #EMPTY_GEOMETRY} instead.
     *
     * @param key the key of the block's Java model
     * @return the geometry name, or null if the model is not one we convert
     */
    private static String geometryName(@NotNull Key key) {
        if (key.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
            return null;
        }

        String value = key.value();
        return "geometry." + key.namespace() + "." + value.substring(value.lastIndexOf('/') + 1);
    }

    /**
     * Gives Bedrock the rotation of the model variant, if there is one to give.
     * <p>
     * The component is left off entirely when nothing rotates. It would be a no-op, but it is not
     * free: a block carrying a transformation has its geometry measured against Bedrock's 1 + 14/16
     * block limit, and failing that check costs the entire block definition.
     */
    private static void applyTransformation(@NotNull CustomBlockComponents.Builder builder, @NotNull ModelDefinition definition) {
        int rotationX = (360 - definition.variant().x()) % 360;
        int rotationY = (360 - definition.variant().y()) % 360;
        if (rotationX == 0 && rotationY == 0) {
            return;
        }

        builder.transformation(new TransformationComponent(
                rotationX, rotationY, 0, // Rotation
                1, 1, 1, // Scale
                0, 0, 0 // Translation
        ));
    }

    /**
     * Builds the Bedrock box for the given shape, undoing the rotation Bedrock will apply to it.
     * <p>
     * Bedrock's {@code minecraft:transformation} rotates a block's collision and selection boxes
     * along with its geometry, but the shapes Java hands us are already in world orientation. Left
     * alone they end up rotated twice - most visibly on doors, where an open door's thin collision
     * slab lands across the doorway instead of beside it, so Bedrock players can't walk through
     * (see GeyserMC/Hydraulic#70). Rotating the box back by the same amount first means Bedrock's
     * rotation puts it where it belongs.
     *
     * @param shape the shape to convert
     * @param variantRotationY the Y rotation of the model variant this shape belongs to
     * @return the box component to hand to Bedrock
     */
    private static BoxComponent createBoxComponent(VoxelShape shape, int variantRotationY) {
        if (shape.isEmpty()) {
            return BoxComponent.emptyBox();
        }

        float minX = 5;
        float minY = 5;
        float minZ = 5;
        float maxX = -5;
        float maxY = -5;
        float maxZ = -5;
        for (AABB boundingBox : shape.toAabbs()) {
            double offsetX = boundingBox.getXsize() * 0.5;
            double offsetY = boundingBox.getYsize() * 0.5;
            double offsetZ = boundingBox.getZsize() * 0.5;

            Vec3 center = boundingBox.getCenter();

            minX = Math.min(minX, (float) (center.x() - offsetX));
            minY = Math.min(minY, (float) (center.y() - offsetY));
            minZ = Math.min(minZ, (float) (center.z() - offsetZ));

            maxX = Math.max(maxX, (float) (center.x() + offsetX));
            maxY = Math.max(maxY, (float) (center.y() + offsetY));
            maxZ = Math.max(maxZ, (float) (center.z() + offsetZ));
        }

        if (Math.floorMod(variantRotationY, 360) != 0) {
            // Bedrock is given (360 - variant) as its rotation, which turns the block the opposite
            // way to Java, so undoing it here means rotating by -variant in Java's own direction.
            double angle = Math.toRadians(-variantRotationY);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            float rotatedMinX = 5;
            float rotatedMinZ = 5;
            float rotatedMaxX = -5;
            float rotatedMaxZ = -5;
            for (float x : new float[] { minX, maxX }) {
                for (float z : new float[] { minZ, maxZ }) {
                    // Rotate the corner around the block's vertical centre
                    double offsetX = x - 0.5;
                    double offsetZ = z - 0.5;

                    float rotatedX = (float) (0.5 + offsetX * cos - offsetZ * sin);
                    float rotatedZ = (float) (0.5 + offsetX * sin + offsetZ * cos);

                    rotatedMinX = Math.min(rotatedMinX, rotatedX);
                    rotatedMaxX = Math.max(rotatedMaxX, rotatedX);
                    rotatedMinZ = Math.min(rotatedMinZ, rotatedZ);
                    rotatedMaxZ = Math.max(rotatedMaxZ, rotatedZ);
                }
            }

            minX = rotatedMinX;
            maxX = rotatedMaxX;
            minZ = rotatedMinZ;
            maxZ = rotatedMaxZ;
        }

        minX = MathUtils.clamp(minX, 0, 1);
        minY = MathUtils.clamp(minY, 0, 1);
        minZ = MathUtils.clamp(minZ, 0, 1);
        maxX = MathUtils.clamp(maxX, 0, 1);
        maxY = MathUtils.clamp(maxY, 0, 1);
        maxZ = MathUtils.clamp(maxZ, 0, 1);

        return new BoxComponent(
                16 * (1 - maxX) - 8, // For some odd reason X is mirrored on Bedrock
                16 * minY,
                16 * minZ - 8,
                16 * (maxX - minX),
                16 * (maxY - minY),
                16 * (maxZ - minZ)
        );
    }
}
