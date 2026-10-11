package org.geysermc.hydraulic.util;

import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class RegistryDataSanitizerTest {
    @Test
    void makesUnknownBiomeChoicesAndNamespacedKeysReadable() {
        CompoundTag effects = new CompoundTag();
        effects.putString("grass_color_modifier", "wilderwild_tundra");
        effects.putInt("water_color", 4159204);
        CompoundTag tracks = new CompoundTag();
        tracks.putInt("wilderwild:custom_track", 1);
        tracks.putInt("minecraft:day", 2);
        CompoundTag biome = new CompoundTag();
        biome.put("effects", effects);
        biome.put("tracks", tracks);

        CompoundTag safe = (CompoundTag) sanitize(Registries.BIOME, biome);
        assertEquals("none", safe.getCompound("effects").orElseThrow()
                .getString("grass_color_modifier").orElseThrow());
        assertEquals(4159204, safe.getCompound("effects").orElseThrow()
                .getInt("water_color").orElseThrow());
        assertFalse(safe.getCompound("tracks").orElseThrow().keySet().contains("wilderwild:custom_track"));
        assertEquals("wilderwild_tundra", effects.getString("grass_color_modifier").orElseThrow());
    }

    @Test
    void trimsUnknownEnchantmentConditionsAndEffects() {
        CompoundTag vanillaTerm = new CompoundTag();
        vanillaTerm.putString("condition", "minecraft:entity_properties");
        CompoundTag modTerm = new CompoundTag();
        modTerm.putString("condition", "subtlyd:is_wet");
        ListTag terms = new ListTag();
        terms.add(vanillaTerm);
        terms.add(modTerm);
        CompoundTag requirements = new CompoundTag();
        requirements.putString("condition", "minecraft:any_of");
        requirements.put("terms", terms);
        CompoundTag body = new CompoundTag();
        body.putString("type", "minecraft:add");
        CompoundTag effect = new CompoundTag();
        effect.put("requirements", requirements);
        effect.put("effect", body);
        ListTag damage = new ListTag();
        damage.add(effect);
        CompoundTag effects = new CompoundTag();
        effects.put("minecraft:damage", damage);
        CompoundTag enchantment = new CompoundTag();
        enchantment.put("effects", effects);

        CompoundTag safe = (CompoundTag) sanitize(Registries.ENCHANTMENT, enchantment);
        ListTag safeTerms = safe.getCompound("effects").orElseThrow()
                .getList("minecraft:damage").orElseThrow().getCompound(0).orElseThrow()
                .getCompound("requirements").orElseThrow().getList("terms").orElseThrow();
        assertEquals(1, safeTerms.size());
        assertEquals(2, terms.size());
    }

    @Test
    void leavesAlreadyReadableEntriesUnallocated() {
        CompoundTag biome = new CompoundTag();
        biome.putString("temperature_modifier", "frozen");
        List<RegistrySynchronization.PackedRegistryEntry> entries = List.of(entry(biome));
        assertSame(entries, RegistryDataSanitizer.sanitize(Registries.BIOME, entries));
    }

    private static net.minecraft.nbt.Tag sanitize(
            net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>> registry,
            CompoundTag data) {
        return RegistryDataSanitizer.sanitize(registry, List.of(entry(data)))
                .getFirst().data().orElseThrow();
    }

    private static RegistrySynchronization.PackedRegistryEntry entry(CompoundTag data) {
        return new RegistrySynchronization.PackedRegistryEntry(
                Identifier.fromNamespaceAndPath("test", "entry"), Optional.of(data));
    }
}
