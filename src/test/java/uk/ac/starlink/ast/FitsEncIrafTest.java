package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the writer tests of Rust {@code fitschan/enc_iraf.rs}: C's {@code IRAFFromStore},
 * which writes the primary axis description as FITS-IRAF cards. The module's reader, {@code
 * read_from_cards}, is not used by the Rust port's read and has no counterpart here, so its tests
 * are not translated.
 */
class FitsEncIrafTest {

    /** The stores of an image of two pixel axes, with a primary description of two axes. */
    private static FitsPlaneStores twoAxes() {
        FitsPlaneStores stores = new FitsPlaneStores(2);
        stores.version(' ', 2);
        return stores;
    }

    @Test
    void writeIrafProducesCd() {
        FitsPlaneStores stores = twoAxes();
        FitsStore store = stores.get(' ');
        store.crpix[0] = 256.0;
        store.crpix[1] = 256.0;
        store.crval[0] = 180.0;
        store.crval[1] = -30.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.cdelt[0] = -0.0005;
        store.cdelt[1] = 0.0005;
        store.radesys = "FK5";
        store.equinox = 2000.0;

        FitsChan fc = new FitsChan();
        assertTrue(FitsEncIraf.irafFromStore(fc, stores, "astWrite", "FitsChan"));
        FitsCardList cards = fc.cards();
        assertEquals(-0.0005, cards.getFloat("CD1_1"));
        assertEquals(0.0005, cards.getFloat("CD2_2"));
        assertEquals("FK5", cards.getString("RADECSYS"));
        assertNull(cards.getString("RADESYS"));
        assertNull(cards.getFloat("CDELT1"));
        assertNull(cards.getFloat("CDELT2"));
        assertNull(cards.getFloat("PC1_1"));
        assertNull(cards.find("WCSAXES"));
        assertNull(cards.getFloat("LONPOLE"));
    }

    @Test
    void writeIrafComputesCdFromPc() {
        FitsPlaneStores stores = twoAxes();
        FitsStore store = stores.get(' ');
        store.crpix[0] = 100.0;
        store.crpix[1] = 100.0;
        store.crval[0] = 45.0;
        store.crval[1] = 60.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        // A rotation by 30 degrees.
        store.pc[0][0] = 0.866025;
        store.pc[0][1] = -0.5;
        store.pc[1][0] = 0.5;
        store.pc[1][1] = 0.866025;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;

        FitsChan fc = new FitsChan();
        assertTrue(FitsEncIraf.irafFromStore(fc, stores, "astWrite", "FitsChan"));
        FitsCardList cards = fc.cards();
        // CDi_j = PCi_j * CDELTi.
        assertTrue(Math.abs(cards.getFloat("CD1_1") - -0.000866025) < 1e-10);
        assertTrue(Math.abs(cards.getFloat("CD1_2") - 0.0005) < 1e-10);
        assertTrue(Math.abs(cards.getFloat("CD2_1") - 0.0005) < 1e-10);
        assertTrue(Math.abs(cards.getFloat("CD2_2") - 0.000866025) < 1e-10);
    }

    @Test
    void translateSflToGls() {
        FitsPlaneStores stores = twoAxes();
        FitsStore store = stores.get(' ');
        store.crpix[0] = 100.0;
        store.crpix[1] = 100.0;
        store.crval[0] = 0.0;
        store.crval[1] = 0.0;
        store.ctype[0] = "GLON-SFL";
        store.ctype[1] = "GLAT-SFL";
        store.cdelt[0] = -0.01;
        store.cdelt[1] = 0.01;

        FitsChan fc = new FitsChan();
        assertTrue(FitsEncIraf.irafFromStore(fc, stores, "astWrite", "FitsChan"));
        assertEquals("GLON-GLS", fc.cards().getString("CTYPE1"));
        assertEquals("GLAT-GLS", fc.cards().getString("CTYPE2"));
    }
}
