package org.geysermc.hydraulic.util;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Description;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;

import java.util.ArrayList;
import java.util.List;

public class GeoUtil {
    private static final String FORMAT_VERSION = "1.16.0";
    private static final float[] ELEMENT_OFFSET = new float[] { 8, 0, 8 };
    private static final int SCALE = 16;

    /**
     * Bedrock rejects the geometry of a block that carries a transformation component when it
     * measures more than {@code 1 + 14/16} blocks on an axis, logging
     * "Total length of parts for schematic ... is greater than 1 + 14/16ths" and refusing to render
     * it. Anything larger here breaks every rotated block using the geometry - which is why wall and
     * hanging signs (empty models plus a facing/rotation) failed.
     */
    private static final float MAX_VISIBLE_BOUNDS = 1.875f;

    /**
     * Create a model entity from a voxel shape
     *
     * @param shape the voxel shape
     * @param geoName the name of the geometry
     * @return the created model entity
     */
    public static ModelEntity fromShape(VoxelShape shape, String geoName) {
        ModelEntity modelEntity = new ModelEntity();
        modelEntity.formatVersion(FORMAT_VERSION);

        Geometry geometry = new Geometry();

        Description description = new Description();
        description.identifier(geoName);
        description.textureWidth(16);
        description.textureHeight(16);
        description.visibleBoundsWidth(MAX_VISIBLE_BOUNDS);
        description.visibleBoundsHeight(MAX_VISIBLE_BOUNDS);
        description.visibleBoundsOffset(new float[] { 0.0f, 0.25f, 0.0f });
        geometry.description(description);

        List<Bones> bones = new ArrayList<>();

        for (AABB box : shape.toAabbs()) {
            float[] from = new float[] { (float) box.minX * SCALE, (float) box.minY * SCALE, (float) box.minZ * SCALE };
            float[] to = new float[] { (float) box.maxX * SCALE, (float) box.maxY * SCALE, (float) box.maxZ * SCALE };

            Bones bone = new Bones();
            bone.name("bone_" + bones.size());
            bone.pivot(new float[] { ELEMENT_OFFSET[0], ELEMENT_OFFSET[1], -ELEMENT_OFFSET[2] });

            Cubes cube = new Cubes();
            cube.origin(new float[] { ELEMENT_OFFSET[0] - to[0], from[1], from[2] - ELEMENT_OFFSET[2] });
            cube.size(new float[] { to[0] - from[0], to[1] - from[1], to[2] - from[2] });

            bone.cubes(List.of(cube));
            bones.add(bone);
        }

        geometry.bones(bones);

        modelEntity.geometry(List.of(geometry));

        return modelEntity;
    }

    /**
     * Create an empty model entity
     *
     * @param geoName the name of the geometry
     * @return the created model entity
     */
    public static ModelEntity empty(String geoName) {
        ModelEntity modelEntity = new ModelEntity();
        modelEntity.formatVersion(FORMAT_VERSION);

        Geometry geometry = new Geometry();

        Description description = new Description();
        description.identifier(geoName);
        description.textureWidth(16);
        description.textureHeight(16);
        // A model that draws nothing needs no room to draw it in. Keeping this down to a single block
        // means the geometry passes Bedrock's bounds check even if it is given a transformation,
        // which at the full 1 + 14/16 it did not - and a geometry that fails that check takes its
        // whole block definition down with it
        description.visibleBoundsWidth(1.0f);
        description.visibleBoundsHeight(1.0f);
        description.visibleBoundsOffset(new float[] { 0.0f, 0.0f, 0.0f });
        geometry.description(description);

        // Without this the geometry is written with no "bones" key at all, which Bedrock treats as
        // malformed rather than as an empty model
        geometry.bones(List.of());

        modelEntity.geometry(List.of(geometry));

        return modelEntity;
    }
}
