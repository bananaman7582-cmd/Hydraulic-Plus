package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;
import team.unnamed.creative.metadata.animation.AnimationFrame;
import team.unnamed.creative.metadata.animation.AnimationMeta;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnimatedTextureConverterTest {
    @Test
    void convertsGridToVerticalStripAndKeepsExplicitOrderAndTiming() throws Exception {
        BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        paint(source, 0, Color.RED);
        paint(source, 2, Color.BLUE);

        AnimationMeta metadata = AnimationMeta.of(false, 2, 2, 1, List.of(
                AnimationFrame.of(1, 4), AnimationFrame.of(0, 2)));
        AnimatedTextureConverter.Result result = AnimatedTextureConverter.convert(png(source), metadata);

        assertEquals(2, result.ticksPerFrame());
        assertEquals(List.of(1, 1, 0), result.frames());
        assertEquals(2, result.frameWidth());
        assertEquals(2, result.frameHeight());

        BufferedImage strip = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertEquals(2, strip.getWidth());
        assertEquals(4, strip.getHeight());
        assertEquals(Color.RED.getRGB(), strip.getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), strip.getRGB(0, 2));
    }

    @Test
    void usesAllFramesAndDefaultDurationWhenFramesAreOmitted() throws Exception {
        BufferedImage source = new BufferedImage(2, 4, BufferedImage.TYPE_INT_ARGB);
        AnimationMeta metadata = AnimationMeta.of(true, -1, -1, 3, List.of());

        AnimatedTextureConverter.Result result = AnimatedTextureConverter.convert(png(source), metadata);

        assertEquals(3, result.ticksPerFrame());
        assertEquals(List.of(0, 1), result.frames());
    }

    @Test
    void rejectsOutOfRangeJavaFrameReferences() throws Exception {
        BufferedImage source = new BufferedImage(2, 4, BufferedImage.TYPE_INT_ARGB);
        AnimationMeta metadata = AnimationMeta.of(false, -1, -1, 1,
                List.of(AnimationFrame.of(2)));

        assertThrows(Exception.class, () -> AnimatedTextureConverter.convert(png(source), metadata));
    }

    private static void paint(BufferedImage image, int x, Color color) {
        for (int pixelX = x; pixelX < x + 2; pixelX++) {
            for (int pixelY = 0; pixelY < 2; pixelY++) {
                image.setRGB(pixelX, pixelY, color.getRGB());
            }
        }
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
