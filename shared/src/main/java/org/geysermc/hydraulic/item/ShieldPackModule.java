package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import net.kyori.adventure.key.Key;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.PackLogListener;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.util.ItemModels;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.attachables.Attachable;
import org.geysermc.pack.bedrock.resource.attachables.Attachables;
import org.geysermc.pack.bedrock.resource.attachables.attachable.Description;
import org.geysermc.pack.bedrock.resource.attachables.attachable.description.Scripts;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.converter.pipeline.ConversionContext;
import org.geysermc.pack.converter.type.model.BedrockModel;
import org.geysermc.pack.converter.type.model.ModelConverter;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import org.jetbrains.annotations.NotNull;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives a modded shield the way Bedrock holds and raises its own.
 * <p>
 * Bedrock draws a shield from an attachable, and everything needed - the shield's shape, the
 * animations for carrying it in either hand and for raising it to block - is already in the client.
 * What it does not have is any notion of a shield that is not {@code minecraft:shield}: its own
 * attachable decides whether the player is blocking by asking, in as many words, whether the item in
 * hand is named {@code minecraft:shield}. A modded shield fails that test and so never raises.
 * <p>
 * The answer is to write the mod's own name into those questions, which is why the script below is
 * built per item rather than shared. Everything else is vanilla's, unchanged.
 */
@AutoService(PackModule.class)
public class ShieldPackModule extends TexturePackModule<ShieldPackModule> {
    private static final Map<String, String> ATTACHABLE_MATERIALS = new HashMap<>() {
        {
            put("default", "entity_alphatest");
            put("enchanted", "entity_alphatest_glint");
        }
    };

    private static final Map<String, String> ATTACHABLE_GEOMETRY = Map.of("default", "geometry.shield");
    private static final ModelConverter ITEM_MODEL_CONVERTER = new ModelConverter(true);

    /**
     * Vanilla's shield animations, keyed exactly as {@code controller.animation.shield.wield} looks
     * them up. As with the humanoid mobs, these names are not ours to choose - a missing key leaves
     * the controller with nothing to play.
     */
    private static final Map<String, String> ATTACHABLE_ANIMATIONS = new HashMap<>() {
        {
            put("wield", "controller.animation.shield.wield");
            put("wield_main_hand_first_person", "animation.shield.wield_main_hand_first_person");
            put("wield_off_hand_first_person", "animation.shield.wield_off_hand_first_person");
            put("wield_first_person_block", "animation.shield.wield_first_person_blocking");
            put("wield_main_hand_first_person_block", "animation.shield.wield_main_hand_first_person_blocking");
            put("wield_off_hand_first_person_block", "animation.shield.wield_off_hand_first_person_blocking");
            put("wield_third_person", "animation.shield.wield_third_person");
        }
    };

    /**
     * Where vanilla's animations expect a first-person shield to sit. Copied rather than worked out,
     * since they are the numbers those animations were drawn against.
     */
    private static final String[] PLACEMENT = {
        "variable.main_hand_first_person_pos_x =  5.3;",
        "variable.main_hand_first_person_pos_y = 26.0;",
        "variable.main_hand_first_person_pos_z = 0.4;",
        "variable.main_hand_first_person_rot_x = 91.0;",
        "variable.main_hand_first_person_rot_y = 65.0;",
        "variable.main_hand_first_person_rot_z = -43.0;",
        "variable.off_hand_first_person_pos_x = -13.5;",
        "variable.off_hand_first_person_pos_y = -5.8;",
        "variable.off_hand_first_person_pos_z = 5.1;",
        "variable.off_hand_first_person_with_bow_pos_z = -25.0;",
        "variable.off_hand_first_person_rot_x = 1.0;",
        "variable.off_hand_first_person_rot_y = 176.0;",
        "variable.off_hand_first_person_rot_z = -2.5;"
    };

    public ShieldPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<ShieldPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<Item> shields = shields(context);
        if (shields.isEmpty()) {
            return;
        }

        context.logger().info("Shields to convert: {} in mod {}", shields.size(), context.mod().id());

        for (Item shield : shields) {
            Identifier shieldLocation = BuiltInRegistries.ITEM.getKey(shield);

            team.unnamed.creative.item.Item definition = ItemModels.definition(assets, shieldLocation);
            if (definition == null) {
                context.logger().warn("Shield {} has no item definition, skipping", shieldLocation);
                continue;
            }

            Key modelKey = ItemModels.defaultModelKey(definition.model());
            Model baseModel = modelKey == null ? null : assets.model(modelKey);
            if (baseModel == null) {
                context.logger().warn("Shield {} has no resolvable idle model, skipping", shieldLocation);
                continue;
            }

            PackLogListener logListener = new PackLogListener(context.logger());
            Model model = new ModelStitcher(context.modelProvider(), baseModel, logListener).stitch();
            Key texture = ItemModels.firstTexture(model);
            if (texture == null) {
                context.logger().warn("Shield {} has no texture, skipping", shieldLocation);
                continue;
            }

            Map<String, String> textures = new HashMap<>();
            textures.put("default", getOutputFromModel(context, texture).replace(".png", ""));
            textures.put("enchanted", "textures/misc/enchanted_item_glint");

            Map<String, String> materials = new HashMap<>(ATTACHABLE_MATERIALS);
            List<ModelTexture> layers = model.textures().layers();
            if (layers != null) {
                for (int index = 0; index < layers.size(); index++) {
                    ModelTexture layer = layers.get(index);
                    if (layer.key() != null) {
                        String name = "layer" + index;
                        materials.put(name, "entity_alphatest");
                        textures.put(name, getOutputFromModel(context, layer.key()).replace(".png", ""));
                    }
                }
            }
            for (Map.Entry<String, ModelTexture> entry : model.textures().variables().entrySet()) {
                if (entry.getValue().key() == null || entry.getKey().equals("particle")) {
                    continue;
                }

                // Converted Java box models preserve the name following '#', so expose matching
                // aliases in the attachable as well as the conventional "default" texture.
                materials.put(entry.getKey(), "entity_alphatest");
                textures.put(entry.getKey(), getOutputFromModel(context, entry.getValue().key()).replace(".png", ""));
            }

            Map<String, String> geometry = customGeometry(context, shieldLocation, model, logListener);

            Attachables shieldAttachable = new Attachables();
            shieldAttachable.formatVersion("1.10.0");

            Description description = new Description();
            description.identifier(shieldLocation.toString());
            description.materials(materials);
            description.geometry(geometry);
            description.animations(ATTACHABLE_ANIMATIONS);
            description.scripts(scripts(shieldLocation));
            description.renderControllers(new String[] { "controller.render.item_default" });
            description.textures(textures);

            Attachable attachable = new Attachable();
            attachable.description(description);
            shieldAttachable.attachable(attachable);

            bedrockPack.addAttachable(shieldAttachable, "attachables/" + shieldLocation.getPath() + ".json");
        }
    }

    /**
     * Converts a shield made from Java model cubes into Bedrock geometry. Flat shield sprites and
     * conversion failures retain Bedrock's built-in shield shape, so a strange model cannot prevent
     * an otherwise valid shield from rendering.
     */
    @NotNull
    private static Map<String, String> customGeometry(@NotNull PackPostProcessContext<ShieldPackModule> context,
                                                      @NotNull Identifier shield, @NotNull Model model,
                                                      @NotNull PackLogListener logListener) {
        if (model.elements().isEmpty()) {
            return ATTACHABLE_GEOMETRY;
        }

        try {
            BedrockModel converted = ITEM_MODEL_CONVERTER.convert(
                    model,
                    new ConversionContext(context.mod().id(), logListener)
            );
            if (converted == null || converted.model().geometry() == null || converted.model().geometry().isEmpty()) {
                return ATTACHABLE_GEOMETRY;
            }

            ModelEntity entityModel = converted.model();
            Geometry convertedGeometry = entityModel.geometry().getFirst();
            List<Bones> convertedBones = convertedGeometry.bones();
            if (convertedBones == null || convertedBones.isEmpty()) {
                return ATTACHABLE_GEOMETRY;
            }

            // Vanilla's shield animation controller moves a bone named "shield". The general model
            // converter gives every Java cube its own bone, so parent them to that animated root.
            Bones shieldRoot = new Bones();
            shieldRoot.name("shield");
            shieldRoot.pivot(new float[] { 0, 0, 0 });

            List<Bones> bones = new ArrayList<>(convertedBones.size() + 1);
            bones.add(shieldRoot);
            for (Bones bone : convertedBones) {
                if (bone.parent() == null) {
                    bone.parent("shield");
                }
                bones.add(bone);
            }
            convertedGeometry.bones(bones);

            context.bedrockResourcePack().addEntityModel(entityModel, converted.fileName());
            String identifier = convertedGeometry.description().identifier();
            context.logger().info("Using converted 3D shield geometry {} for {}", identifier, shield);
            return Map.of("default", identifier);
        } catch (Exception e) {
            context.logger().warn("Unable to convert 3D shield model for {}; using the vanilla shield shape", shield, e);
            return ATTACHABLE_GEOMETRY;
        }
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<ShieldPackModule> context) {
        return !shields(context).isEmpty();
    }

    /**
     * Everything in the mod that stops an attack, which is what a shield is as far as the game is
     * concerned - there is no shield class or tag to ask for, only the component that does the work.
     */
    @NotNull
    private static List<Item> shields(@NotNull PackPostProcessContext<ShieldPackModule> context) {
        return context.registryValues(BuiltInRegistries.ITEM).stream()
                .filter(item -> item.components().has(DataComponents.BLOCKS_ATTACKS))
                .toList();
    }

    /**
     * The script for one shield, naming that shield where vanilla names its own.
     */
    @NotNull
    private static Scripts scripts(@NotNull Identifier shield) {
        String name = shield.toString();

        Scripts scripts = new Scripts();
        scripts.initialize(PLACEMENT);
        scripts.preAnimation(new String[] {
            // Vanilla asks after 'minecraft:shield' here. Asked about itself instead, a modded shield
            // raises when its own is the one being blocked with
            "variable.is_blocking_main_hand = query.blocking && !query.is_item_name_any('slot.weapon.offhand', '"
                    + name + "') && query.is_item_name_any('slot.weapon.mainhand', '" + name + "');",
            "variable.is_blocking_off_hand = query.blocking && query.is_item_name_any('slot.weapon.offhand', '"
                    + name + "');",
            "variable.is_using_bow = (query.get_equipped_item_name == 'bow') && (query.main_hand_item_use_duration > 0.0f);"
        });
        scripts.animate(List.of(Map.of("wield", "1.0")));
        return scripts;
    }
}
