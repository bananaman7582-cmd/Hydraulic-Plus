package org.geysermc.hydraulic.particle;

import com.google.auto.service.AutoService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a Bedrock particle definition for each particle a mod adds.
 * <p>
 * <b>These definitions are not reachable yet, and this module is groundwork rather than a working
 * feature.</b> A particle travels to the client as an index into the particle registry, and Geyser's
 * copy of that registry only holds the vanilla entries, so a mod's particle cannot be named over the
 * wire at all - the packet fails to read and is dropped. Until that changes,
 * {@code ServerCommonPacketListenerImplMixin} sends Bedrock players the closest vanilla particle
 * instead, so something shows up and the packet's batch survives. What is written here is the other
 * half: the moment a modded particle can be addressed, the effect it names already exists in the
 * pack.
 * <p>
 * Java only describes a particle's textures - its movement, size and lifetime live in the mod's own
 * rendering code, which cannot be read here. The effects below therefore use the particle's first
 * texture with a short life and a gentle upward drift: a generic puff wearing the right picture, not
 * a copy of what Java draws. Java animates a multi-texture particle by cycling separate files, which
 * Bedrock cannot do without packing them into one atlas, so only the first frame is used.
 */
@AutoService(PackModule.class)
public class ParticlePackModule extends TexturePackModule<ParticlePackModule> {
    private static final String FORMAT_VERSION = "1.10.0";

    /**
     * Java keeps particle textures under {@code textures/particle}, but its particle files name them
     * without that folder, so it is added back to find them.
     */
    private static final String TEXTURE_DIRECTORY = "particle/";

    public ParticlePackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<ParticlePackModule> context) {
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        int written = 0;
        for (Identifier particle : moddedParticles(context)) {
            List<String> textures = readTextures(context, particle);
            if (textures.isEmpty()) {
                // Plenty of particles are drawn from an item or block texture rather than their own,
                // and have no particle file at all. Nothing to build an effect from
                continue;
            }

            Key texture = Key.key(particle.getNamespace(), TEXTURE_DIRECTORY + stripNamespace(textures.get(0)));
            bedrockPack.addExtraFile(effect(particle, getOutputFromModel(context, texture).replace(".png", "")),
                    "particles/" + context.mod().id() + "/" + particle.getPath() + ".json");
            written++;
        }

        if (written > 0) {
            context.logger().info("Wrote {} particle definition(s) for mod {}", written, context.mod().id());
        }
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<ParticlePackModule> context) {
        return !moddedParticles(context).isEmpty();
    }

    /**
     * The particles registered by the mod being converted.
     */
    @NotNull
    private static List<Identifier> moddedParticles(@NotNull PackPostProcessContext<ParticlePackModule> context) {
        List<Identifier> particles = new ArrayList<>();
        for (Identifier key : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
            if (key.getNamespace().equals(context.mod().namespace())) {
                particles.add(key);
            }
        }

        return particles;
    }

    /**
     * Reads the texture list out of a Java particle file, which is all such a file contains.
     */
    @NotNull
    private static List<String> readTextures(@NotNull PackPostProcessContext<ParticlePackModule> context, @NotNull Identifier particle) {
        Path file = context.mod().resolveFile("assets/" + particle.getNamespace() + "/particles/" + particle.getPath() + ".json");
        if (file == null) {
            return List.of();
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (!json.has("textures")) {
                return List.of();
            }

            List<String> textures = new ArrayList<>();
            for (var element : json.getAsJsonArray("textures")) {
                textures.add(element.getAsString());
            }

            return textures;
        } catch (Exception e) {
            context.logger().warn("Could not read the particle file for {}: {}", particle, e.getMessage());
            return List.of();
        }
    }

    /**
     * Builds a single short-lived billboard particle around the given texture.
     *
     * @param particle the particle's identifier, reused as the Bedrock effect's name
     * @param texture the texture's location within the Bedrock pack
     * @return the effect, ready to be written into the pack
     */
    @NotNull
    private static JsonObject effect(@NotNull Identifier particle, @NotNull String texture) {
        JsonObject renderParameters = new JsonObject();
        renderParameters.addProperty("material", "particles_alpha");
        renderParameters.addProperty("texture", texture);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", particle.toString());
        description.add("basic_render_parameters", renderParameters);

        JsonObject components = new JsonObject();
        components.add("minecraft:emitter_rate_instant", property("num_particles", 1));
        components.add("minecraft:emitter_lifetime_once", property("active_time", 0.25f));
        components.add("minecraft:emitter_shape_point", offset());
        components.add("minecraft:particle_lifetime_expression", property("max_lifetime", 1.0f));
        components.addProperty("minecraft:particle_initial_speed", 0.0f);
        components.add("minecraft:particle_motion_dynamic", motion());
        components.add("minecraft:particle_appearance_billboard", billboard());

        JsonObject effect = new JsonObject();
        effect.add("description", description);
        effect.add("components", components);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.add("particle_effect", effect);

        return root;
    }

    @NotNull
    private static JsonObject offset() {
        JsonObject shape = new JsonObject();
        shape.add("offset", numbers(0, 0, 0));
        return shape;
    }

    /**
     * A slow rise with enough drag to settle, which reads as smoke, steam or sparks alike.
     */
    @NotNull
    private static JsonObject motion() {
        JsonObject motion = new JsonObject();
        motion.add("linear_acceleration", numbers(0, 0.6f, 0));
        motion.addProperty("linear_drag_coefficient", 1.0f);
        return motion;
    }

    @NotNull
    private static JsonObject billboard() {
        JsonObject uv = new JsonObject();
        uv.addProperty("texture_width", 16);
        uv.addProperty("texture_height", 16);
        uv.add("uv", numbers(0, 0));
        uv.add("uv_size", numbers(16, 16));

        JsonObject billboard = new JsonObject();
        billboard.add("size", numbers(0.1f, 0.1f));
        billboard.addProperty("facing_camera_mode", "lookat_xyz");
        billboard.add("uv", uv);
        return billboard;
    }

    @NotNull
    private static JsonObject property(@NotNull String name, @NotNull Number value) {
        JsonObject object = new JsonObject();
        object.addProperty(name, value);
        return object;
    }

    @NotNull
    private static JsonArray numbers(@NotNull Number... values) {
        JsonArray array = new JsonArray();
        for (Number value : values) {
            array.add(value);
        }
        return array;
    }

    /**
     * Java texture lists name the namespace, which the folder lookup does not want.
     */
    @NotNull
    private static String stripNamespace(@NotNull String texture) {
        int colon = texture.indexOf(':');
        return colon == -1 ? texture : texture.substring(colon + 1);
    }
}
