package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_constructor_checks.rs}: constructors and setters refuse
 * arguments C refuses, with C's status.
 *
 * <p>{@code a_conversion_takes_exactly_its_arguments} is translated with SlaMap and TimeMap in Task
 * 13; the others need classes outside the first phase.
 */
class ConstructorChecksTest {

    /** wcsmap.c SetPV: an axis out of range, or one that is not celestial, is AST__AXIIN. */
    @Test
    void aWcsmapPvNeedsACelestialAxis() {
        WcsMap wcs = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        for (int axis : new int[] {0, 3, 4}) {
            AstException e = assertThrows(AstException.class, () -> wcs.setPV(axis - 1, 1, 0.0));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "axis " + axis);
        }
    }

    /** polymap.c StoreArrays: a term for an output that does not exist is AST__BADCI. */
    @Test
    void aPolymapTermNeedsARealOutput() {
        for (int outAxis : new int[] {0, 2}) {
            double[] bad = {1.0, outAxis, 1.0};
            double[] good = {1.0, 1.0, 1.0};
            AstException e = assertThrows(AstException.class, () -> new PolyMap(1, 1, 1, bad, 0, null));
            assertEquals(AstException.AST__BADCI, e.getStatus(), "forward, output " + outAxis);
            e = assertThrows(AstException.class, () -> new PolyMap(1, 1, 1, good, 1, bad));
            assertEquals(AstException.AST__BADCI, e.getStatus(), "inverse, output " + outAxis);
        }
    }
}
