package org.geysermc.hydraulic.entity.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.geysermc.hydraulic.mixin.client.CubeDefinitionAccessor;
import org.geysermc.hydraulic.mixin.client.CubeDeformationAccessor;
import org.geysermc.hydraulic.mixin.client.LayerDefinitionAccessor;
import org.geysermc.hydraulic.mixin.client.MaterialDefinitionAccessor;
import org.geysermc.hydraulic.mixin.client.PartDefinitionAccessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns a Java entity model into Bedrock geometry.
 * <p>
 * The two formats describe the same thing - a tree of named parts, each holding boxes with a size
 * and a corner of the texture - so this is a change of coordinates rather than a redrawing. What
 * makes it possible at all is that Java's model is still in its <i>described</i> form here, before
 * being baked into triangles: a {@link CubeDefinition} still knows it is a box 8 wide starting at a
 * given corner, which is exactly what Bedrock wants to be told.
 * <p>
 * <b>This only runs on the client.</b> Entity models live in client-only code and a dedicated server
 * does not have those classes at all, which is why the conversion happens here and the result is
 * written out for the server to pick up rather than being built where it is used.
 * <p>
 * Two differences have to be undone along the way:
 * <ul>
 *   <li>Java points Y downwards and measures from the top of the model, Bedrock points Y up and
 *       measures from the entity's feet, 24 pixels below. X and Z already agree - a Java model's
 *       right arm sits at the same place Bedrock puts its own, so touching X mirrors the entity.</li>
 *   <li>Java positions a part relative to its parent; Bedrock wants every pivot in model space, so
 *       the offsets are accumulated on the way down the tree. Rotations stay local, because Bedrock
 *       already inherits those through the parent chain.</li>
 * </ul>
 * The output was checked against Bedrock's own humanoid: a converted zombie variant lands on the
 * same numbers vanilla uses, limb for limb.
 */
public final class EntityGeometryConverter {
    private static final String FORMAT_VERSION = "1.12.0";

    /**
     * How far Bedrock's origin sits below Java's, in pixels. Java builds an entity downwards from its
     * head, Bedrock upwards from its feet, and a player is 24 pixels tall in model space.
     */
    private static final float Y_OFFSET = 24.0f;

    /**
     * Java's names for the humanoid bones, and the ones Bedrock's animations look for.
     * <p>
     * Bedrock's humanoid animations move bones by name - {@code animation.humanoid.move} turns
     * {@code leftarm} and {@code rightleg} - and it matches those names without regard to case but
     * very much with regard to punctuation. A Java model calls the same bone {@code left_arm}, so
     * left alone the animation finds nothing to move and the mob walks without moving a limb. The
     * head is the giveaway: both spell it the same way, so head tracking worked while nothing else
     * did.
     * <p>
     * Only the humanoid skeleton is listed. A bone a mod invented keeps its own name, since no
     * vanilla animation is looking for it anyway.
     */
    private static final Map<String, String> BEDROCK_BONE_NAMES = Map.of(
            "left_arm", "leftArm",
            "right_arm", "rightArm",
            "left_leg", "leftLeg",
            "right_leg", "rightLeg",
            "left_sleeve", "leftSleeve",
            "right_sleeve", "rightSleeve",
            "left_pants", "leftPants",
            "right_pants", "rightPants",
            "jacket", "jacket"
    );

    private EntityGeometryConverter() {
    }

    /**
     * The name Bedrock knows a bone by, which is the Java one unless it is part of the humanoid
     * skeleton.
     */
    @NotNull
    static String boneName(@NotNull String javaName) {
        return BEDROCK_BONE_NAMES.getOrDefault(javaName, javaName);
    }

    /**
     * Converts one model layer into a Bedrock geometry file.
     *
     * @param identifier the name to give the geometry, without the {@code geometry.} prefix
     * @param layer the Java model layer to convert
     * @return the geometry, ready to be written into a pack
     */
    @NotNull
    public static JsonObject convert(@NotNull String identifier, @NotNull LayerDefinition layer) {
        LayerDefinitionAccessor layerAccess = (LayerDefinitionAccessor) layer;
        MaterialDefinitionAccessor material = (MaterialDefinitionAccessor) (Object) layerAccess.hydraulic$material();

        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry." + identifier);
        description.addProperty("texture_width", material.hydraulic$textureWidth());
        description.addProperty("texture_height", material.hydraulic$textureHeight());
        JsonArray bones = new JsonArray();
        // The root part is a holder for the real parts rather than something drawn, so its children
        // become the top-level bones and it contributes only its offset
        PartDefinition root = layerAccess.hydraulic$mesh().getRoot();
        appendChildren(bones, root, null, 0, 0, 0);

        // Measured only once the bones exist, since it is the bones being measured
        VisibleBounds.describe(description, bones);

        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", bones);

        JsonArray geometries = new JsonArray();
        geometries.add(geometry);

        JsonObject file = new JsonObject();
        file.addProperty("format_version", FORMAT_VERSION);
        file.add("minecraft:geometry", geometries);
        return file;
    }

    /**
     * Walks the part tree, carrying each part's position down to its children so pivots come out in
     * model space rather than relative to whatever they hang off.
     */
    private static void appendChildren(@NotNull JsonArray bones, @NotNull PartDefinition part, @Nullable String parentName,
                                       float parentX, float parentY, float parentZ) {
        for (Map.Entry<String, PartDefinition> entry : part.getChildren()) {
            PartDefinition child = entry.getValue();
            PartPose pose = ((PartDefinitionAccessor) child).hydraulic$partPose();

            float x = parentX + pose.x();
            float y = parentY + pose.y();
            float z = parentZ + pose.z();

            String name = boneName(entry.getKey());
            bones.add(bone(name, parentName, child, pose, x, y, z));
            appendChildren(bones, child, name, x, y, z);
        }
    }

    @NotNull
    private static JsonObject bone(@NotNull String name, @Nullable String parent, @NotNull PartDefinition part,
                                   @NotNull PartPose pose, float x, float y, float z) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parent != null) {
            bone.addProperty("parent", parent);
        }

        bone.add("pivot", numbers(x, Y_OFFSET - y, z));

        // Java stores these in radians. Turning the Y axis around reverses which way a turn goes
        // about the two axes across it, while a turn about Y itself is unaffected
        if (pose.xRot() != 0 || pose.yRot() != 0 || pose.zRot() != 0) {
            bone.add("rotation", numbers(
                    Math.toDegrees(pose.xRot()),
                    Math.toDegrees(pose.yRot()),
                    Math.toDegrees(pose.zRot())
            ));
        }

        // Two boxes in the same place is a model with a second layer over the first, drawn by Java in
        // its own pass. Bedrock draws both at the same depth and they flicker against each other from
        // every angle, so the one underneath is kept and the repeat dropped
        Set<String> placed = new HashSet<>();

        JsonArray cubes = new JsonArray();
        for (CubeDefinition cube : ((PartDefinitionAccessor) part).hydraulic$cubes()) {
            JsonObject built = cube(cube, x, y, z);

            JsonElement origin = built.get("origin");
            JsonElement size = built.get("size");
            if (origin != null && size != null && !placed.add(origin + "|" + size)) {
                continue;
            }

            cubes.add(built);
        }

        if (!cubes.isEmpty()) {
            bone.add("cubes", cubes);
        }

        return bone;
    }

    @NotNull
    private static JsonObject cube(@NotNull CubeDefinition cube, float partX, float partY, float partZ) {
        CubeDefinitionAccessor access = (CubeDefinitionAccessor) (Object) cube;
        Vector3fc origin = access.hydraulic$origin();
        Vector3fc size = access.hydraulic$dimensions();

        float x = partX + origin.x();
        float y = partY + origin.y();
        float z = partZ + origin.z();

        // Both formats give a box by its lowest corner, and X and Z already agree. Only Y needs
        // turning around, and there the corner nearest Java's origin is the far one once flipped,
        // so the box's own height comes off
        JsonObject json = new JsonObject();
        json.add("origin", numbers(x, Y_OFFSET - (y + size.y()), z));
        json.add("size", numbers(size.x(), size.y(), size.z()));
        json.add("uv", numbers(access.hydraulic$texCoord().u(), access.hydraulic$texCoord().v()));

        CubeDeformationAccessor grow = (CubeDeformationAccessor) (Object) access.hydraulic$grow();
        // Bedrock has one inflate value for all three axes, so a box grown unevenly - which is rare -
        // can only be approximated by the largest of the three
        float inflate = Math.max(grow.hydraulic$growX(), Math.max(grow.hydraulic$growY(), grow.hydraulic$growZ()));
        if (inflate != 0) {
            json.addProperty("inflate", inflate);
        }

        if (access.hydraulic$mirror()) {
            json.addProperty("mirror", true);
        }

        return json;
    }

    @NotNull
    private static JsonArray numbers(@NotNull Number... values) {
        JsonArray array = new JsonArray();
        for (Number value : values) {
            array.add(value);
        }
        return array;
    }
}
