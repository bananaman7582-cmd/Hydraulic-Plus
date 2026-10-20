package org.geysermc.hydraulic.block;

import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.junit.jupiter.api.Test;
import org.geysermc.geyser.level.block.GeyserCustomBlockData;
import org.geysermc.geyser.level.block.GeyserCustomBlockState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BedrockBlockPropertiesTest {
    @Test
    void removesAllOversizedCountersButPreservesExactlySixteenValues() {
        var age = IntegerProperty.create("age", 0, 25);
        var spread = IntegerProperty.create("spread", 0, 48);
        var small = IntegerProperty.create("level", 0, 15);
        var lit = BooleanProperty.create("lit");
        assertEquals(List.of(small, lit), BedrockBlockProperties.supported(List.of(age, spread, small, lit)));
    }

    @Test
    void counterOnlyBlockBecomesAPropertylessBedrockBlock() {
        assertTrue(BedrockBlockProperties.supported(List.of(IntegerProperty.create("age", 0, 25))).isEmpty());
        assertTrue(BedrockBlockProperties.supported(List.of()).isEmpty());
    }

    @Test
    void collapsedStatesBuildAgainstGeysersActualDeclaredProperties() {
        var age = IntegerProperty.create("age", 0, 25);
        var spread = IntegerProperty.create("spread", 0, 48);
        var lit = BooleanProperty.create("lit");
        var properties = BedrockBlockProperties.supported(List.of(age, spread, lit));
        var declaration = new GeyserCustomBlockData.Builder().name("counter_block");
        for (var property : properties) {
            declaration.booleanProperty(property.getName());
        }
        var data = declaration.build();
        for (boolean value : new boolean[] {false, true}) {
            var state = new GeyserCustomBlockState.Builder(data);
            for (var property : properties) {
                state.booleanProperty(property.getName(), value);
            }
            assertDoesNotThrow(state::build);
        }
    }
}
