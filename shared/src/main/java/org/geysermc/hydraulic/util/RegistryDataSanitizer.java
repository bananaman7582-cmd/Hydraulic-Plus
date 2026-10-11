package org.geysermc.hydraulic.util;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Removes codec values that a vanilla-shaped protocol consumer cannot resolve. */
public final class RegistryDataSanitizer {
    private static final Map<String, Choice> FIXED_CHOICES = Map.of(
            "grass_color_modifier", new Choice(Set.of("none", "dark_forest", "swamp"), "none"),
            "temperature_modifier", new Choice(Set.of("none", "frozen"), "none")
    );

    private RegistryDataSanitizer() {
    }

    private record Choice(Set<String> allowed, String fallback) {
    }

    public static List<RegistrySynchronization.PackedRegistryEntry> sanitize(
            ResourceKey<? extends Registry<?>> registry,
            List<RegistrySynchronization.PackedRegistryEntry> entries) {
        List<RegistrySynchronization.PackedRegistryEntry> result = null;
        for (int index = 0; index < entries.size(); index++) {
            RegistrySynchronization.PackedRegistryEntry entry = entries.get(index);
            Tag source = entry.data().orElse(null);
            if (source == null) {
                continue;
            }
            Tag safe = sanitizeTree(source);
            if (Registries.ENCHANTMENT.equals(registry)) {
                safe = sanitizeEnchantment(safe);
            }
            if (safe != source) {
                if (result == null) {
                    result = new ArrayList<>(entries);
                }
                result.set(index, new RegistrySynchronization.PackedRegistryEntry(entry.id(), Optional.of(safe)));
            }
        }
        return result == null ? entries : result;
    }

    private static Tag sanitizeTree(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            CompoundTag result = null;
            for (String key : List.copyOf(compound.keySet())) {
                Tag value = compound.get(key);
                if (value == null) {
                    continue;
                }
                if (isModdedName(key)) {
                    result = result == null ? compound.copy() : result;
                    result.remove(key);
                    continue;
                }
                Tag replacement = fixedChoice(key, value);
                if (replacement == null) {
                    replacement = sanitizeTree(value);
                }
                if (replacement != value) {
                    result = result == null ? compound.copy() : result;
                    result.put(key, replacement);
                }
            }
            return result == null ? compound : result;
        }
        if (tag instanceof ListTag list) {
            ListTag result = null;
            for (int index = 0; index < list.size(); index++) {
                Tag source = list.get(index);
                Tag safe = sanitizeTree(source);
                if (safe != source) {
                    result = result == null ? list.copy() : result;
                    result.set(index, safe);
                }
            }
            return result == null ? list : result;
        }
        return tag;
    }

    @Nullable
    private static Tag fixedChoice(String key, Tag value) {
        Choice choice = FIXED_CHOICES.get(key);
        if (choice == null) {
            return null;
        }
        String current = value.asString().orElse(null);
        return current == null || choice.allowed().contains(current)
                ? null : StringTag.valueOf(choice.fallback());
    }

    private static Tag sanitizeEnchantment(Tag tag) {
        if (!(tag instanceof CompoundTag enchantment)
                || !(enchantment.get("effects") instanceof CompoundTag effects)) {
            return tag;
        }
        CompoundTag safeEffects = null;
        for (String kind : List.copyOf(effects.keySet())) {
            if (!(effects.get(kind) instanceof ListTag entries)) {
                continue;
            }
            ListTag kept = new ListTag();
            boolean changed = false;
            for (Tag entry : entries) {
                Tag safe = sanitizeEnchantmentEffect(entry);
                if (safe == null) {
                    changed = true;
                } else {
                    kept.add(safe);
                    changed |= safe != entry;
                }
            }
            if (changed) {
                safeEffects = safeEffects == null ? effects.copy() : safeEffects;
                if (kept.isEmpty()) {
                    safeEffects.remove(kind);
                } else {
                    safeEffects.put(kind, kept);
                }
            }
        }
        if (safeEffects == null) {
            return tag;
        }
        CompoundTag result = enchantment.copy();
        result.put("effects", safeEffects);
        return result;
    }

    @Nullable
    private static Tag sanitizeEnchantmentEffect(Tag tag) {
        if (!(tag instanceof CompoundTag effect)) {
            return tag;
        }
        if (effect.get("effect") instanceof CompoundTag body
                && body.get("type") instanceof StringTag type
                && isModdedName(type.asString().orElse(""))) {
            return null;
        }

        Tag requirements = effect.get("requirements");
        if (requirements == null) {
            return tag;
        }
        Tag safeRequirements = sanitizeRequirements(requirements);
        if (safeRequirements == requirements) {
            return tag;
        }
        CompoundTag result = effect.copy();
        if (safeRequirements == null) {
            result.remove("requirements");
        } else {
            result.put("requirements", safeRequirements);
        }
        return result;
    }

    @Nullable
    private static Tag sanitizeRequirements(Tag tag) {
        if (!(tag instanceof CompoundTag requirements)) {
            return tag;
        }
        CompoundTag result = null;
        for (String key : List.copyOf(requirements.keySet())) {
            Tag value = requirements.get(key);
            if ("terms".equals(key) && value instanceof ListTag terms) {
                ListTag kept = new ListTag();
                for (Tag term : terms) {
                    if (!refersToMod(term)) {
                        kept.add(term);
                    }
                }
                if (kept.size() != terms.size()) {
                    if (kept.isEmpty()) {
                        return null;
                    }
                    result = result == null ? requirements.copy() : result;
                    result.put(key, kept);
                }
            } else if (value != null && refersToMod(value)) {
                return null;
            }
        }
        return result == null ? requirements : result;
    }

    private static boolean refersToMod(Tag tag) {
        if (tag instanceof StringTag string) {
            String value = string.asString().orElse("");
            return isModdedName(value.startsWith("#") ? value.substring(1) : value);
        }
        if (tag instanceof CompoundTag compound) {
            return compound.keySet().stream().map(compound::get)
                    .anyMatch(value -> value != null && refersToMod(value));
        }
        if (tag instanceof ListTag list) {
            for (Tag item : list) {
                if (refersToMod(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isModdedName(String value) {
        int separator = value.indexOf(':');
        return separator > 0 && !value.startsWith(Identifier.DEFAULT_NAMESPACE + ":");
    }
}
