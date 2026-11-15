package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code src/fitschan/fits_store.rs}: the shape of a new
 * {@link FitsStore} and the axis classification of its CTYPE values (C's {@code GetSkyAxes},
 * fitschan.c:10809-10825).
 */
class FitsStoreTest {

    @Test
    void newCreatesCorrectSizes() {
        FitsStore store = new FitsStore(2);
        assertEquals(2, store.naxis);
        assertEquals(2, store.crpix.length);
        assertEquals(2, store.crval.length);
        assertEquals(2, store.cdelt.length);
        assertEquals(2, store.pc.length);
        assertEquals(2, store.pc[0].length);
        assertEquals(2, store.cd.length);
        assertEquals(2, store.cd[0].length);
        assertEquals(2, store.ctype.length);
        assertEquals(2, store.cunit.length);
        assertEquals(2, store.cname.length);
        assertEquals(2, store.pv.length);
        assertEquals(FitsStore.MAX_PV_PARAMS, store.pv[0].length);
    }

    @Test
    void newCreatesLargerStore() {
        FitsStore store = new FitsStore(4);
        assertEquals(4, store.naxis);
        assertEquals(4, store.pc.length);
        assertEquals(4, store.pc[0].length);
    }

    @Test
    void hasPcMatrixDetection() {
        FitsStore store = new FitsStore(2);
        assertFalse(store.hasPcMatrix());
        store.pc[0][1] = 0.5;
        assertTrue(store.hasPcMatrix());
    }

    @Test
    void hasSipDetection() {
        FitsStore store = new FitsStore(2);
        assertFalse(store.hasSip());
        store.sip = true;
        assertTrue(store.hasSip());
    }

    @Test
    void celestialAxesRaDec() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        assertArrayEquals(new int[] {0, 1}, store.celestialAxes());
    }

    @Test
    void celestialAxesGalactic() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "FREQ";
        store.ctype[1] = "GLON-CAR";
        store.ctype[2] = "GLAT-CAR";
        assertArrayEquals(new int[] {1, 2}, store.celestialAxes());
    }

    @Test
    void celestialAxesNone() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "FREQ";
        store.ctype[1] = "STOKES";
        assertNull(store.celestialAxes());
    }

    @Test
    void spectralAxisDetection() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.ctype[2] = "FREQ";
        assertEquals(2, store.spectralAxis());
    }

    @Test
    void spectralAxisNone() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        assertEquals(-1, store.spectralAxis());
    }

    /** C's {@code IsSpectral} (fitschan.c:20576) units, and its algorithm-code rule. */
    @Test
    void isSpectralGivesTheDefaultUnit() {
        assertEquals("Hz", FitsStore.isSpectral("FREQ"));
        assertEquals("m/s", FitsStore.isSpectral("VRAD-F2V"));
        assertEquals("", FitsStore.isSpectral("ZOPT"));
        assertNull(FitsStore.isSpectral("FREQXF2V"));
        assertNull(FitsStore.isSpectral("RA---TAN"));
    }

    /** A copy shares no arrays with the original. */
    @Test
    void copySharesNoArrays() {
        FitsStore store = new FitsStore(2);
        store.crval[0] = 1.0;
        store.pc[0][0] = 2.0;
        FitsStore c = store.copy();
        c.crval[0] = 3.0;
        c.pc[0][0] = 4.0;
        assertEquals(1.0, store.crval[0]);
        assertEquals(2.0, store.pc[0][0]);
    }
}
