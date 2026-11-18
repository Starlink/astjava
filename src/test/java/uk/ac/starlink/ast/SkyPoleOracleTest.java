package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_sky_pole_oracle.rs}: celestial FITS-WCS headers with an
 * explicit LONPOLE or LATPOLE, from {@code oracle/sky_pole.json} (made by {@code
 * tools/oracle_fixtures/sky_pole_probe.c}), each read and written again by C with the FITS-WCS
 * encoding.
 *
 * <p>{@code sky_pole_matches_the_c_library} compares the LONPOLE and LATPOLE C writes: a
 * projection wcsmap.c {@code IsZenithal} counts as zenithal writes only the pole its WcsMap was
 * given, and any other has the pole worked out from its Mappings (fitschan.c {@code SkyPole}).
 */
class SkyPoleOracleTest {

    /** The header of a case, as the probe builds it: the common cards, then the case's own. */
    private static List<String> header(String proj, String extra) {
        List<String> cards = new ArrayList<>();
        cards.add("NAXIS   = 2");
        cards.add("NAXIS1  = 100");
        cards.add("NAXIS2  = 100");
        cards.add("CRPIX1  = 50.0");
        cards.add("CRPIX2  = 50.0");
        cards.add("CRVAL1  = 30.0");
        cards.add("CRVAL2  = 40.0");
        cards.add("CDELT1  = -0.01");
        cards.add("CDELT2  = 0.01");
        cards.add("CTYPE1  = 'RA---" + proj + "'");
        cards.add("CTYPE2  = 'DEC--" + proj + "'");
        cards.add("RADESYS = 'ICRS'");
        for (String line : CValues.lines(extra)) {
            if (!line.isEmpty()) {
                cards.add(line);
            }
        }
        return cards;
    }

    /**
     * The cases C refuses are those with an explicit LONPOLE that cannot hold for the reference
     * point, which C's {@code WcsNative} reports as an ill-conditioned native-to-celestial
     * conversion with status AST__BDFTS (fitschan.c:38765).
     */
    @Test
    void refusedCasesAreIllConditioned() {
        int refused = 0;
        for (JsonNode c : ProbeData.load("sky_pole").get("CASES")) {
            if (c.get(5).asBoolean()) {
                continue;
            }
            refused++;
            String name = c.get(0).asText();
            FitsChan fc = new FitsChan();
            fc.setEncoding("FITS-WCS");
            for (String card : header(c.get(1).asText(), c.get(2).asText())) {
                fc.putFits(card, false);
            }
            fc.clear("Card");
            AstException e = assertThrows(AstException.class, fc::read, name);
            assertEquals(AstException.AST__BDFTS, e.getStatus(), name + ": " + e.getMessage());
        }
        assertNotEquals(0, refused, "C refuses some cases");
    }

    /**
     * The LONPOLE and LATPOLE written for a case, each null if not written; null if the header
     * is not read or the FrameSet not written.
     */
    private static Double[] written(String proj, String extra) {
        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        for (String card : header(proj, extra)) {
            fc.putFits(card, false);
        }
        fc.clear("Card");
        AstObject obj;
        try {
            obj = fc.read();
        } catch (AstException e) {
            return null;
        }
        if (obj == null) {
            return null;
        }
        FitsChan out = new FitsChan();
        out.setEncoding("FITS-WCS");
        if (out.write(obj) != 1) {
            return null;
        }
        return new Double[] {out.getFitsF("LONPOLE"), out.getFitsF("LATPOLE")};
    }

    private static Double optional(JsonNode n) {
        return n.isNull() ? null : ProbeData.toDouble(n);
    }

    @Test
    void skyPoleMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("sky_pole").get("CASES")) {
            String name = c.get(0).asText();
            Double lonpole = optional(c.get(3));
            Double latpole = optional(c.get(4));
            boolean ok = c.get(5).asBoolean();
            Double[] got = written(c.get(1).asText(), c.get(2).asText());
            boolean matches =
                    got == null ? !ok : ok && Objects.equals(got[0], lonpole) && Objects.equals(got[1], latpole);
            if (!matches) {
                failures.add(name + ": " + (got == null ? "not written" : got[0] + " " + got[1]) + ", C gives LONPOLE "
                        + lonpole + " LATPOLE " + latpole + " (ok " + ok + ")");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
