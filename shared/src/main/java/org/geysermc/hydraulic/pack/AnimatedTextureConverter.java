package org.geysermc.hydraulic.pack;

import com.google.gson.annotations.SerializedName;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.textures.FlipbookTexture;
import org.jetbrains.annotations.NotNull;
import team.unnamed.creative.metadata.animation.AnimationFrame;
import team.unnamed.creative.metadata.animation.AnimationMeta;
import team.unnamed.creative.texture.Texture;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Converts Java {@code .png.mcmeta} animation semantics into a Bedrock flipbook. */
public final class AnimatedTextureConverter {
    private static final int MAX_SEQUENCE_LENGTH = 16_384;

    private AnimatedTextureConverter() {
    }

    /**
     * Adds an animation and replaces the copied Java image with a Bedrock-compatible vertical strip.
     * Java permits grids, arbitrary frame order, and a different duration for every frame. Bedrock's
     * pack schema expects a vertical strip and one duration, so the frame list repeats indices at the
     * greatest-common-divisor time quantum to retain the Java timing exactly in normal packs.
     */
    public static void register(@NotNull BedrockResourcePack pack, @NotNull Texture texture,
                                @NotNull AnimationMeta metadata, @NotNull String atlasTile,
                                @NotNull String outputPath) throws IOException {
        Result result = convert(texture.data().toByteArray(), metadata);
        pack.addExtraFile(result.png(), outputPath + ".png");

        // Initialize the schema map using its public API, then replace the basic entry with the two
        // fields the current schema model does not expose: explicit frame order and frame blending.
        pack.addFlipbookTexture(atlasTile, outputPath, result.ticksPerFrame());
        HydraulicFlipbookTexture flipbook = new HydraulicFlipbookTexture();
        flipbook.atlasTile(atlasTile);
        flipbook.flipbookTexture(outputPath);
        flipbook.ticksPerFrame(result.ticksPerFrame());
        flipbook.frames = result.frames();
        flipbook.blendFrames = metadata.interpolate();
        pack.flipbookTextures().put(atlasTile, flipbook);
    }

    @NotNull
    static Result convert(byte @NotNull [] png, @NotNull AnimationMeta metadata) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
        if (source == null) {
            throw new IOException("Animated texture is not a readable PNG");
        }

        int frameWidth = metadata.width() > 0 ? metadata.width() : source.getWidth();
        int frameHeight = metadata.height() > 0 ? metadata.height() : frameWidth;
        if (frameWidth <= 0 || frameHeight <= 0
                || source.getWidth() % frameWidth != 0 || source.getHeight() % frameHeight != 0) {
            throw new IOException("Animation frame size " + frameWidth + "x" + frameHeight
                    + " does not divide texture size " + source.getWidth() + "x" + source.getHeight());
        }

        int columns = source.getWidth() / frameWidth;
        int rows = source.getHeight() / frameHeight;
        int physicalFrameCount = columns * rows;
        if (physicalFrameCount == 0) {
            throw new IOException("Animated texture contains no frames");
        }

        List<TimedFrame> timeline = timeline(metadata, physicalFrameCount);
        int quantum = timeline.stream().mapToInt(TimedFrame::ticks).reduce(0, AnimatedTextureConverter::gcd);
        quantum = Math.max(1, quantum);

        long expandedLength = 0;
        for (TimedFrame frame : timeline) {
            expandedLength += Math.max(1, frame.ticks() / quantum);
        }
        if (expandedLength > MAX_SEQUENCE_LENGTH) {
            long totalTicks = timeline.stream().mapToLong(TimedFrame::ticks).sum();
            quantum = (int) Math.max(1, (totalTicks + MAX_SEQUENCE_LENGTH - 1) / MAX_SEQUENCE_LENGTH);
        }

        List<Integer> frames = new ArrayList<>((int) Math.min(expandedLength, MAX_SEQUENCE_LENGTH));
        for (TimedFrame frame : timeline) {
            int repetitions = Math.max(1, Math.round((float) frame.ticks() / quantum));
            for (int repeat = 0; repeat < repetitions && frames.size() < MAX_SEQUENCE_LENGTH; repeat++) {
                frames.add(frame.index());
            }
        }

        BufferedImage strip = new BufferedImage(frameWidth, frameHeight * physicalFrameCount,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = strip.createGraphics();
        try {
            for (int index = 0; index < physicalFrameCount; index++) {
                int sourceX = (index % columns) * frameWidth;
                int sourceY = (index / columns) * frameHeight;
                graphics.drawImage(source,
                        0, index * frameHeight, frameWidth, (index + 1) * frameHeight,
                        sourceX, sourceY, sourceX + frameWidth, sourceY + frameHeight, null);
            }
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(strip, "png", output)) {
            throw new IOException("No PNG writer is available");
        }
        return new Result(output.toByteArray(), quantum, List.copyOf(frames), frameWidth, frameHeight);
    }

    @NotNull
    private static List<TimedFrame> timeline(@NotNull AnimationMeta metadata, int frameCount) throws IOException {
        int defaultTicks = Math.max(1, metadata.frameTime());
        List<TimedFrame> timeline = new ArrayList<>();
        if (metadata.frames().isEmpty()) {
            for (int index = 0; index < frameCount; index++) {
                timeline.add(new TimedFrame(index, defaultTicks));
            }
            return timeline;
        }

        for (AnimationFrame frame : metadata.frames()) {
            if (frame.index() < 0 || frame.index() >= frameCount) {
                throw new IOException("Animation references frame " + frame.index()
                        + " but the texture only contains " + frameCount + " frame(s)");
            }
            int ticks = frame.frameTime() == AnimationFrame.DELEGATE_FRAME_TIME
                    ? defaultTicks : Math.max(1, frame.frameTime());
            timeline.add(new TimedFrame(frame.index(), ticks));
        }
        return timeline;
    }

    private static int gcd(int left, int right) {
        left = Math.abs(left);
        right = Math.abs(right);
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    record Result(byte[] png, int ticksPerFrame, List<Integer> frames, int frameWidth, int frameHeight) {
    }

    private record TimedFrame(int index, int ticks) {
    }

    private static final class HydraulicFlipbookTexture extends FlipbookTexture {
        @SerializedName("frames")
        private List<Integer> frames;

        @SerializedName("blend_frames")
        private boolean blendFrames;
    }
}
