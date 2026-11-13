package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translates the unit tests of Rust {@code fitschan/wcs_read.rs} ({@code mod tests}), which build
 * FitsStores directly, and adds tests of the pieces of C's {@code FsetFromStore}, {@code WcsMapFrm}
 * and {@code WcsOthers} the read is made of.
 *
 * <p>{@code decode_1d_spectral}, {@code decode_3d_mixed} and {@code
 * build_pixel_to_world_handles_non_adjacent_celestial_axes} read spectral axes, which need
 * SpecFrame, and are listed in {@code deferred/rust-tests.txt}.
 */
class FitsWcsReadTest {

    /** A minimal two-dimensional celestial FitsStore. */
    private static FitsStore celestial2dStore() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.crpix[0] = 128.0;
        store.crpix[1] = 128.0;
        store.crval[0] = 180.0;
        store.crval[1] = 45.0;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.radesys = "ICRS";
        return store;
    }

    /** Rust's test helper {@code decode_wcs}: the primary description of {@code store} alone. */
    private static FrameSet decodeWcs(FitsStore store) {
        return FitsWcsRead.decodeWcsInner(
                store, store.naxis, false, false, new FitsCardList(), ' ', 0, new FitsWarnings(), new ArrayList<>());
    }

    @Test
    void decode2dCelestial() {
        FrameSet fs = decodeWcs(celestial2dStore());
        assertEquals(2, fs.getNframe());
        assertEquals(2, fs.getFrame(FrameSet.AST__BASE).getNaxes());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void decodeCrpixMapsToCrval() {
        // FITS-WCS paper I: the reference pixel CRPIX maps to the reference value CRVAL.
        FrameSet fs = decodeWcs(celestial2dStore());
        double[][] out = fs.tran2(1, new double[] {128.0}, new double[] {128.0}, true);
        assertEquals(Math.toRadians(180.0), out[0][0], 1e-4);
        assertEquals(Math.toRadians(45.0), out[1][0], 1e-4);
    }

    @Test
    void tabAxisNeedsLutMapWhenTabOkIsPositive() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "WAVE-TAB";
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> FitsWcsRead.decodeWcsInner(
                        store, 1, false, false, new FitsCardList(), ' ', 1, new FitsWarnings(), new ArrayList<>()));
        assertTrue(e.getMessage().endsWith(" needs LutMap"), e.getMessage());
    }

    @Test
    void spectralAxisNeedsSpecFrame() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "FREQ";
        store.crval[0] = 1.4e9;
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> decodeWcs(store));
        assertTrue(e.getMessage().endsWith(" needs SpecFrame"), e.getMessage());
    }

    @Test
    void logAxisNeedsMathMap() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "X-LOG";
        store.crval[0] = 2.0;
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> decodeWcs(store));
        assertTrue(e.getMessage().endsWith(" needs MathMap"), e.getMessage());
    }

    @Test
    void useCtypeCommentsComparesWithTheCommentsOfEarlierOtherAxesByCount() {
        // fitschan.c WcsOthers (~39262-39290): comms is indexed by axis, but compared at the
        // indices 0 to nother-2. With "other" axes 1 and 2 and the same comment on both, the
        // second is compared with comms[0], which is never filled, so the repeat is missed.
        FitsStore store = new FitsStore(3);
        store.ctypeCom[1] = "Same";
        store.ctypeCom[2] = "Same";
        assertTrue(FitsWcsRead.useCtypeComments(store, new int[] {1, 2}));
        // With the "other" axes first the indices agree and the repeat is found.
        FitsStore first = new FitsStore(3);
        first.ctypeCom[0] = "Same";
        first.ctypeCom[1] = "Same";
        assertFalse(FitsWcsRead.useCtypeComments(first, new int[] {0, 1}));
        // A single "other" axis takes its CTYPE (fitschan.c: if( nother == 1 ) usecom = 0).
        assertFalse(FitsWcsRead.useCtypeComments(first, new int[] {0}));
        // A blank comment stops their use.
        first.ctypeCom[1] = "  ";
        assertFalse(FitsWcsRead.useCtypeComments(first, new int[] {0, 1}));
    }

    @Test
    void ctypeDomainJoinsTheOtherCtypesWithoutLog() {
        // fitschan.c WcsOthers: the CTYPE (default AXIS<n>) with any "-LOG" removed, joined by
        // '-', a blank CTYPE contributing nothing.
        FitsStore store = new FitsStore(4);
        store.ctype[0] = "X-LOG";
        store.ctype[2] = "   ";
        store.ctype[3] = "TIME";
        assertEquals("X-AXIS2-TIME", FitsWcsRead.ctypeDomain(store, new int[] {0, 1, 2, 3}));
        assertNull(FitsWcsRead.ctypeDomain(store, new int[] {2}));
    }

    @Test
    void linearFrameTakesSymbolsLabelsUnitsAndWcsname() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "X";
        store.ctype[1] = "Y";
        store.cname[1] = "Height";
        store.cunit[0] = "m";
        Frame frame = FitsWcsRead.buildWorldFrame(store, null);
        assertEquals("X", frame.getSymbol(1));
        assertEquals("X", frame.getLabel(1));
        assertEquals("Height", frame.getLabel(2));
        assertEquals("m", frame.getUnit(1));
        assertEquals("X-Y", frame.getDomain());
        store.wcsname = "Plane";
        frame = FitsWcsRead.buildWorldFrame(store, null);
        assertEquals("PLANE", frame.getDomain());
        assertEquals("Plane coordinates", frame.getTitle());
    }

    @Test
    void joinPixelAxesGivesExtraWcsAxesZero() {
        // fitschan.c AddFrame: extra WCS axes take the constant 0.0 (the default CRPIX).
        Mapping unit = new UnitMap(3);
        Mapping joined = FitsWcsRead.joinPixelAxes(2, unit);
        assertEquals(2, joined.getNin());
        assertEquals(3, joined.getNout());
        double[][] out = joined.tranP(1, 2, new double[][] {{5.0}, {6.0}}, true, 3);
        assertEquals(Arrays.asList(5.0, 6.0, 0.0), Arrays.asList(out[0][0], out[1][0], out[2][0]));
        assertSame(unit, FitsWcsRead.joinPixelAxes(3, unit));
    }
}
