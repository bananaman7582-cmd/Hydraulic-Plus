package org.geysermc.hydraulic.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceCompatibilityTest {
    @Test
    void fallsBackFromCodeBackedItemModelProperties() {
        JsonObject root = JsonParser.parseString("""
                {"type":"minecraft:condition","property":"enderscape:enabled",
                 "on_true":{"type":"minecraft:model","model":"enderscape:item/on"},
                 "on_false":{"type":"minecraft:model","model":"enderscape:item/off"}}
                """).getAsJsonObject();

        assertTrue(ResourceCompatibility.simplifyModdedRendering(root));
        assertEquals("minecraft:model", root.get("type").getAsString());
        assertEquals("enderscape:item/off", root.get("model").getAsString());
    }
    @Test
    void dropsInvalidCullfaceButKeepsValidFaces() {
        JsonObject model = JsonParser.parseString("""
                {"elements":[{"faces":{
                  "north":{"texture":"#painting","cullface":"painting"},
                  "south":{"texture":"#painting","cullface":"south"}
                }}]}
                """).getAsJsonObject();

        assertTrue(ResourceCompatibility.simplifyModdedRendering(model));
        JsonObject faces = model.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
        assertFalse(faces.getAsJsonObject("north").has("cullface"));
        assertEquals("south", faces.getAsJsonObject("south").get("cullface").getAsString());
    }

    @Test
    void keepsBaseOfCodeRenderedItemAndDropsCodeTint() {
        JsonObject item = JsonParser.parseString("""
                {"type":"minecraft:special","base":"alexsmobs:item/transmutation_table",
                 "model":{"type":"alexsmobs:icon"},
                 "tints":[{"type":"alexscaves:biome"}]}
                """).getAsJsonObject();

        assertTrue(ResourceCompatibility.simplifyModdedRendering(item));
        assertEquals("minecraft:model", item.get("type").getAsString());
        assertEquals("alexsmobs:item/transmutation_table", item.get("model").getAsString());
        assertFalse(item.has("tints"));
    }

    @Test
    void createsReadableFallbackNames() {
        assertEquals("Rubble Shield", ResourceCompatibility.readableName("rubble_shield"));
        assertEquals("Ancient Cave Painting", ResourceCompatibility.readableName("ancient/cave-painting"));
    }
}
