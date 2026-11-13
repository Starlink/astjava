package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Malformed cards in a celestial FITS-WCS header: a {@code CRVAL1} whose value is a string, and a
 * string value with no closing quote. The read gives what C's gives, and nothing but an {@link
 * AstException} may escape it.
 *
 * <p>C's {@code Split} (fitschan.c:32648) types a value that starts with a quote as a string
 * (fitschan.c:32837-32838) and copies characters up to the next lone quote or the end of the card
 * (fitschan.c:32851-32890), so neither card is malformed to it: it raises no {@code badkeyvalue}
 * warning, which only a value of no type gets (fitschan.c:33018, 33093). A string where C's {@code
 * WcsFcRead} wants a number cannot be converted ({@code CnvType} returns 0 when {@code astSscanf}
 * finds no number, fitschan.c:7864-7867), so the card is neither stored nor marked as used
 * (fitschan.c:37361-37366) and the read goes on as if it were absent, with a {@code badval} warning
 * if that condition is reported (fitschan.c:37377-37388). An absent CRVAL is taken as zero by
 * {@code WcsNative} (fitschan.c:38711-38715).
 */
class MalformedHeaderTest {

    /** A celestial header, with {@code crval1} as its CRVAL1 card, or none if null. */
    private static String[] celestial(String crval1, String ctype1) {
        List<String> cards = new ArrayList<>(Arrays.asList(
                "NAXIS   =                    2",
                "NAXIS1  =                  100",
                "NAXIS2  =                  100",
                ctype1,
                "CTYPE2  = 'DEC--TAN'",
                "CRPIX1  =                   50",
                "CRPIX2  =                   50"));
        if (crval1 != null) {
            cards.add(crval1);
        }
        cards.addAll(Arrays.asList(
                "CRVAL2  =                   20",
                "CDELT1  =               -0.001",
                "CDELT2  =                0.001",
                "RADESYS = 'FK5'",
                "EQUINOX =               2000.0"));
        return cards.toArray(new String[0]);
    }

    private static final String CTYPE1 = "CTYPE1  = 'RA---TAN'";

    /** The pixel positions compared. */
    private static final double[][] PIXELS = {{1.0, 1.0}, {50.0, 50.0}, {100.0, 37.5}};

    /**
     * The read of {@code fc}: a FrameSet. Any exception other than an AstException is reported by
     * its type, as one C could not raise.
     */
    // Any exception other than an AstException is caught to report its type, as one C cannot raise.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static FrameSet read(FitsChan fc) {
        AstObject obj;
        try {
            obj = fc.read();
        } catch (AstException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AssertionError("the read threw " + e.getClass().getName() + ", which C cannot", e);
        }
        assertNotNull(obj, "the read gave no Object");
        assertInstanceOf(FrameSet.class, obj, "the read gave a " + obj.astClass());
        return (FrameSet) obj;
    }

    /** A FitsChan holding {@code cards}, with any exception other than an AstException reported by type. */
    // Any exception other than an AstException is caught to report its type, as one C cannot raise.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static FitsChan put(String... cards) {
        try {
            return FitsHeaders.chan(cards);
        } catch (AstException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AssertionError("putFits threw " + e.getClass().getName() + ", which C cannot", e);
        }
    }

    /** Asserts that the FrameSets map each of {@link #PIXELS} to the same position. */
    private static void assertSamePositions(FrameSet want, FrameSet got) {
        for (double[] pix : PIXELS) {
            double[] w = FitsHeaders.tran(want, pix, true);
            double[] g = FitsHeaders.tran(got, pix, true);
            assertTrue(
                    Arrays.equals(w, g),
                    Arrays.toString(pix) + ": " + Arrays.toString(g) + ", not " + Arrays.toString(w));
        }
    }

    @Test
    void stringCrvalIsStoredWithoutWarning() {
        FitsChan fc = put(celestial("CRVAL1  = 'abc'", CTYPE1));
        assertNull(fc.warnings(), "a string value is not malformed to Split");
        assertEquals("abc", fc.getFitsS("CRVAL1"));
    }

    @Test
    void stringCrvalReadsAsAnAbsentCrval() {
        FitsChan fc = put(celestial("CRVAL1  = 'abc'", CTYPE1));
        FrameSet fs = read(fc);
        assertInstanceOf(SkyFrame.class, fs.getFrame(FrameSet.AST__CURRENT));
        assertSamePositions(read(put(celestial(null, CTYPE1))), fs);
        // The card was not used, so it is left; the cards that were used are not.
        fc.clear("Card");
        assertNotNull(fc.findFits("CRVAL1", false), "CRVAL1 is not used by the read");
        fc.clear("Card");
        assertNull(fc.findFits("CRVAL2", false), "CRVAL2 is used by the read");
    }

    @Test
    void stringCrvalWarnsUnderBadval() {
        FitsChan fc = put(celestial("CRVAL1  = 'abc'", CTYPE1));
        fc.setWarnings("badval");
        read(fc);
        KeyMap warnings = fc.warnings();
        assertNotNull(warnings, "a badval warning is reported");
        assertEquals(1, warnings.mapSize());
        assertEquals(
                "The original FITS header contained a value for keyword CRVAL1 which could not be converted to a"
                        + " floating point number.",
                warnings.mapGet0C("Warning_1"));
        // C's Warn stores the ASTWARN block in front of the current card, CRVAL1.
        List<String> kws = FitsHeaders.keywords(fc);
        int crval1 = kws.indexOf("CRVAL1");
        assertTrue(crval1 > 0 && "ASTWARN".equals(kws.get(crval1 - 1)), "ASTWARN block before CRVAL1: " + kws);
    }

    @Test
    void unterminatedStringIsReadToTheEndOfTheCard() {
        FitsChan fc = put(celestial("CRVAL1  =                   10", "CTYPE1  = 'RA---TAN"));
        assertNull(fc.warnings(), "a string with no closing quote is not malformed to Split");
        assertEquals("RA---TAN", fc.getFitsS("CTYPE1"));
    }

    @Test
    void unterminatedStringReadsAsTheTerminatedOne() {
        FrameSet fs = read(put(celestial("CRVAL1  =                   10", "CTYPE1  = 'RA---TAN")));
        assertInstanceOf(SkyFrame.class, fs.getFrame(FrameSet.AST__CURRENT));
        assertSamePositions(read(put(celestial("CRVAL1  =                   10", CTYPE1))), fs);
    }

    @Test
    // Any exception other than an AstException is caught to report its type, as one C cannot raise.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    void onlyAstExceptionsEscape() {
        // The two malformed cards together, read with every warning condition reported and with
        // Strict set: the read either gives a FrameSet or fails with an AstException.
        FitsChan fc = put(celestial("CRVAL1  = 'abc'", "CTYPE1  = 'RA---TAN"));
        fc.setWarnings(fc.getAllWarnings());
        fc.setStrict(true);
        try {
            fc.read();
        } catch (AstException e) {
            // Strict makes the badval warning an error, with status AST__BADIN.
            assertEquals(AstException.AST__BADIN, e.getStatus(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            fail("the read threw " + e.getClass().getName() + ", which C cannot", e);
        }
        fail("Strict makes the badval warning an error");
    }
}
