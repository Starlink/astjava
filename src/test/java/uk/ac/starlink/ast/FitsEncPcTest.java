package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the writer tests of Rust {@code fitschan/enc_pc.rs}: C's {@code PCFromStore},
 * which writes axis descriptions as FITS-PC cards. The module's reader, {@code read_from_cards}, is
 * not used by the Rust port's read and has no counterpart here, so its tests are not translated.
 */
class FitsEncPcTest {

    /** The stores of an image of two pixel axes, with no descriptions yet. */
    private static FitsPlaneStores twoAxes() {
        return new FitsPlaneStores(2);
    }

    /** The primary description of {@code stores}, of two axes. */
    private static FitsStore primary(FitsPlaneStores stores) {
        return stores.version(' ', 2);
    }

    @Test
    void writePc3digit() {
        FitsPlaneStores stores = twoAxes();
        FitsStore store = primary(stores);
        store.crpix[0] = 512.0;
        store.crpix[1] = 512.0;
        store.crval[0] = 180.0;
        store.crval[1] = -45.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.cdelt[0] = -0.0002778;
        store.cdelt[1] = 0.0002778;
        // A rotation by 30 degrees.
        store.pc[0][0] = 0.866025;
        store.pc[0][1] = -0.5;
        store.pc[1][0] = 0.5;
        store.pc[1][1] = 0.866025;
        store.radesys = "FK5";
        store.equinox = 2000.0;

        FitsChan fc = new FitsChan();
        assertTrue(FitsEncPc.pcFromStore(fc, stores, "astWrite", "FitsChan"));
        FitsCardList cards = fc.cards();

        assertNotNull(cards.getFloat("PC001001"));
        assertNotNull(cards.getFloat("PC001002"));
        assertNotNull(cards.getFloat("PC002001"));
        assertNotNull(cards.getFloat("PC002002"));
        assertNull(cards.getFloat("PC1_1"));
        assertEquals(-0.0002778, cards.getFloat("CDELT1"));
        assertEquals(0.0002778, cards.getFloat("CDELT2"));
        assertEquals("FK5", cards.getString("RADECSYS"));
        assertNull(cards.getString("RADESYS"));
        assertNull(cards.getFloat("CD1_1"));
    }

    @Test
    void writePcNeedsCdelt() {
        // FITS-PC writes a CDELT for every axis, so a store without them writes nothing, and the
        // cards written before the gap was found are removed.
        FitsPlaneStores stores = twoAxes();
        FitsStore store = primary(stores);
        store.crpix[0] = 256.0;
        store.crpix[1] = 256.0;
        store.crval[0] = 45.0;
        store.crval[1] = 60.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.pc[0][1] = 0.5;

        FitsChan fc = new FitsChan();
        fc.cards().beginMarkingNew();
        assertFalse(FitsEncPc.pcFromStore(fc, stores, "astWrite", "FitsChan"));
        assertEquals(0, fc.cards().len());
    }

    @Test
    void writePcSflToGls() {
        FitsPlaneStores stores = twoAxes();
        FitsStore store = primary(stores);
        store.crpix[0] = 100.0;
        store.crpix[1] = 100.0;
        store.crval[0] = 0.0;
        store.crval[1] = 0.0;
        store.ctype[0] = "GLON-SFL";
        store.ctype[1] = "GLAT-SFL";
        store.cdelt[0] = -0.01;
        store.cdelt[1] = 0.01;

        FitsChan fc = new FitsChan();
        assertTrue(FitsEncPc.pcFromStore(fc, stores, "astWrite", "FitsChan"));
        assertEquals("GLON-GLS", fc.cards().getString("CTYPE1"));
        assertEquals("GLAT-GLS", fc.cards().getString("CTYPE2"));
    }

    @Test
    void writePcIdentityNotWritten() {
        FitsPlaneStores stores = twoAxes();
        FitsStore store = primary(stores);
        store.crpix[0] = 50.0;
        store.crpix[1] = 50.0;
        store.crval[0] = 10.0;
        store.crval[1] = 20.0;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.pc[0][0] = 1.0;
        store.pc[1][1] = 1.0;

        FitsChan fc = new FitsChan();
        assertTrue(FitsEncPc.pcFromStore(fc, stores, "astWrite", "FitsChan"));
        FitsCardList cards = fc.cards();
        assertNull(cards.getFloat("PC001001"));
        assertNull(cards.getFloat("PC001002"));
        assertNull(cards.getFloat("PC002001"));
        assertNull(cards.getFloat("PC002002"));
    }
}
