package org.geysermc.hydraulic.util;

import com.google.gson.JsonElement;
import net.kyori.adventure.key.Key;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.CompositeItemModel;
import team.unnamed.creative.item.ConditionItemModel;
import team.unnamed.creative.item.Item;
import team.unnamed.creative.item.ItemModel;
import team.unnamed.creative.item.RangeDispatchItemModel;
import team.unnamed.creative.item.ReferenceItemModel;
import team.unnamed.creative.item.SelectItemModel;
import team.unnamed.creative.item.SpecialItemModel;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ModelTexture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Helpers for reading item definitions.
 * <p>
 * Minecraft 1.21.4 replaced the {@code overrides} list inside an item's model with a separate item
 * definition under {@code assets/<namespace>/items/}, built from nested select, condition and range
 * dispatch models. Anything still reading {@code Model#overrides()} silently finds nothing on modern
 * versions, which is what left bows without their drawing animation.
 */
public final class ItemModels {
    private ItemModels() {
    }

    /**
     * Gets the item definition for the given item, if the pack has one.
     *
     * @param assets the pack to look in
     * @param item the item to look up
     * @return the item definition, or {@code null} if the pack doesn't define one
     */
    @Nullable
    public static Item definition(@NotNull ResourcePack assets, @NotNull Identifier item) {
        return assets.item(Key.key(item.getNamespace(), item.getPath()));
    }

    /**
     * Finds the range dispatch that drives a drawing animation, following past the select and
     * condition models that wrap it. A bow keeps it behind a {@code using_item} condition; a
     * crossbow additionally wraps that in a {@code charge_type} select.
     *
     * @param model the item definition's root model
     * @return the range dispatch driving the animation, or {@code null} if there isn't one
     */
    @Nullable
    public static RangeDispatchItemModel findRangeDispatch(@Nullable ItemModel model) {
        if (model instanceof RangeDispatchItemModel rangeDispatch) {
            return rangeDispatch;
        } else if (model instanceof ConditionItemModel condition) {
            // The drawing states sit on the "true" branch of "is the player using this item"
            RangeDispatchItemModel found = findRangeDispatch(condition.onTrue());
            return found != null ? found : findRangeDispatch(condition.onFalse());
        } else if (model instanceof SelectItemModel select) {
            for (SelectItemModel.Case selectCase : select.cases()) {
                RangeDispatchItemModel found = findRangeDispatch(selectCase.model());
                if (found != null) {
                    return found;
                }
            }
            return findRangeDispatch(select.fallback());
        } else if (model instanceof CompositeItemModel composite && !composite.models().isEmpty()) {
            return findRangeDispatch(composite.models().getFirst());
        }

        return null;
    }

    /**
     * Lists the models a range dispatch steps through, in the order the animation plays them: the
     * fallback first, then each entry by ascending threshold. For a bow that gives the undrawn model
     * followed by the two drawing stages.
     *
     * @param rangeDispatch the range dispatch to read
     * @return the models it steps through, in order
     */
    @NotNull
    public static List<ItemModel> orderedModels(@NotNull RangeDispatchItemModel rangeDispatch) {
        List<ItemModel> models = new ArrayList<>();
        models.add(rangeDispatch.fallback());

        rangeDispatch.entries().stream()
                .sorted(Comparator.comparingDouble(RangeDispatchItemModel.Entry::threshold))
                .map(RangeDispatchItemModel.Entry::model)
                .forEach(models::add);

        return models;
    }

    /**
     * Finds the model a select model uses for a named case, e.g. a crossbow's {@code arrow} or
     * {@code rocket} charge.
     *
     * @param model the model to search, only select models can match
     * @param when the case value to look for
     * @return the model used for that case, or {@code null} if there isn't one
     */
    @Nullable
    public static ItemModel caseModel(@Nullable ItemModel model, @NotNull String when) {
        if (!(model instanceof SelectItemModel select)) {
            return null;
        }

        for (SelectItemModel.Case selectCase : select.cases()) {
            for (JsonElement value : selectCase.when()) {
                if (value.isJsonPrimitive() && when.equals(value.getAsString())) {
                    return selectCase.model();
                }
            }
        }

        return null;
    }

    /**
     * Resolves the ordinary, idle model from a modern item definition.
     * <p>
     * This is deliberately based on the shape of the definition rather than an item class or mod
     * name. It therefore covers modded shields and other items whose visible model sits behind a
     * {@code using_item} condition, a display-context select, or a special-render wrapper.
     *
     * @param model the root of the item definition
     * @return the referenced model key, or {@code null} if no reference can be found
     */
    @Nullable
    public static Key defaultModelKey(@Nullable ItemModel model) {
        if (model instanceof ReferenceItemModel reference) {
            return reference.model();
        }

        if (model instanceof SpecialItemModel special) {
            return special.base();
        }

        if (model instanceof ConditionItemModel condition) {
            // An inventory/idle item is not actively being used. This also gives sensible results
            // for other boolean predicates, whose false branch is their normal presentation.
            Key found = defaultModelKey(condition.onFalse());
            return found != null ? found : defaultModelKey(condition.onTrue());
        }

        if (model instanceof SelectItemModel select) {
            ItemModel gui = caseModel(select, "gui");
            Key found = defaultModelKey(gui != null ? gui : select.fallback());
            if (found != null) {
                return found;
            }

            for (SelectItemModel.Case selectCase : select.cases()) {
                found = defaultModelKey(selectCase.model());
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        if (model instanceof CompositeItemModel composite) {
            for (ItemModel child : composite.models()) {
                Key found = defaultModelKey(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        if (model instanceof RangeDispatchItemModel range) {
            Key found = defaultModelKey(range.fallback());
            if (found != null) {
                return found;
            }

            for (RangeDispatchItemModel.Entry entry : range.entries()) {
                found = defaultModelKey(entry.model());
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Chooses a usable texture from a stitched model. Box models name their atlas variables rather
     * than exposing a {@code layer0}, so stopping at the layer list drops many 3D modded items.
     */
    @Nullable
    public static Key firstTexture(@NotNull Model model) {
        List<ModelTexture> layers = model.textures().layers();
        if (layers != null && !layers.isEmpty() && layers.getFirst().key() != null) {
            return layers.getFirst().key();
        }

        for (String preferred : List.of("shield", "layer0", "base", "texture", "default")) {
            ModelTexture texture = model.textures().variables().get(preferred);
            if (texture != null && texture.key() != null) {
                return texture.key();
            }
        }

        for (var entry : model.textures().variables().entrySet()) {
            if (!entry.getKey().equals("particle") && entry.getValue().key() != null) {
                return entry.getValue().key();
            }
        }

        return null;
    }

    /**
     * Resolves the first texture layer of the model an item model refers to.
     *
     * @param assets the pack to resolve the model in
     * @param model the item model, which may wrap a model reference
     * @return the texture key, or {@code null} if it can't be resolved
     */
    @Nullable
    public static Key firstLayer(@NotNull ResourcePack assets, @Nullable ItemModel model) {
        Key modelKey = defaultModelKey(model);
        if (modelKey == null) {
            return null;
        }

        Model referenced = assets.model(modelKey);
        if (referenced == null) {
            return null;
        }

        List<ModelTexture> layers = referenced.textures().layers();
        if (layers == null || layers.isEmpty()) {
            return null;
        }

        return layers.getFirst().key();
    }
}
