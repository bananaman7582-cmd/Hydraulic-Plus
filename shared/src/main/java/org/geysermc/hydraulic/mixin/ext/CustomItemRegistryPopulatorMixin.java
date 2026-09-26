package org.geysermc.hydraulic.mixin.ext;

import com.mojang.logging.LogUtils;
import net.kyori.adventure.key.Key;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Tool;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.geyser.registry.populator.CustomItemRegistryPopulator;
import org.geysermc.geyser.registry.populator.custom.CustomItemContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gives modded tools their real mining speeds on Bedrock.
 * <p>
 * Bedrock decides when mining finishes on the client and only then tells the server, so the client's
 * own numbers are what a player actually feels. Those come from an item's {@code minecraft:digger}
 * component, and Geyser writes a single rule there - {@code block: {name:"", tags:"1"}, speed: 1} -
 * where the Molang {@code 1} matches every block. Every tool therefore mines everything at speed 1,
 * which is why a modded pickaxe takes as long as bare hands no matter what Java thinks.
 * <p>
 * There is no API for this, so the rule is rewritten here from the item's Java tool component. Java
 * expresses its rules against block tags such as {@code minecraft:mineable/pickaxe}, and Bedrock has
 * direct equivalents, so each rule becomes a tag query.
 * {@link org.geysermc.hydraulic.block.BlockPackModule} puts the matching tags on custom blocks so
 * the same rules cover them too.
 * <p>
 * <b>This mixin names Cloudburst NBT types, so it only binds while the Fabric build leaves
 * {@code org.cloudburstmc} unrelocated</b> - see the note in {@code fabric/build.gradle.kts}. A
 * mixin that fails to bind aborts the whole class transform and stops Geyser loading, so verify the
 * descriptors in the built jar rather than assuming.
 */
@Mixin(value = CustomItemRegistryPopulator.class, remap = false)
public class CustomItemRegistryPopulatorMixin {
    @Unique
    private static final Logger hydraulic$LOGGER = LogUtils.getLogger();

    /**
     * Java's mineable tags and the Bedrock tags that mean the same thing.
     */
    @Unique
    private static final Map<String, String> hydraulic$MINEABLE_TAGS = Map.of(
            "minecraft:mineable/pickaxe", "minecraft:is_pickaxe_item_destructible",
            "minecraft:mineable/axe", "minecraft:is_axe_item_destructible",
            "minecraft:mineable/shovel", "minecraft:is_shovel_item_destructible",
            "minecraft:mineable/hoe", "minecraft:is_hoe_item_destructible"
    );

    @Redirect(
            method = "createComponentNbt",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/geysermc/geyser/registry/populator/CustomItemRegistryPopulator;computeToolProperties(Lorg/cloudburstmc/nbt/NbtMapBuilder;Lorg/cloudburstmc/nbt/NbtMapBuilder;)V"
            )
    )
    private static void hydraulic$writeRealToolProperties(NbtMapBuilder itemProperties, NbtMapBuilder componentBuilder,
                                                          Key key, CustomItemContext context) {
        List<NbtMap> speeds = new ArrayList<>();

        try {
            hydraulic$addToolRules(context, speeds);
        } catch (Throwable t) {
            // Falling back to Geyser's behaviour is far better than failing to register the item
            hydraulic$LOGGER.warn("Failed to read tool rules for {}, mining speeds will be wrong", key, t);
            speeds.clear();
        }

        // Geyser's catch-all rule, kept last so it only applies where nothing above matched. Without
        // it a tool would be unable to mine anything its Java rules don't mention.
        speeds.add(hydraulic$speed(NbtMap.builder()
                .putString("name", "")
                .putCompound("states", NbtMap.EMPTY)
                .putString("tags", "1")
                .build(), 1));

        // Only the trailing catch-all means we found nothing to translate, so say so rather than
        // leaving it looking like the rules were applied
        if (speeds.size() > 1) {
            hydraulic$LOGGER.info("Mining speeds for {}: {}", key, speeds);
        }

        componentBuilder.putCompound("minecraft:digger", NbtMap.builder()
                .putList("destroy_speeds", NbtType.COMPOUND, speeds)
                // Geyser leaves this off, which also stops Efficiency from doing anything
                .putBoolean("use_efficiency", true)
                .build());
    }

    /**
     * Gives every Java item with a blocks-attacks component Bedrock's native long-use blocking
     * behavior. Geyser does not currently expose this through its custom-item API, but without it a
     * Bedrock client never enters the blocking state that both Java and the generated attachable
     * expect.
     */
    @Inject(method = "createComponentNbt", at = @At("RETURN"))
    private static void hydraulic$writeBlockingProperties(Key key, CustomItemContext context,
                                                           CallbackInfoReturnable<NbtMapBuilder> cir) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(
                context.definition().bedrockIdentifier().toString()
        ));
        if (item == null || !item.components().has(DataComponents.BLOCKS_ATTACKS)) {
            return;
        }

        NbtMapBuilder root = cir.getReturnValue();
        NbtMap componentsMap = root.get("components") instanceof NbtMap value ? value : NbtMap.EMPTY;
        NbtMapBuilder components = componentsMap.toBuilder();
        NbtMap itemPropertiesMap = components.get("item_properties") instanceof NbtMap value ? value : NbtMap.EMPTY;
        NbtMapBuilder itemProperties = itemPropertiesMap.toBuilder();

        // These are the same fields Geyser emits for a consumable whose animation is BLOCK. Shields
        // have no Java consumable component, so they otherwise miss this Bedrock behavior entirely.
        itemProperties.putInt("use_animation", 3);
        itemProperties.putInt("use_duration", 20_000);
        components.putCompound("item_properties", itemProperties.build());
        components.putCompound("minecraft:use_animation", NbtMap.builder()
                .putString("value", "block")
                .build());
        components.putCompound("minecraft:use_modifiers", NbtMap.builder()
                .putFloat("movement_modifier", 0.2f)
                .putFloat("use_duration", 1000.0f)
                .build());
        root.putCompound("components", components.build());

        hydraulic$LOGGER.info("Enabled Bedrock blocking behavior for {}", key);
    }

    /**
     * Turns the item's Java tool rules into Bedrock destroy speed rules.
     */
    @Unique
    private static void hydraulic$addToolRules(@NotNull CustomItemContext context, @NotNull List<NbtMap> speeds) {
        String identifier = context.definition().bedrockIdentifier().toString();
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(identifier));
        if (item == null) {
            return;
        }

        Tool tool = item.components().get(DataComponents.TOOL);
        if (tool == null) {
            return;
        }

        for (Tool.Rule rule : tool.rules()) {
            if (rule.speed().isEmpty()) {
                continue; // a rule without a speed only decides drops, not how fast it mines
            }

            // Bedrock wants a whole number multiplier
            int speed = Math.max(1, Math.round(rule.speed().get()));

            rule.blocks().unwrap().ifLeft(tag -> {
                String bedrockTag = hydraulic$MINEABLE_TAGS.get(tag.location().toString());
                if (bedrockTag == null) {
                    return; // a tag Bedrock has no equivalent for
                }

                speeds.add(hydraulic$speed(NbtMap.builder()
                        .putString("tags", "q.any_tag('" + bedrockTag + "')")
                        .build(), speed));
            }).ifRight(blocks -> {
                // Rules that name blocks outright rather than using a tag
                blocks.forEach(block -> block.unwrapKey().ifPresent(blockKey ->
                        speeds.add(hydraulic$speed(NbtMap.builder()
                                .putString("name", blockKey.identifier().toString())
                                .putCompound("states", NbtMap.EMPTY)
                                .putString("tags", "")
                                .build(), speed))));
            });
        }
    }

    @Unique
    private static NbtMap hydraulic$speed(@NotNull NbtMap block, int speed) {
        return NbtMap.builder()
                .putCompound("block", block)
                .putInt("speed", speed)
                .build();
    }
}
