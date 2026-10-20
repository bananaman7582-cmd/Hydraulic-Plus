package org.geysermc.hydraulic.pack;

import com.google.gson.Gson;
import net.kyori.adventure.key.Key;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import team.unnamed.creative.base.Writable;
import team.unnamed.creative.metadata.animation.AnimationFrame;
import team.unnamed.creative.metadata.animation.AnimationMeta;
import team.unnamed.creative.texture.Texture;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void defaultDimensionsAlsoSupportHorizontalStrips() throws Exception {
        BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        paint(source, 0, Color.RED);
        paint(source, 2, Color.BLUE);
        var result = AnimatedTextureConverter.convert(png(source), AnimationMeta.of(false, -1, -1, 1, List.of()));
        assertEquals(2, result.frameWidth());
        assertEquals(2, result.frameHeight());
        assertEquals(List.of(0, 1), result.frames());
    }

    @Test
    void specifyingOnlyOneDimensionMatchesMinecraftInsteadOfAssumingSquares() throws Exception {
        BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        var widthOnly = AnimatedTextureConverter.convert(png(source), AnimationMeta.of(false, 2, -1, 1, List.of()));
        assertEquals(2, widthOnly.frameWidth());
        assertEquals(4, widthOnly.frameHeight());
        assertEquals(List.of(0, 1), widthOnly.frames());
        var heightOnly = AnimatedTextureConverter.convert(png(source), AnimationMeta.of(false, -1, 2, 1, List.of()));
        assertEquals(4, heightOnly.frameWidth());
        assertEquals(2, heightOnly.frameHeight());
        assertEquals(List.of(0, 1), heightOnly.frames());
    }

    @Test
    void registersAdvancedBedrockFlipbookFieldsAndConvertedPng(@TempDir Path directory) throws Exception {
        BufferedImage source = new BufferedImage(2, 4, BufferedImage.TYPE_INT_ARGB);
        byte[] sourcePng = png(source);
        Texture texture = Texture.of(Key.key("example", "item/clock.png"), Writable.bytes(sourcePng));
        AnimationMeta metadata = AnimationMeta.of(false, -1, -1, 2,
                List.of(AnimationFrame.of(1), AnimationFrame.of(0)));
        BedrockResourcePack pack = new BedrockResourcePack(directory);

        AnimatedTextureConverter.register(pack, texture, metadata,
                "example:clock", "textures/items/example/clock");

        String json = new Gson().toJson(pack.flipbookTextures().get("example:clock"));
        assertTrue(json.contains("\"frames\":[1,0]"));
        assertTrue(json.contains("\"blend_frames\":false"));
        assertTrue(pack.extraFiles().containsKey("textures/items/example/clock.png"));
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
