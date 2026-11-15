package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests of the comparison helpers, including the five tests of Rust
 * {@code tests/test_dump_comparator.rs}.
 */
class CValuesTest {

    private static boolean eq(String a, String b) {
        return CValues.nativeStringsEquivalent(a, b, 4);
    }

    @Test
    void astEqualCloseUsesAstTolerance() {
        assertTrue(CValues.astEqualClose(1.0, 1.0 + 1e-12));
        assertFalse(CValues.astEqualClose(1.0, 1.0001));
    }

    @Test
    void ulpCloseCountsUnitsInTheLastPlace() {
        assertTrue(CValues.ulpClose(1.0, Math.nextUp(1.0), 1));
        assertFalse(CValues.ulpClose(1e-300, -1e-300, 4));
    }

    @Test
    void withinLibmRoundingUsesRelativeAndAbsoluteTolerance() {
        assertTrue(CValues.withinLibmRounding(1.0, 1.0 + 5e-14));
        assertFalse(CValues.withinLibmRounding(1.0, 1.0 + 5e-13));
        assertTrue(CValues.withinLibmRounding(CValues.BAD, CValues.BAD));
        assertFalse(CValues.withinLibmRounding(CValues.BAD, 1.0));
        assertFalse(CValues.withinLibmRounding(Double.NaN, Double.NaN));
    }

    @Test
    void identicalDumpsMatch() {
        assertTrue(eq(" Begin ZoomMap\n    Zoom = 2\n", " Begin ZoomMap\n    Zoom = 2\n"));
    }

    @Test
    void aNumberAFewUlpAwayMatches() {
        assertTrue(eq("    Zoom = 0.30000000000000004\n", "    Zoom = 0.3\n"));
        assertTrue(CValues.nativeStringsAstEqual("    Zoom = 1.0000000000001\n", "    Zoom = 1\n"));
    }

    @Test
    void whitespaceMustMatch() {
        assertFalse(eq("    Zoom = 2 \t# Zoom factor\n", "    Zoom = 2 \t#  Zoom factor\n"));
        assertFalse(eq("    Zoom =  2\n", "    Zoom = 2\n"));
        assertFalse(eq(" Begin ZoomMap\n", " Begin ZoomMap \n"));
        assertFalse(eq("\n Begin ZoomMap\n", " Begin ZoomMap\n"), "leading text is not trimmed");
        assertFalse(eq(" Begin ZoomMap\n", " Begin ZoomMap"), "the final newline counts");
    }

    @Test
    void theSameValueWrittenAnotherWayIsADifference() {
        assertFalse(eq("    Zoom = 1.0\n", "    Zoom = 1\n"));
        assertFalse(eq("    Zoom = 1e3\n", "    Zoom = 1000\n"));
        assertFalse(CValues.nativeStringsAstEqual("    Zoom = 1.0\n", "    Zoom = 1\n"));
    }

    @Test
    void paddingMayRealignADifferingNumberInItsFieldOnly() {
        // A right-justified FITS value that gained a digit keeps its width.
        assertTrue(eq(
                "M0_B    = -0.29008032260091554 / Forward matrix value\n",
                "M0_B    =  -0.2900803226009156 / Forward matrix value\n"));
        // The same padding change with the number's width unchanged is not.
        assertFalse(eq(
                "M0_B    =  -0.29008032260091554 / Forward matrix value\n",
                "M0_B    = -0.2900803226009156 / Forward matrix value\n"));
        // A free-format card whose number gained a character keeps its 80
        // columns by losing a trailing space.
        assertTrue(CValues.nativeStringsAstEqual(
                "IM1_A   = 1.3877787807814457E-17 / Inverse matrix value  \n",
                "IM1_A   = -1.3877787807814457E-17 / Inverse matrix value \n"));
        // Nor is padding before a number that matches exactly.
        assertFalse(eq("M0_B    =  -0.5 / x\n", "M0_B    = -0.5 / x\n"));
    }

    @Test
    void stripIsSimpDropsTheIsSimpLine() {
        assertEquals(
                " Begin ZoomMap\n    Zoom = 2", CValues.stripIsSimp(" Begin ZoomMap\n    IsSimp = 1\n    Zoom = 2\n"));
    }
}
