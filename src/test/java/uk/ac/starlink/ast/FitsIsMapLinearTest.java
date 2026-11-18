package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Translation of the unit tests of Rust {@code fitschan/fset_to_store/is_map_linear.rs} (C {@code IsMapLinear}). */
class FitsIsMapLinearTest {

    /**
     * A ZoomMap over a narrow range far from zero: the Hz to GHz unit Mapping of 20 channels of
     * 39062.5 Hz at 1.1e11 Hz, which C judges linear since its fit is made to offset values
     * (c-library-quirks.md, "IsMapLinear judges a linear Mapping non-linear when its values are far
     * from zero").
     */
    @Test
    void linearFarFromZero() {
        ZoomMap m = new ZoomMap(1, 1.0e-9);
        double lo = 1.102e11 - 9.0 * 39062.5;
        double hi = 1.102e11 + 10.0 * 39062.5;
        assertTrue(FitsIsMapLinear.isMapLinear(m, new double[] {lo}, new double[] {hi}, 0, null));
    }

    @Test
    void unitMapIsLinear() {
        UnitMap m = new UnitMap(2);
        assertTrue(FitsIsMapLinear.isMapLinear(m, new double[] {0, 0}, new double[] {1, 1}, 0, null));
        assertTrue(FitsIsMapLinear.isMapLinear(m, new double[] {0, 0}, new double[] {1, 1}, 1, null));
    }

    @Test
    void shiftMapIsLinear() {
        ShiftMap m = new ShiftMap(new double[] {5.0, 10.0});
        assertTrue(FitsIsMapLinear.isMapLinear(m, new double[] {0, 0}, new double[] {100, 100}, 0, null));
    }

    @Test
    void zoomMapIsLinear() {
        ZoomMap m = new ZoomMap(2, 7.5);
        assertTrue(FitsIsMapLinear.isMapLinear(m, new double[] {0, 0}, new double[] {1, 1}, 0, null));
    }

    @Test
    void winMapIsLinear() {
        WinMap m = new WinMap(new double[] {-128.0, -128.0}, new double[] {0.001, 0.001});
        assertTrue(FitsIsMapLinear.isMapLinear(m, new double[] {1, 1}, new double[] {100, 100}, 0, null));
    }

    @Test
    void tanOverLargeBoxIsNotLinear() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        assertFalse(FitsIsMapLinear.isMapLinear(wm, new double[] {-1, -1}, new double[] {1, 1}, 0, null));
    }

    /** C's {@code MapBox} reports an output that does not exist, and IsMapLinear clears the error. */
    @Test
    void outOfRangeAxisReturnsFalse() {
        UnitMap m = new UnitMap(2);
        assertFalse(FitsIsMapLinear.isMapLinear(m, new double[] {0, 0}, new double[] {1, 1}, 5, null));
    }

    /** The input that alone feeds the output is returned, or -1 when no single input does. */
    @Test
    void coordInNamesTheFeedingInput() {
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        int[] coordIn = {99};
        assertTrue(FitsIsMapLinear.isMapLinear(pm, new double[] {0, 0}, new double[] {10, 10}, 0, coordIn));
        assertEquals(1, coordIn[0]);
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {3.0, 4.0}});
        assertTrue(FitsIsMapLinear.isMapLinear(mm, new double[] {0, 0}, new double[] {10, 10}, 0, coordIn));
        assertEquals(-1, coordIn[0]);
    }

    /** Inverting the Mapping to split it leaves it as it was. */
    @Test
    void theMappingIsLeftAsItWas() {
        ZoomMap m = new ZoomMap(2, 7.5);
        assertTrue(FitsIsMapLinear.isMapLinear(m, new double[] {0, 0}, new double[] {1, 1}, 0, null));
        assertFalse(m.getInvert());
    }
}
