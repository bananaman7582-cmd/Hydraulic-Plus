package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CrossbowItem;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.util.ItemModels;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.attachables.Attachable;
import org.geysermc.pack.bedrock.resource.attachables.Attachables;
import org.geysermc.pack.bedrock.resource.attachables.attachable.Description;
import org.geysermc.pack.bedrock.resource.attachables.attachable.description.Scripts;
import org.jetbrains.annotations.NotNull;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.ItemModel;
import team.unnamed.creative.item.RangeDispatchItemModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives custom crossbows the drawing and loaded appearance Bedrock uses for its own.
 * <p>
 * Bedrock renders crossbows through an attachable rather than a flat icon, so without one a modded
 * crossbow shows as a static sprite with no drawing stages and no sign of what it is loaded with.
 * The geometry, animation and render controller names below are the ones from Bedrock's own
 * {@code crossbow.entity.json}, so only the textures need supplying.
 */
@AutoService(PackModule.class)
public class CrossbowPackModule extends TexturePackModule<CrossbowPackModule> {
    private static final Map<String, String> ATTACHABLE_MATERIALS = new HashMap<>() {
        {
            put("default", "entity_alphatest");
            put("enchanted", "entity_alphatest_glint");
        }
    };
    private static final Map<String, String> ATTACHABLE_GEOMETRY = new HashMap<>() {
        {
            put("default", "geometry.crossbow_standby");
            put("crossbow_pulling_0", "geometry.crossbow_pulling_0");
            put("crossbow_pulling_1", "geometry.crossbow_pulling_1");
            put("crossbow_pulling_2", "geometry.crossbow_pulling_2");
            put("crossbow_arrow", "geometry.crossbow_arrow");
            put("crossbow_rocket", "geometry.crossbow_rocket");
        }
    };
    private static final Map<String, String> ATTACHABLE_ANIMATIONS = new HashMap<>() {
        {
            put("wield", "animation.crossbow.wield");
            put("wield_first_person_pull", "animation.crossbow.wield_first_person_pull");
        }
    };
    private static final Scripts ATTACHABLE_SCRIPTS = new Scripts();

    static {
        ATTACHABLE_SCRIPTS.preAnimation(new String[] {
            "variable.charge_amount = math.clamp((query.main_hand_item_max_duration - (query.main_hand_item_use_duration - query.frame_alpha + 1.0)) / 10.0, 0.0, 1.0f);"
        });
        ATTACHABLE_SCRIPTS.animate(List.of(
            Map.of("wield", "1.0"),
            Map.of("wield_first_person_pull", "query.main_hand_item_use_duration > 0.0f && c.is_first_person")
        ));
    }

    public CrossbowPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<CrossbowPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<CrossbowItem> crossbows = context.registryValues(BuiltInRegistries.ITEM).stream()
                .filter(item -> item instanceof CrossbowItem)
                .map(item -> (CrossbowItem) item)
                .toList();

        context.logger().info("Crossbows to convert: {} in mod {}", crossbows.size(), context.mod().id());

        for (CrossbowItem crossbow : crossbows) {
            Identifier crossbowLocation = BuiltInRegistries.ITEM.getKey(crossbow);

            team.unnamed.creative.item.Item definition = ItemModels.definition(assets, crossbowLocation);
            if (definition == null) {
                context.logger().warn("Crossbow {} has no item definition, skipping", crossbowLocation);
                continue;
            }

            Map<String, String> textures = new HashMap<>();
            textures.put("enchanted", "textures/misc/enchanted_item_glint");

            // The idle appearance is the plain item model, which is what the item definition falls
            // back to when the crossbow is neither loaded nor being drawn
            Key idle = ItemModels.firstLayer(assets, unusedModel(definition.model()));
            if (idle == null) {
                context.logger().warn("Crossbow {} has no idle texture, skipping", crossbowLocation);
                continue;
            }
            String plain = texture(context, idle);
            textures.put("default", plain);

            // What it looks like while loaded, picked by the charge type
            putTexture(context, assets, textures, "crossbow_arrow", ItemModels.caseModel(definition.model(), "arrow"), plain);
            putTexture(context, assets, textures, "crossbow_rocket", ItemModels.caseModel(definition.model(), "rocket"), plain);

            // And the stages it steps through while being drawn
            RangeDispatchItemModel pulling = ItemModels.findRangeDispatch(definition.model());
            List<ItemModel> stages = pulling == null ? List.of() : ItemModels.orderedModels(pulling);
            if (pulling == null) {
                context.logger().warn("Crossbow {} has no drawing stages in its item definition", crossbowLocation);
            }

            for (int stage = 0; stage < 3; stage++) {
                putTexture(context, assets, textures, "crossbow_pulling_" + stage,
                        stage < stages.size() ? stages.get(stage) : null, plain);
            }

            Attachables crossbowAttachable = new Attachables();
            crossbowAttachable.formatVersion("1.10.0");

            Description description = new Description();
            description.identifier(crossbowLocation.toString());
            description.materials(ATTACHABLE_MATERIALS);
            description.geometry(ATTACHABLE_GEOMETRY);
            description.animations(ATTACHABLE_ANIMATIONS);
            description.scripts(ATTACHABLE_SCRIPTS);
            description.renderControllers(new String[] { "controller.render.crossbow" });
            description.textures(textures);

            Attachable attachable = new Attachable();
            attachable.description(description);
            crossbowAttachable.attachable(attachable);

            bedrockPack.addAttachable(crossbowAttachable, "attachables/" + crossbowLocation.getPath() + ".json");
        }
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<CrossbowPackModule> context) {
        return context.registryValues(BuiltInRegistries.ITEM).stream().anyMatch(item -> item instanceof CrossbowItem);
    }

    /**
     * Gets the model shown when the crossbow is neither loaded nor being drawn, which sits on the
     * "false" side of the "is the player using this item" condition.
     */
    private static ItemModel unusedModel(ItemModel model) {
        if (model instanceof team.unnamed.creative.item.ConditionItemModel condition) {
            return condition.onFalse();
        } else if (model instanceof team.unnamed.creative.item.SelectItemModel select) {
            return unusedModel(select.fallback());
        }

        return model;
    }

    /**
     * Names a texture for one of the crossbow's states, whether or not the mod drew one.
     * <p>
     * Bedrock does <i>not</i> quietly fall back for a state left unnamed - its render controller
     * names all six, and one it cannot resolve costs the entire controller, leaving the crossbow with
     * no animation at all rather than one missing frame. Repeating the plain texture keeps the
     * controller whole; the only thing lost is that stage looking different.
     */
    private void putTexture(@NotNull PackPostProcessContext<CrossbowPackModule> context, @NotNull ResourcePack assets,
                            @NotNull Map<String, String> textures, @NotNull String name, ItemModel model,
                            @NotNull String fallback) {
        Key layer = ItemModels.firstLayer(assets, model);
        textures.put(name, layer == null ? fallback : texture(context, layer));
    }

    private String texture(@NotNull PackPostProcessContext<CrossbowPackModule> context, @NotNull Key layer) {
        return getOutputFromModel(context, layer).replace(".png", "");
    }
}
