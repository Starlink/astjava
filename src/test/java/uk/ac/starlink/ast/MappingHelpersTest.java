package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Translation of the unit tests in Rust {@code src/mappings/helpers/mod.rs}. */
class MappingHelpersTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void astEqualDoesNotOverflowNearTheTopOfTheRange() {
        // Scaling by the tolerance multiplier first would make the tolerance infinite.
        assertFalse(MappingHelpers.astEqual(Double.MAX_VALUE, 0.0));
        assertFalse(MappingHelpers.astEqual(-Double.MAX_VALUE, 0.0));
        assertFalse(MappingHelpers.astEqual(Double.MAX_VALUE, -Double.MAX_VALUE));
        assertTrue(MappingHelpers.astEqual(Double.MAX_VALUE, Double.MAX_VALUE));
    }

    @Test
    void astEqualsKeepsItsRelativeTolerance() {
        double a = 1.4204e9;
        assertTrue(MappingHelpers.astEqual(a, a * (1.0 + 1.0e-13)));
        assertFalse(MappingHelpers.astEqual(a, a * (1.0 + 1.0e-9)));
    }

    @Test
    void astEqualsKeepsItsNearZeroFloor() {
        assertTrue(MappingHelpers.astEqual(0.0, 1.0e-13));
        assertFalse(MappingHelpers.astEqual(0.0, 1.0e-11));
    }

    @Test
    void astEqualsBadHandling() {
        assertTrue(MappingHelpers.astEqual(BAD, BAD));
        assertFalse(MappingHelpers.astEqual(BAD, 0.0));
        assertFalse(MappingHelpers.astEqual(0.0, BAD));
    }
}
