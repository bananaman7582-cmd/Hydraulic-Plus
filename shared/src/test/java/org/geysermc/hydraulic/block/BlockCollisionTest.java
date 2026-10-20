package org.geysermc.hydraulic.block;

import net.minecraft.world.phys.shapes.Shapes;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockCollisionTest {
    @Test
    void emptyBlocksStayEmptyAndFullBlocksHaveUnitDimensions() {
        assertEquals(0, BlockCollision.boxes(Shapes.empty()).length);
        assertArrayEquals(new JavaBoundingBox[] {new JavaBoundingBox(.5, .5, .5, 1, 1, 1)},
                BlockCollision.boxes(Shapes.block()));
    }

    @Test
    void slabUsesCentreAndHeightRatherThanMinMaxCoordinates() {
        var box = BlockCollision.boxes(Shapes.box(0, 0, 0, 1, .5, 1));
        assertArrayEquals(new JavaBoundingBox[] {new JavaBoundingBox(.5, .25, .5, 1, .5, 1)}, box);
    }

    @Test
    void keepsMultiplePartsAndGeometryOutsideTheUnitCube() {
        var shape = Shapes.or(Shapes.box(0, 0, 0, .25, 1.5, .25), Shapes.box(.75, 0, .75, 1, .5, 1));
        var boxes = BlockCollision.boxes(shape);
        assertEquals(2, boxes.length);
        assertTrue(java.util.Arrays.stream(boxes).anyMatch(box -> box.sizeY() == 1.5));
        assertTrue(java.util.Arrays.stream(boxes).anyMatch(box -> box.middleX() == .875));
    }
}
