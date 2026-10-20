package org.geysermc.hydraulic.block;

import org.junit.jupiter.api.Test;
import team.unnamed.creative.blockstate.Condition;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockStateConditionsTest {
    @Test
    void handlesNestedAndOrAndPipeSeparatedValues() {
        var condition = Condition.and(Condition.match("facing", "north|south"),
                Condition.or(Condition.match("lit", true), Condition.match("waterlogged", true)));
        assertTrue(BlockStateConditions.matches(condition, Map.of("facing", "south", "lit", "true")::get));
        assertFalse(BlockStateConditions.matches(condition, Map.of("facing", "east", "lit", "true")::get));
        assertFalse(BlockStateConditions.matches(condition, Map.of("facing", "north", "lit", "false")::get));
    }

    @Test
    void absentPropertiesDoNotAccidentallySatisfyACondition() {
        assertFalse(BlockStateConditions.matches(Condition.match("missing", "true"), name -> null));
        assertTrue(BlockStateConditions.matches(Condition.NONE, name -> null));
    }
}
