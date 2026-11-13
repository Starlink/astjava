package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the card unit tests of Rust {@code fitschan/mod.rs}.
 *
 * <p>Left to later tasks: {@code read_consumes_wcs_cards_but_keeps_obs_and_structural} and {@code
 * protected_card_survives_read} need a FITS-WCS read (Task 23); {@code native_round_trip} and
 * {@code rewrite_after_read_re_emits_wcs_cards} write to the FitsChan and are listed in {@code
 * deferred/rust-tests.txt}.
 */
class FitsChanModuleTest {

    @Test
    void newFitschanIsEmpty() {
        FitsChan fc = new FitsChan();
        assertEquals(0, fc.getNcard());
        // C's GetEncoding (fitschan.c:12603) gives NATIVE for an empty FitsChan.
        assertEquals("NATIVE", fc.getEncoding());
        assertFalse(fc.test("Encoding"));
        assertEquals(17, fc.getFitsDigits());
    }

    @Test
    void fromHeaderCreatesFitschan() {
        FitsChan fc = FitsChan.fromHeader("SIMPLE  =                    T / Fits standard\n"
                + "BITPIX  =                  -32 / Bits per pixel\n"
                + "NAXIS   =                    2 / Number of axes\n"
                + "END");
        assertEquals(4, fc.getNcard());
    }

    @Test
    void putFitsAndFindFits() {
        FitsChan fc = new FitsChan();
        fc.putFits("NAXIS   =                    2 / Number of axes", false);
        fc.putFits("CRVAL1  =     266.416666666667 / Reference value", false);
        assertEquals(2, fc.getNcard());
        fc.clear("Card");
        String found = fc.findFits("CRVAL1", true);
        assertNotNull(found);
        assertTrue(found.contains("266.41666666666"));
    }

    @Test
    void setFitsFAndGetFitsF() {
        FitsChan fc = new FitsChan();
        fc.setFits("CRVAL1", 123.456, "Test value", false);
        assertTrue(Math.abs(fc.getFitsF("CRVAL1") - 123.456) < 1e-10);
    }

    @Test
    void setFitsIAndGetFitsI() {
        FitsChan fc = new FitsChan();
        fc.setFits("NAXIS", 3, "Number of axes", false);
        assertEquals(3, (int) fc.getFitsI("NAXIS"));
    }

    @Test
    void setFitsSAndGetFitsS() {
        FitsChan fc = new FitsChan();
        fc.setFits("CTYPE1", "RA---TAN", "Axis type", false);
        assertEquals("RA---TAN", fc.getFitsS("CTYPE1"));
    }

    @Test
    void setFitsLAndGetFitsL() {
        FitsChan fc = new FitsChan();
        fc.setFits("SIMPLE", true, "Standard FITS", false);
        assertTrue(fc.getFitsL("SIMPLE"));
    }

    @Test
    void ncardTracksInsertions() {
        FitsChan fc = new FitsChan();
        assertEquals(0, fc.getNcard());
        fc.setFits("A", 1, "", false);
        assertEquals(1, fc.getNcard());
        fc.setFits("B", 2, "", false);
        assertEquals(2, fc.getNcard());
    }

    @Test
    void emptyFitsClearsAll() {
        FitsChan fc = new FitsChan();
        fc.setFits("NAXIS", 2, "", false);
        fc.setFits("NAXIS1", 100, "", false);
        assertEquals(2, fc.getNcard());
        fc.emptyFits();
        assertEquals(0, fc.getNcard());
    }

    @Test
    void overwriteReplacesExisting() {
        FitsChan fc = new FitsChan();
        fc.setFits("NAXIS", 2, "Original", false);
        fc.clear("Card");
        fc.setFits("NAXIS", 3, "Updated", true);
        assertEquals(1, fc.getNcard());
        assertEquals(3, (int) fc.getFitsI("NAXIS"));
    }

    @Test
    void encodingAccessors() {
        FitsChan fc = new FitsChan();
        fc.setEncoding("NATIVE");
        assertEquals("NATIVE", fc.getEncoding());
    }

    @Test
    void fitsDigitsAccessors() {
        FitsChan fc = new FitsChan();
        fc.setFitsDigits(15);
        assertEquals(15, fc.getFitsDigits());
        fc.setFitsDigits(-3);
        assertEquals(-3, fc.getFitsDigits());
    }

    @Test
    void delFitsRemovesCurrent() {
        FitsChan fc = new FitsChan();
        fc.setFits("A", 1, "", false);
        fc.setFits("B", 2, "", false);
        fc.setFits("C", 3, "", false);
        fc.clear("Card");
        fc.delFits();
        assertEquals(2, fc.getNcard());
    }

    @Test
    void setFitsUStoresUndef() {
        FitsChan fc = new FitsChan();
        fc.setFitsU("BLANK", "Undefined", false);
        assertEquals(FitsCard.Value.UNDEF, fc.cards().find("BLANK").value);
    }

    @Test
    void setFitsCmStoresComment() {
        FitsChan fc = new FitsChan();
        fc.setFitsCM("This is a test", false);
        assertEquals(1, fc.getNcard());
        // C's astSetFitsCM stores a commentary card with a blank keyword.
        fc.clear("Card");
        assertEquals(FitsCard.CardType.COMMENT, fc.getCardType());
        assertEquals("        ", fc.getCardName());
        assertEquals("This is a test", fc.getCardComm());
    }

    @Test
    void putCardsMultiLine() {
        FitsChan fc = new FitsChan();
        StringBuilder cards = new StringBuilder();
        cards.append(pad("SIMPLE  =                    T / Fits standard"))
                .append(pad("NAXIS   =                    2 / Number of axes"))
                .append("END");
        fc.putCards(cards.toString());
        assertEquals(3, fc.getNcard());
        assertEquals(1, fc.getCard());
    }

    @Test
    void rewindResetsPosition() {
        FitsChan fc = FitsChan.fromHeader("NAXIS   =                    2\nNAXIS1  =                  100\nEND");
        fc.findFits("NAXIS1", true);
        fc.clear("Card");
        assertNotNull(fc.findFits("NAXIS", true));
    }

    /** {@code s} padded with spaces to 80 characters. */
    static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 80) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
