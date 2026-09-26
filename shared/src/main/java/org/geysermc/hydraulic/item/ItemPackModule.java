package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import com.google.common.collect.Lists;
import net.kyori.adventure.key.Key;
import net.minecraft.tags.ItemTags;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserBlockPlacer;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserChargeable;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.hydraulic.pack.PackLogListener;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.TexturePackModule;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.pack.context.PackPreProcessContext;
import org.geysermc.hydraulic.component.ComponentConverter;
import org.geysermc.hydraulic.util.HydraulicKey;
import org.geysermc.hydraulic.util.ItemModels;
import org.geysermc.hydraulic.util.PackUtil;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.converter.type.model.ModelStitcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.*;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.metadata.animation.AnimationMeta;
import team.unnamed.creative.model.ModelTexture;
import team.unnamed.creative.texture.Texture;

import java.util.Set;
import java.util.*;

@AutoService(PackModule.class)
public class ItemPackModule extends TexturePackModule<ItemPackModule> {
    private final List<Identifier> itemsWith2dIcon = new ArrayList<>();
    private final List<Identifier> handheldItems = new ArrayList<>();
    private final Map<String, String> itemBuiltinTexture = new HashMap<>();

    public ItemPackModule() {
        this.listenOn(GeyserDefineCustomItemsEvent.class, this::onDefineCustomItems);

        this.preProcess(this::preProcess);
        this.postProcess(this::postProcess);
    }

    private void handleModel(@NotNull PackPreProcessContext<ItemPackModule> context, ItemModel itemModel, Identifier itemLocation) {
        Key modelKey = ItemModels.defaultModelKey(itemModel);
        if (modelKey == null) {
            return;
        }

        List<Model> modelList = Lists.newArrayList(context.assets((pack) -> { // This can probably be done easier, but im not sure how
            Model model = pack.model(modelKey);
            if (model == null) return List.of();

            return List.of(model);
        }));
        if (modelList.isEmpty()) return;

        Model model = modelList.getFirst();
        Key modelParent = model.parent();
        if (modelParent == null) return;

        if (modelParent.value().equals("item/generated")) { // If the parent is item/generated, it's a 2D icon
            itemsWith2dIcon.add(itemLocation);
        } else if (modelParent.value().equals("item/handheld")) { // If the parent is item/handheld, it's handheld
            itemsWith2dIcon.add(itemLocation); // item/handheld has the parent item/generated, so lets assume it's 2D
            handheldItems.add(itemLocation);
        }
    }

    private void preProcess(@NotNull PackPreProcessContext<ItemPackModule> context) {
        for (team.unnamed.creative.item.Item item : context.assets(ResourcePack::items)) {
            Identifier itemLocation = HydraulicKey.of(item.key()).identifier();
            handleModel(context, item.model(), itemLocation);
        }

//        for (Model model : context.assets(ResourcePack::models)) {
//            Key modelParent = model.parent();
//            if (modelParent != null) {
//                if (modelParent.value().equals("item/generated")) { // If the parent is item/generated, it's a 2D icon
//                    HydraulicKey key = HydraulicKey.of(model.key());
//                    key.path(key.path().replace("item/", ""));
//                    itemsWith2dIcon.add(key.location());
//                } else if (modelParent.value().equals("item/handheld")) { // If the parent is item/handheld, it's handheld
//                    HydraulicKey key = HydraulicKey.of(model.key());
//                    key.path(key.path().replace("item/", ""));
//                    itemsWith2dIcon.add(key.location()); // item/handheld has the parent item/generated, so lets assume it's 2D
//                    handheldItems.add(key.location());
//                }
//            }
//        }

        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);

        PackLogListener packLogListener = new PackLogListener(context.logger());
        for (Item item : items) {
            Identifier itemLocation = BuiltInRegistries.ITEM.getKey(item);

            Model baseModel = context.modelProvider().model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            if (baseModel == null) {
                continue;
            }

            Model model = new ModelStitcher(context.modelProvider(), baseModel, packLogListener).stitch();
            if (model == null) {
                continue;
            }

            List<ModelTexture> layers = model.textures().layers();
            if (layers == null || layers.isEmpty()) {
                continue;
            }

            Key layer0 = layers.getFirst().key();

            if (layer0 != null && layer0.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
                itemBuiltinTexture.put(itemLocation.toString(), PackUtil.getTextureName(layer0.toString()));
            }
        }
    }

    private void postProcess(@NotNull PackPostProcessContext<ItemPackModule> context) {
        ResourcePack assets = context.javaResourcePack();
        BedrockResourcePack bedrockPack = context.bedrockResourcePack();

        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);

        context.logger().info("Items to convert: {} in mod {}", items.size(), context.mod().id());

        Map<Key, AnimationMeta> animated = animatedTextures(assets);

        PackLogListener packLogListener = new PackLogListener(context.logger());
        for (Item item : items) {
            Identifier itemLocation = BuiltInRegistries.ITEM.getKey(item);

            // Where the item says its model is, rather than where its name suggests. An item is free
            // to keep nothing at item/<name> and point elsewhere - one that looks different in the
            // hand than in the inventory leaves that file empty and names a model per situation - and
            // reading the empty one found no texture and dropped the item, which is why a handful of
            // Alex's Mobs items were not merely wrong but absent
            Model baseModel = declaredModel(assets, itemLocation);
            if (baseModel == null) {
                baseModel = assets.model(Key.key(itemLocation.getNamespace(), "item/" + itemLocation.getPath()));
            }

            if (baseModel == null) {
                context.logger().warn("Item {} has no item model, skipping", itemLocation);
                continue;
            }

            Model model = new ModelStitcher(context.modelProvider(), baseModel, packLogListener).stitch();

            Key texture = iconOf(model);
            if (texture == null) {
                // Don't warn if a block as they can use the block model
                if (!(item instanceof BlockItem)) {
                    context.logger().warn("Item {} has no texture to draw, skipping", itemLocation);
                }

                continue;
            }

            String outputLoc = getOutputFromModel(context, texture);
            String texturePath = outputLoc.replace(".png", "");
            bedrockPack.addItemTexture(itemLocation.toString(), texturePath);

            // An animated item is one tall image holding every frame stacked up, and it only looks
            // like an item because the client is told to read it a frame at a time. Bedrock has not
            // been told, so it draws the whole column squeezed into the slot - which is why a lava
            // bottle of twenty frames arrives as an unreadable smear rather than as a bottle
            AnimationMeta animation = animated.get(texture);
            if (animation != null) {
                bedrockPack.addFlipbookTexture(itemLocation.toString(), texturePath, animation.frameTime());
            }
        }
    }

    /**
     * The model an item actually declares, following the same choice the game would make.
     * <p>
     * An item names its model in its own definition rather than by sitting at a predictable path, and
     * may name several - one for the inventory, another for the hand. The inventory one is what
     * Bedrock draws as the icon, so that is the one followed, exactly as when working out whether the
     * item is a flat sprite in the first place.
     */
    @Nullable
    private static Model declaredModel(@NotNull ResourcePack assets, @NotNull Identifier itemLocation) {
        team.unnamed.creative.item.Item declaration =
                assets.item(Key.key(itemLocation.getNamespace(), itemLocation.getPath()));

        if (declaration == null) {
            return null;
        }

        Key model = ItemModels.defaultModelKey(declaration.model());
        return model == null ? null : assets.model(model);
    }

    /**
     * The texture to draw an item's icon with.
     * <p>
     * A flat item names it {@code layer0} and there is nothing else to consider. A model built out of
     * boxes has no layer at all - its textures are named for the faces they cover - and Bedrock has no
     * way to draw one as an icon regardless. Taking any texture it does have gives a flat picture of
     * roughly the right thing, which beats the nothing such an item was showing before.
     */
    @Nullable
    private static Key iconOf(@NotNull Model model) {
        return ItemModels.firstTexture(model);
    }

    /**
     * Every item texture in the mod that is animated, by the key its model refers to it as.
     * <p>
     * Gathered up front rather than looked up per item, since the textures are walked once either way
     * and most items have no animation to find.
     */
    @NotNull
    private static Map<Key, AnimationMeta> animatedTextures(@NotNull ResourcePack assets) {
        Map<Key, AnimationMeta> animated = new HashMap<>();

        for (Texture texture : assets.textures()) {
            Key key = texture.key();
            if (!key.value().startsWith("item/") || !texture.hasMetadata()) {
                continue;
            }

            AnimationMeta meta = texture.meta().meta(AnimationMeta.class);
            if (meta == null) {
                continue;
            }

            // Textures are held under their file name and referred to by models without one, so the
            // two only meet with the extension taken off
            animated.put(Key.key(key.namespace(), key.value().replace(".png", "")), meta);
        }

        return animated;
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<ItemPackModule> context) {
        return !context.registryValues(BuiltInRegistries.ITEM).isEmpty();
    }

    private void onDefineCustomItems(PackEventContext<GeyserDefineCustomItemsEvent, ItemPackModule> context) {
        GeyserDefineCustomItemsEvent event = context.event();
        List<Item> items = context.registryValues(BuiltInRegistries.ITEM);

        DefaultedRegistry<Item> registry = BuiltInRegistries.ITEM;
        for (Item item : items) {
            Identifier itemLocation = registry.getKey(item);

            try {
                NonVanillaCustomItemDefinition.Builder customItemDefinition = NonVanillaCustomItemDefinition.builder(
                        org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString()),
                        org.geysermc.geyser.api.util.Identifier.of(itemLocation.toString()),
                        registry.getId(item)
                )
                        .displayName("%" + item.getDescriptionId());

                CustomItemBedrockOptions.Builder customItemOptions = CustomItemBedrockOptions.builder()
                        .allowOffhand(true);

                // Bedrock decides how a spear is held, raised and thrown by looking for this tag on
                // whatever is in the hand - its own controllers ask
                // query.equipped_item_any_tag('slot.weapon.mainhand', 'minecraft:is_spear'). Without
                // it none of that applies to a modded spear, which is why one sits in the hand at the
                // wrong angle and never winds up
                if (item.getDefaultInstance().is(ItemTags.SPEARS)) {
                    customItemOptions.tags(Set.of(org.geysermc.geyser.api.util.Identifier.of("minecraft:is_spear")));
                }

                // Allow minecraft namespace texture to be used (remapped as hydraulic)
                if (itemBuiltinTexture.containsKey(itemLocation.toString())) {
                    customItemOptions.icon(itemBuiltinTexture.get(itemLocation.toString()));
                }

                // Add the icon if it should have an icon
                boolean is2d = itemsWith2dIcon.contains(itemLocation);
                if (is2d) {
                    customItemOptions.icon(itemLocation.toString());
                }

                // Make it handheld if need be
                if (handheldItems.contains(itemLocation)) {
                    customItemOptions.displayHandheld(true);
                }

                // Set the creative mappings, but only for items a player is meant to be handed. A mod
                // may register items that exist to hold a model rather than to be picked up - Alex's
                // Mobs keeps four beside its stink ray, one per way of holding it - and Java never
                // shows those because they are in no creative tab. Putting every registered item in
                // Bedrock's menu regardless is what turned one stink ray into five
                if (CreativeItems.isObtainable(item)) {
                    CreativeMappings.setup(item, customItemOptions);
                }

                // Set all bedrock components using what java components we have
                ComponentConverter.setGeyserComponents(
                        item.components(),
                        customItemDefinition,
                        customItemOptions
                );

                // Set the needed component for bows to work correctly
                if (item instanceof BowItem) {
                    customItemDefinition.component(
                            GeyserItemDataComponents.CHARGEABLE,
                            GeyserChargeable.builder()
                                    .maxDrawDuration(1f)
                                    .chargeOnDraw(false)
                    );

                    // Include the default icon, this won't change in the hotbar when used but this works the best for now
                    customItemOptions.icon(itemLocation.toString());
                }

                // Set the needed component for crossbows to work correctly
                if (item instanceof CrossbowItem) {
                    customItemDefinition.component(
                            GeyserItemDataComponents.CHARGEABLE,
                            GeyserChargeable.builder()
                                    .maxDrawDuration(0f)
                                    .chargeOnDraw(true)
                    );

                    // Include the default icon, this won't change in the hotbar when used but this works the best for now
                    customItemOptions.icon(itemLocation.toString());
                }

                if (item instanceof BlockItem blockItem) {
                    // Set the block_placer component to the correct block
                    // This fixes animations sometimes not showing
                    Block block = blockItem.getBlock();

                    customItemDefinition.component(
                            GeyserItemDataComponents.BLOCK_PLACER,
                            GeyserBlockPlacer.of(HydraulicKey.of(BuiltInRegistries.BLOCK.getKey(block)), !is2d)
                    );

                    CreativeMappings.setupBlock(block, customItemOptions);
                }

                customItemDefinition.bedrockOptions(customItemOptions);

                event.register(customItemDefinition.build());
            } catch (Exception e) {
                context.logger().error("Unable to register {}:", itemLocation, e);
            }
        }
    }
}
