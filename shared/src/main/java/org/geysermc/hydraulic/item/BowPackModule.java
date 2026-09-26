package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BowItem;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.util.ItemModels;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.attachables.Attachable;
import org.geysermc.pack.bedrock.resource.attachables.Attachables;
import org.geysermc.pack.bedrock.resource.attachables.attachable.Description;
import org.geysermc.pack.bedrock.resource.attachables.attachable.description.Scripts;
import org.geysermc.pack.bedrock.resource.render_controllers.RenderControllers;
import org.geysermc.pack.bedrock.resource.render_controllers.rendercontrollers.Arrays;
import org.jetbrains.annotations.NotNull;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.ItemModel;
import team.unnamed.creative.item.RangeDispatchItemModel;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(PackModule.class)
public class BowPackModule extends TexturePackModule<BowPackModule> {
    private static final Map<String, String> ATTACHABLE_MATERIALS = new HashMap<>() {
        {
            put("default", "entity_alphatest");
            put("enchanted", "entity_alphatest_glint");
        }
    };
    private static final Map<String, String> ATTACHABLE_GEOMETRY = new HashMap<>() {
        {
            put("default", "geometry.bow_standby");
            put("bow_pulling_0", "geometry.bow_pulling_0");
            put("bow_pulling_1", "geometry.bow_pulling_1");
            put("bow_pulling_2", "geometry.bow_pulling_2");
        }
    };
    private static final Map<String, String> ATTACHABLE_ANIMATIONS = new HashMap<>() {
        {
            put("wield", "animation.bow.wield");
            put("wield_first_person_pull", "animation.bow.wield_first_person_pull");
        }
    };
    private static final Scripts ATTACHABLE_SCRIPTS = new Scripts();

    static {
        ATTACHABLE_SCRIPTS.preAnimation(new String[] {
            "v.charge_amount = math.clamp((q.main_hand_item_max_duration - (q.main_hand_item_use_duration - q.frame_alpha + 1.0)) / 10.0, 0.0, 1.0f);",
            "v.total_frames = 3;",
            "v.step = v.total_frames / 120;",
            "v.frame = query.is_using_item ? math.clamp((v.frame ?? 0) + v.step, 1, v.total_frames) : 0;"
        });
        ATTACHABLE_SCRIPTS.animate(List.of(
            Map.of("wield", "c.is_first_person"),
            Map.of("wield_first_person_pull", "query.main_hand_item_use_duration > 0.0f && c.is_first_person")
        ));
    }

    public BowPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<BowPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<BowItem> bowItems = context.registryValues(BuiltInRegistries.ITEM).stream()
                .filter(item -> item instanceof BowItem)
                .map(item -> (BowItem) item)
                .toList();

        context.logger().info("Bows to convert: " + bowItems.size() + " in mod " + context.mod().id());

        for (BowItem bowItem : bowItems) {
            Identifier bowLocation = BuiltInRegistries.ITEM.getKey(bowItem);
            Map<String, String> textures = new HashMap<>() {
                {
                    put("enchanted", "textures/misc/enchanted_item_glint");
                }
            };

            Model model = assets.model(Key.key(bowLocation.getNamespace(), "item/" + bowLocation.getPath()));
            if (model == null) {
                context.logger().warn("Bow {} has no model, skipping", bowLocation);
                continue;
            }

            List<ModelTexture> layers = model.textures().layers();
            if (layers == null || layers.isEmpty()) {
                context.logger().warn("Bow {} has no layer0 texture, skipping", bowLocation);
                continue;
            }

            ModelTexture layer0 = layers.getFirst();
            String defaultOutputLoc = getOutputFromModel(context, layer0.key()).replace(".png", "");

            textures.put("default", defaultOutputLoc);

            // The drawing stages come from the item definition. They used to live in the model's
            // "overrides" list, but 1.21.4 moved them out, so reading overrides finds nothing at all
            // on modern versions and the bow ends up with no drawing animation.
            team.unnamed.creative.item.Item definition = ItemModels.definition(assets, bowLocation);
            RangeDispatchItemModel pulling = definition == null ? null : ItemModels.findRangeDispatch(definition.model());
            List<ItemModel> stages = pulling == null ? List.of() : ItemModels.orderedModels(pulling);
            if (pulling == null) {
                context.logger().warn("Bow {} has no drawing stages in its item definition", bowLocation);
            }

            // Every stage gets a texture whether or not the mod drew one. The render controller names
            // all three, and Bedrock refuses a controller that reaches for a texture the attachable
            // does not list - so a bow missing its second frame would lose the whole controller, and
            // with it the bow. Falling back to the undrawn texture costs only the drawing animation
            for (int stage = 0; stage < 3; stage++) {
                Key layer = stage < stages.size() ? ItemModels.firstLayer(assets, stages.get(stage)) : null;
                textures.put("bow_pulling_" + stage, layer == null
                        ? defaultOutputLoc
                        : getOutputFromModel(context, layer).replace(".png", ""));
            }

            Attachables armorAttachable = new Attachables();
            armorAttachable.formatVersion("1.10.0");

            Description description = new Description();
            description.identifier(bowLocation.toString());
            description.materials(ATTACHABLE_MATERIALS);
            description.geometry(ATTACHABLE_GEOMETRY);
            description.animations(ATTACHABLE_ANIMATIONS);
            description.scripts(ATTACHABLE_SCRIPTS);
            description.renderControllers(new String[] {"controller.render.bow_custom"});

            description.textures(textures);

            Attachable attachable = new Attachable();
            attachable.description(description);
            armorAttachable.attachable(attachable);

            bedrockPack.addAttachable(armorAttachable, "attachables/" + bowLocation.getPath() + ".json");
        }

        RenderControllers renderController = new RenderControllers();
        renderController.formatVersion("1.10.0");

        org.geysermc.pack.bedrock.resource.render_controllers.rendercontrollers.RenderControllers bowCustomRenderController = new org.geysermc.pack.bedrock.resource.render_controllers.rendercontrollers.RenderControllers();
        bowCustomRenderController.arrays(new Arrays());

        bowCustomRenderController.arrays().textures().put("array.bow_texture_frames", new String[] {
                "texture.default",
                "texture.bow_pulling_0",
                "texture.bow_pulling_1",
                "texture.bow_pulling_2"
        });

        bowCustomRenderController.arrays().geometries().put("array.bow_geo_frames", new String[] {
                "geometry.default",
                "geometry.bow_pulling_0",
                "geometry.bow_pulling_1",
                "geometry.bow_pulling_2"
        });

        bowCustomRenderController.geometry("array.bow_geo_frames[math.floor(v.frame)]");
        bowCustomRenderController.materials().add(Map.of("*", "variable.is_enchanted ? material.enchanted : material.default"));
        bowCustomRenderController.textures(new String[] {
                "array.bow_texture_frames[math.floor(v.frame)]",
                "texture.enchanted"
        });

        renderController.renderControllers().put("controller.render.bow_custom", bowCustomRenderController);
        bedrockPack.addRenderController(renderController, "render_controllers/bow_custom.render_controllers.json");
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<BowPackModule> context) {
        return context.registryValues(BuiltInRegistries.ITEM).stream().anyMatch(item -> item instanceof BowItem);
    }
}
