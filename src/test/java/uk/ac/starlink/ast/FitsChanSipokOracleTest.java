package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_sipok_oracle.rs}: FitsChan's SipOK attribute
 * against the C library. A FrameSet with SIP distortion, read from {@code
 * wcsconv/inputs/sip.head} with SipReplace false, is written with every encoding and SipOK unset,
 * false and true. Only FITS-WCS writes SIP coefficients; with SipOK false, or another encoding,
 * the write uses a linear approximation if FitsTol allows one.
 *
 * <p>The cases are those of {@code oracle/fitschan_sipok.json}, made by {@code
 * tools/oracle_fixtures/fitschan_sipok_probe.c} against the C library fixed upstream in bdc6336c
 * (Rust c-library-quirks.md: SipOK applies only to FITS-WCS). Each case is compared byte for byte:
 * what {@code write} returns, then every card.
 *
 * <p>The cases of the encodings in {@link #NOT_WRITTEN}, whose writers this library does not
 * have, do not run; their number is pinned.
 */
class FitsChanSipokOracleTest {

    /** The encodings whose writers this library does not have. */
    private static final Set<String> NOT_WRITTEN =
            new HashSet<>(Arrays.asList("FITS-AIPS", "FITS-AIPS++", "FITS-CLASS", "DSS"));

    /** The number of cases of the encodings in {@link #NOT_WRITTEN}. */
    private static final int NOT_WRITTEN_CASES = 12;

    /** What this library writes for one case, in the probe's format. */
    private static String run(String enc, String sipOk) {
        FitsChan fin = new FitsChan();
        fin.set("SipReplace=0");
        for (String line : CValues.lines(Fixtures.read("wcsconv/inputs/sip.head"))) {
            fin.putFits(line, false);
        }
        fin.clear("Card");
        AstObject obj = fin.read();

        FitsChan out = new FitsChan();
        out.setEncoding(enc);
        // MODERN-JAVA(21): switch expression
        switch (sipOk) {
            case "unset":
                break;
            case "0":
                out.set("SipOK=0");
                break;
            case "1":
                out.set("SipOK=1");
                break;
            default:
                throw new IllegalArgumentException("no SipOK " + sipOk);
        }
        StringBuilder got = new StringBuilder("wrote ").append(out.write(obj)).append('\n');
        out.clear("Card");
        String card;
        while ((card = out.findFits("%f", true)) != null) {
            got.append(card).append('\n');
        }
        return got.toString();
    }

    @Test
    void sipOkWritesMatchTheCLibrary() {
        List<String> failed = new ArrayList<>();
        int run = 0;
        int notWritten = 0;
        for (JsonNode c : ProbeData.load("fitschan_sipok").get("CASES")) {
            String enc = c.get(0).asText();
            String sipOk = c.get(1).asText();
            if (NOT_WRITTEN.contains(enc)) {
                notWritten++;
                continue;
            }
            run++;
            String want = c.get(2).asText();
            String got = run(enc, sipOk);
            if (!got.equals(want)) {
                failed.add(enc + " SipOK=" + sipOk + "\n--- Java\n" + got + "--- C\n" + want);
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
        assertEquals(9, run, "FITS-WCS, FITS-PC and FITS-IRAF cases run");
        assertEquals(NOT_WRITTEN_CASES, notWritten, "cases of encodings not written");
    }

    @Test
    void sipOkAccessors() {
        FitsChan fc = new FitsChan();
        assertEquals(1, fc.getI("SipOK"));
        assertFalse(fc.test("SipOK"));
        fc.set("SipOK=0");
        assertEquals(0, fc.getI("SipOK"));
        assertTrue(fc.test("SipOK"));
        fc.clear("SipOK");
        assertEquals(1, fc.getI("SipOK"));
        assertFalse(fc.test("SipOK"));
    }
}
