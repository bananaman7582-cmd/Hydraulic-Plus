package org.geysermc.hydraulic.block;

import net.minecraft.world.phys.shapes.VoxelShape;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBoundingBox;

/** Geyser expects box centres and dimensions, not Minecraft's min/max coordinates. */
final class BlockCollision {
    private BlockCollision() {
    }

    static JavaBoundingBox[] boxes(VoxelShape shape) {
        return shape.toAabbs().stream().map(box -> new JavaBoundingBox(
                (box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2,
                box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ))
                .toArray(JavaBoundingBox[]::new);
    }
}
