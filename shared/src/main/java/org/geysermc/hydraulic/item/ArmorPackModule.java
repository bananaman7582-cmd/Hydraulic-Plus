package org.geysermc.hydraulic.item;

import com.google.auto.service.AutoService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.Equippable;
import org.geysermc.hydraulic.pack.PackModule;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.pack.bedrock.resource.attachables.Attachable;
import org.geysermc.pack.bedrock.resource.attachables.Attachables;
import org.geysermc.pack.bedrock.resource.attachables.attachable.Description;
import org.geysermc.pack.bedrock.resource.attachables.attachable.description.Scripts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.equipment.Equipment;
import team.unnamed.creative.equipment.EquipmentLayer;
import team.unnamed.creative.equipment.EquipmentLayerType;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@AutoService(PackModule.class)
public class ArmorPackModule extends PackModule<ArmorPackModule> {
    private static final String BEDROCK_ARMOR_TEXTURE_LOCATION = "textures/entity/%s/equipment/%s/%s";

    private static final Map<String, String> ATTACHABLE_MATERIALS = new HashMap<>() {
        {
            put("default", "armor");
            put("enchanted", "armor_enchanted");
        }
    };
    public ArmorPackModule() {
        this.postProcess(this::postProcess);
    }

    private void postProcess(@NotNull PackPostProcessContext<ArmorPackModule> context) {
        List<Item> armorItems = context.registryValues(BuiltInRegistries.ITEM).stream()
                .filter(item -> item.components().has(DataComponents.EQUIPPABLE) && item.components().get(DataComponents.EQUIPPABLE).assetId().isPresent())
                .toList();

        context.logger().info("Armor to convert: {} in mod {}", armorItems.size(), context.mod().id());

        for (Item armorItem : armorItems) {
            Equippable equippable = armorItem.components().get(DataComponents.EQUIPPABLE);

            EquipmentLayerType layerType = getEquipmentLayer(equippable.slot());
            if (layerType == null) {
                // This might be something else... lets just check
                Optional<HolderSet<EntityType<?>>> optionalEntityType = equippable.allowedEntities();
                if (optionalEntityType.isPresent()) {
                    HolderSet<EntityType<?>> entityTypeHolderSet = optionalEntityType.get();

                    if (entityTypeHolderSet.contains(BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(EntityTypes.HORSE))) {
                        layerType = EquipmentLayerType.HORSE_BODY;
                    } else if (entityTypeHolderSet.contains(BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(EntityTypes.WOLF))) {
                        layerType = EquipmentLayerType.WOLF_BODY;
                    } else if (entityTypeHolderSet.contains(BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(EntityTypes.LLAMA))) {
                        layerType = EquipmentLayerType.LLAMA_BODY;
                    }
                }

                if (layerType == null) { // We recheck as above can change how things go
                    context.logger().debug("Skipping equippable {} - unsupported slot {} and not known animal armor",
                            BuiltInRegistries.ITEM.getKey(armorItem), equippable.slot());
                    continue; // There is no layer we can give the bedrock currently, so we can skip this
                }
            }

            Identifier armorItemLocation = BuiltInRegistries.ITEM.getKey(armorItem);

            Identifier armorTextureLocation = equippable.assetId().map(ResourceKey::identifier).orElseThrow(); // Checked above to ensure all armor processed has an asset id, so this shouldn't throw (This instead of get to prevent yellow lines)

            Key layerTexture = resolveLayerTexture(context, armorTextureLocation, layerType);
            if (layerTexture == null) {
                context.logger().warn("No '{}' equipment layer found for armor item {} (asset {}), skipping",
                        layerType.name().toLowerCase(Locale.ROOT), armorItemLocation, armorTextureLocation);
                continue;
            }

            // Bedrock's vanilla armor geometries and the layer each slot's parent_setup must hide so the
            // client doesn't also draw its own armor layer over ours.
            String geometryType;
            String hiddenLayer;
            switch (equippable.slot()) {
                case HEAD -> { geometryType = "geometry.humanoid.armor.helmet"; hiddenLayer = "helmet_layer_visible"; }
                case CHEST -> { geometryType = "geometry.humanoid.armor.chestplate"; hiddenLayer = "chest_layer_visible"; }
                case LEGS -> { geometryType = "geometry.humanoid.armor.leggings"; hiddenLayer = "leg_layer_visible"; }
                case FEET -> { geometryType = "geometry.humanoid.armor.boots"; hiddenLayer = "boot_layer_visible"; }
                default -> { geometryType = null; hiddenLayer = null; } // e.g. animal armor - not supported yet
            }
            if (geometryType == null) {
                context.logger().debug("Skipping armor item {} - slot {} has no Bedrock armor geometry",
                        armorItemLocation, equippable.slot());
                continue;
            }

            Scripts scripts = new Scripts();
            scripts.parentSetup("variable." + hiddenLayer + " = 0.0;");

            Attachables armorAttachable = new Attachables();
            armorAttachable.formatVersion("1.10.0");

            Description description = new Description();
            description.identifier(armorItemLocation.toString());
            description.materials(ATTACHABLE_MATERIALS);
            description.scripts(scripts);
            description.renderControllers(new String[] { "controller.render.armor" });

            // Deliberately no description.item(...): for armor attachables Bedrock binds purely on the
            // identifier matching the worn item's identifier. The "item" map is a filter used by held
            // items (e.g. vanilla elytra); an entry here that doesn't match how the client names the
            // item suppresses the attachable entirely, which is why armor rendered invisible.

            EquipmentLayerType finalLayerType = layerType;
            description.textures(new HashMap<>() {
                {
                    put("default", String.format(BEDROCK_ARMOR_TEXTURE_LOCATION, layerTexture.namespace(), finalLayerType.name().toLowerCase(), layerTexture.value()));
                    put("enchanted", "textures/misc/enchanted_actor_glint");
                }
            });

            description.geometry(Map.of("default", geometryType));

            Attachable attachable = new Attachable();
            attachable.description(description);
            armorAttachable.attachable(attachable);

            context.bedrockResourcePack().addAttachable(armorAttachable, "attachables/" + armorItemLocation.getPath() + ".json");
        }
    }

    @Override
    public boolean test(@NotNull PackPostProcessContext<ArmorPackModule> context) {
        return context.registryValues(BuiltInRegistries.ITEM).stream().anyMatch(item -> item.components().has(DataComponents.EQUIPPABLE) && item.components().get(DataComponents.EQUIPPABLE).assetId().isPresent());
    }

    private static @Nullable EquipmentLayerType getEquipmentLayer(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD, CHEST, FEET -> EquipmentLayerType.HUMANOID;
            case LEGS -> EquipmentLayerType.HUMANOID_LEGGINGS;
            default -> null;
        };
    }

    /**
     * Resolves the texture key of the first layer of the given equipment asset for the given layer type.
     * <p>
     * Prefers the {@link Equipment} parsed by the resource pack reader, but falls back to reading the
     * equipment definition file directly: most (Fabric) mods ship no {@code pack.mcmeta}, so the reader
     * can't determine the pack format and looks for equipment definitions in the wrong folder, leaving
     * {@link team.unnamed.creative.ResourcePack#equipment(Key)} empty.
     */
    @Nullable
    private Key resolveLayerTexture(@NotNull PackPostProcessContext<ArmorPackModule> context,
                                    @NotNull Identifier assetId, @NotNull EquipmentLayerType layerType) {
        Equipment equipment = context.javaResourcePack().equipment(Key.key(assetId.toString()));
        if (equipment != null) {
            List<EquipmentLayer> layers = equipment.layers().get(layerType);
            if (layers != null && !layers.isEmpty()) {
                return layers.getFirst().texture();
            }
        }

        String layerKey = layerType.name().toLowerCase(Locale.ROOT);
        Path file = context.mod().resolveFile("assets/" + assetId.getNamespace() + "/equipment/" + assetId.getPath() + ".json");
        if (file == null) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject layers = root.getAsJsonObject("layers");
            if (layers == null) {
                return null;
            }
            JsonArray layerArray = layers.getAsJsonArray(layerKey);
            if (layerArray == null || layerArray.isEmpty()) {
                return null;
            }
            JsonObject firstLayer = layerArray.get(0).getAsJsonObject();
            if (!firstLayer.has("texture")) {
                return null;
            }
            return Key.key(firstLayer.get("texture").getAsString());
        } catch (Exception e) {
            context.logger().warn("Failed to read equipment definition {} for {}: {}", file, assetId, e.getMessage());
            return null;
        }
    }
}
