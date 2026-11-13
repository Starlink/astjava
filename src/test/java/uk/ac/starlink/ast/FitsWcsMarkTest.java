package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/wcs_mark.rs}: the sweep of C's {@code
 * WcsFcRead} (fitschan.c:36696) that marks every card holding a FITS-WCS keyword as read. The
 * expected markings follow from the branches of {@code WcsFcRead} (fitschan.c:36800-37352), which
 * set {@code mark} to 1 except for DUT1, DTAI, MJD-OBS, MJD-AVG, OBSGEO-X/Y/Z and TIMESYS.
 */
class FitsWcsMarkTest {

    /** A keyword and whether the sweep marked its card. */
    private static final class Marked {
        final String keyword;
        final boolean wasMarked;

        Marked(String keyword, boolean marked) {
            this.keyword = keyword;
            this.wasMarked = marked;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Marked && ((Marked) o).keyword.equals(keyword) && ((Marked) o).wasMarked == wasMarked;
        }

        @Override
        public int hashCode() {
            return keyword.hashCode() * 2 + (wasMarked ? 1 : 0);
        }

        @Override
        public String toString() {
            return keyword + "=" + wasMarked;
        }
    }

    /** Each card's keyword paired with whether the sweep marked it, leaving out END. */
    private static List<Marked> consumed(String header) {
        FitsCardList cards = FitsCardList.fromHeader(header);
        FitsWcsMark.SweepResult r = FitsWcsMark.markWcsCards(cards, Collections.emptyList(), new FitsWarnings());
        assertNull(r.error);
        List<Marked> out = new ArrayList<>();
        for (FitsCard c : cards.cards()) {
            if (!"END".equals(c.keyword)) {
                out.add(new Marked(c.keyword, c.flags.consumption == FitsCard.Consumption.PROVISIONAL));
            }
        }
        return out;
    }

    private static void assertAll(List<Marked> got, boolean marked) {
        for (Marked m : got) {
            assertEquals(marked, m.wasMarked, got.toString());
        }
    }

    @Test
    void everyCopyOfARepeatedWcsKeywordIsMarked() {
        List<Marked> got = consumed("CRVAL1  =                 1.0\nCRVAL1  =                 1.0\nEND");
        assertEquals(Arrays.asList(new Marked("CRVAL1", true), new Marked("CRVAL1", true)), got);
    }

    @Test
    void observationDetailKeywordsSurvive() {
        List<Marked> got = consumed("MJD-OBS =               50000.0\n"
                + "DUT1    =                   0.1\n"
                + "OBSGEO-X=                1000.0\n"
                + "TIMESYS = 'UTC'\n"
                + "DTAI    =                  37.0\n"
                + "END");
        assertAll(got, false);
    }

    @Test
    void keywordsHandledOnlyBySpecTransSurvive() {
        // CD, RADECSYS, CROTA, PROJP, EPOCH, LONGPOLE and DATE-OBS have no WcsFcRead branch.
        List<Marked> got = consumed("CD1_1   =                 1.0\n"
                + "RADECSYS= 'FK5'\n"
                + "CROTA1  =                 0.0\n"
                + "PROJP1  =                 0.0\n"
                + "EPOCH   =                1950.0\n"
                + "LONGPOLE=                 0.0\n"
                + "DATE-OBS= '2000-01-01'\n"
                + "END");
        assertAll(got, false);
    }

    @Test
    void alternateAxisDescriptionsAreMarked() {
        List<Marked> got =
                consumed("CRVAL1A =                 1.0\nCTYPE2B = 'FREQ'\nPV1_2C  =                 0.0\nEND");
        assertAll(got, true);
    }

    @Test
    void sipCoefficientsAreMarked() {
        List<Marked> got = consumed("A_1_2   =                 1e-6\nBP_0_3  =                 1e-7\nEND");
        assertAll(got, true);
    }

    @Test
    void similarNamesAreNotSwallowed() {
        // SREF%d must not match SREFP1 or SREFIS by prefix; all three have their own branch.
        List<Marked> got =
                consumed("SREF1   =                 1.0\nSREFP1  =                 2.0\nSREFIS  = 'Pole'\nEND");
        assertAll(got, true);
    }

    @Test
    void aStringThatIsNotANumberLeavesAFloatCardUnmarked() {
        List<Marked> got = consumed("CRVAL1  = 'not a number'\nEND");
        assertEquals(Collections.singletonList(new Marked("CRVAL1", false)), got);
    }

    @Test
    void aNumericStringConvertsAndMarks() {
        List<Marked> got = consumed("CRVAL1  = '1.5'\nEND");
        assertEquals(Collections.singletonList(new Marked("CRVAL1", true)), got);
    }

    @Test
    void anUndefinedVelosysIsAccepted() {
        FitsCardList cards = FitsCardList.fromHeader("VELOSYS =\nEND");
        assertNull(FitsWcsMark.markWcsCards(cards, Collections.emptyList(), new FitsWarnings()).error);
    }

    @Test
    void anUndefinedCrvalIsAnError() {
        FitsCardList cards = FitsCardList.fromHeader("CRVAL1  =\nEND");
        AstException e = FitsWcsMark.markWcsCards(cards, Collections.emptyList(), new FitsWarnings()).error;
        assertNotNull(e);
        // CnvType's error (fitschan.c:7797-7799).
        assertEquals(AstException.AST__FUNDEF, e.getStatus());
    }

    /** C warns of an unconvertible value unless the translations hold the keyword (fitschan.c:37378-37390). */
    @Test
    void anUnconvertibleValueWarnsUnlessTranslated() {
        FitsCardList cards = FitsCardList.fromHeader("NAXIS   = 2\nCRVAL1  = 'abc'\nEND");
        FitsWcsMark.SweepResult r = FitsWcsMark.markWcsCards(cards, Collections.emptyList(), new FitsWarnings());
        assertNull(r.error);
        assertEquals(1, r.warnings.size());
        assertEquals(1, r.warnings.get(0)[0]);
        FitsWarnings.Warning w = (FitsWarnings.Warning) r.warnings.get(0)[1];
        assertEquals("badval", w.condition);
        assertEquals(
                "The original FITS header contained a value for keyword CRVAL1 which could not be converted to a"
                        + " floating point number.",
                w.text);
        cards = FitsCardList.fromHeader("NAXIS   = 2\nCRVAL1  = 'abc'\nEND");
        r = FitsWcsMark.markWcsCards(cards, Collections.singletonList("CRVAL1"), new FitsWarnings());
        assertTrue(r.warnings.isEmpty());
    }

    /** With Strict in force the warning ends the sweep as an error, as C's {@code Warn} does. */
    @Test
    void strictTurnsTheWarningIntoAnError() {
        FitsCardList cards = FitsCardList.fromHeader("CRVAL1  = 'abc'\nEND");
        FitsWcsMark.SweepResult r =
                FitsWcsMark.markWcsCards(cards, Collections.emptyList(), FitsWarnings.strict("BadVal", "astRead"));
        assertNotNull(r.error);
        assertEquals(AstException.AST__BADIN, r.error.getStatus());
        assertTrue(r.warnings.isEmpty());
    }

    @Test
    void isWcsKeywordFollowsTheTemplates() {
        assertTrue(FitsWcsMark.isWcsKeyword("CRVAL1"));
        assertTrue(FitsWcsMark.isWcsKeyword("PC1_2A"));
        assertTrue(FitsWcsMark.isWcsKeyword("IMAGFREQ"));
        assertFalse(FitsWcsMark.isWcsKeyword("IMAGFREQA"));
        assertFalse(FitsWcsMark.isWcsKeyword("MJD-OBS"));
        assertFalse(FitsWcsMark.isWcsKeyword("CD1_1"));
    }
}
