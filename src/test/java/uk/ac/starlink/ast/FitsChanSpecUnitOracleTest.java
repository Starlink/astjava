package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_spec_unit_oracle.rs}: how a FITS-WCS write
 * describes a spectral axis, against C's {@code SpectralAxes} ({@code
 * oracle/fitschan_spec_unit.json}, from {@code tools/oracle_fixtures/fitschan_spec_unit_probe.c}):
 * the default reference value is the middle of the spectral range when the size of the spectral
 * pixel axis is known and its first pixel otherwise, and a spectral Unit that is not linearly
 * related to the default Unit is cleared first.
 *
 * <p>Each case reads a frequency cube, optionally sets the Unit of its spectral axis, and writes it
 * to a FitsChan holding the case's NAXIS cards; the value the write returns and the cards written
 * are compared with C's.
 *
 * <p>The read of the cube is checked for every case. A case that stops at something not available
 * ({@code needs <Class>}) is listed with its reason in {@link #UNAVAILABLE}, and the list is
 * checked both ways: a listed case must stop with the reason listed, and every other case must
 * match C.
 */
class FitsChanSpecUnitOracleTest {

    /** The cube every case reads. */
    private static final String[] HEADER = {
        "NAXIS   = 3",
        "CRPIX1  = 5",
        "CRPIX2  = 5",
        "CRPIX3  = 10",
        "CRVAL1  = 10",
        "CRVAL2  = 20",
        "CRVAL3  = 1.4E9",
        "CDELT1  = -0.001",
        "CDELT2  = 0.001",
        "CDELT3  = 1.0E6",
        "CTYPE1  = 'RA---TAN'",
        "CTYPE2  = 'DEC--TAN'",
        "CTYPE3  = 'FREQ'",
        "CUNIT3  = 'Hz'",
        "SPECSYS = 'LSRK'",
        "RESTFRQ = 1.420405752E9",
        "RADESYS = 'FK5'",
        "EQUINOX = 2000.0",
    };

    /** The cases that stop at something not available, with the reason ({@code needs <Class>}). */
    private static final Map<String, String> UNAVAILABLE = unavailableCases();

    private static Map<String, String> unavailableCases() {
        Map<String, String> m = new TreeMap<>();
        return Collections.unmodifiableMap(m);
    }

    /** The FrameSet read from {@link #HEADER}: a CmpFrame of the SkyFrame and a SpecFrame. */
    private static FrameSet readCube() {
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.chan(HEADER));
        Frame cur = fs.getFrame(FrameSet.AST__CURRENT);
        assertInstanceOf(CmpFrame.class, cur);
        assertInstanceOf(SpecFrame.class, cur.primaryFrame(3, new int[1]));
        assertEquals("LSRK", cur.getC("StdOfRest(3)"));
        return fs;
    }

    /**
     * The value the write returns followed by the cards written, or, for a case that stops at
     * something not available, the reason, from {@code needs} on.
     */
    private static List<String> written(String unit, String dims) {
        FrameSet fs = readCube();
        FitsChan out = new FitsChan();
        out.setEncoding("FITS-WCS");
        for (String card : dims.split("~", -1)) {
            if (!card.isEmpty()) {
                out.putFits(card, false);
            }
        }
        int ret;
        try {
            if (!unit.isEmpty()) {
                fs.setUnit(3, unit);
            }
            ret = out.write(fs);
        } catch (UnsupportedOperationException e) {
            String msg = e.getMessage();
            int at = msg.lastIndexOf("needs ");
            assertTrue(at >= 0, msg);
            List<String> reason = new ArrayList<>();
            reason.add(msg.substring(at));
            return reason;
        }
        List<String> got = new ArrayList<>();
        got.add(Integer.toString(ret));
        got.addAll(FitsHeaders.foundCards(out));
        return got;
    }

    @Test
    void spectralAxesMatchC() {
        List<String> failed = new ArrayList<>();
        Map<String, String> stopped = new TreeMap<>();
        for (JsonNode c : ProbeData.load("fitschan_spec_unit").get("CASES")) {
            String name = c.get(0).asText();
            List<String> got = written(c.get(1).asText(), c.get(2).asText());
            if (got.size() == 1 && got.get(0).startsWith("needs ")) {
                stopped.put(name, got.get(0));
                continue;
            }
            List<String> want = new ArrayList<>();
            want.add(Integer.toString(c.get(3).asInt()));
            for (JsonNode card : c.get(4)) {
                want.add(card.asText());
            }
            if (!got.equals(want)) {
                failed.add(name + ":\n--- java\n" + String.join("\n", got) + "\n--- C\n" + String.join("\n", want));
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
        assertEquals(UNAVAILABLE, stopped, "the cases that stop at something not available");
    }
}
