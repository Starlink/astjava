package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_alt_epoch_oracle.rs}: where a FITS-WCS write
 * takes MJD-OBS and DATE-OBS from when the axis descriptions have different epochs, against C.
 * {@code SkySys} (fitschan.c) stores the date of observation only for the primary axis
 * description. A header has a single MJD-OBS, which an alternate description must not change,
 * whether or not that alternate is written.
 *
 * <p>Each case reads a celestial header with a primary (FK5) and an alternate (galactic, {@code
 * A}) description, with or without MJD-OBS, then optionally sets the Epoch of either Frame, and
 * optionally remaps the alternate Frame with a non-linear MathMap so that its description cannot
 * be written. The FrameSet is written with NAXIS1 and NAXIS2 set. Compared with C: the value the
 * write returns and the cards written. The cases are those of {@code
 * oracle/fitschan_alt_epoch.json}, made by {@code tools/oracle_fixtures/fitschan_alt_epoch_probe.c}.
 *
 */
class FitsChanAltEpochOracleTest {

    private static final String[] HEADER = {
        "NAXIS   = 2",
        "NAXIS1  = 100",
        "NAXIS2  = 100",
        "CRPIX1  = 50",
        "CRPIX2  = 50",
        "CRVAL1  = 10",
        "CRVAL2  = 20",
        "CDELT1  = -0.001",
        "CDELT2  = 0.001",
        "CTYPE1  = 'RA---TAN'",
        "CTYPE2  = 'DEC--TAN'",
        "RADESYS = 'FK5'",
        "EQUINOX = 2000.0",
        "CRPIX1A = 50",
        "CRPIX2A = 50",
        "CRVAL1A = 100",
        "CRVAL2A = 30",
        "CDELT1A = -0.001",
        "CDELT2A = 0.001",
        "CTYPE1A = 'GLON-TAN'",
        "CTYPE2A = 'GLAT-TAN'",
    };

    /**
     * The value the write returns and the cards written. {@code prim} and {@code alt} are the MJD
     * epochs set on the primary and alternate Frames, or 0 for none.
     */
    private static List<String> written(String mjdobs, double prim, double alt, boolean fail) {
        FitsChan fc = new FitsChan();
        for (String card : HEADER) {
            fc.putFits(card, false);
        }
        if (!mjdobs.isEmpty()) {
            fc.putFits(mjdobs, false);
        }
        FrameSet fs = FitsHeaders.readFrameSet(rewound(fc));
        // Frame 2 is the primary description and Frame 3 the alternate.
        if (prim != 0.0) {
            fs.setCurrent(2);
            fs.setEpoch(String.format(java.util.Locale.ROOT, "MJD %.1f", prim));
        }
        fs.setCurrent(3);
        if (alt != 0.0) {
            fs.setEpoch(String.format(java.util.Locale.ROOT, "MJD %.1f", alt));
        }
        if (fail) {
            fs.remapFrame(
                    3, new MathMap(2, 2, new String[] {"x=a+100*b*b", "y=b"}, new String[] {"a=x-100*y*y", "b=y"}));
        }
        fs.setCurrent(2);
        FitsChan out = new FitsChan();
        out.setEncoding("FITS-WCS");
        out.putFits("NAXIS1  = 100", false);
        out.putFits("NAXIS2  = 100", false);
        List<String> got = new ArrayList<>();
        got.add("wrote " + out.write(fs));
        got.addAll(FitsHeaders.foundCards(out));
        return got;
    }

    private static FitsChan rewound(FitsChan fc) {
        fc.clear("Card");
        return fc;
    }

    @Test
    void datesMatchC() {
        List<String> failed = new ArrayList<>();
        int remapped = 0;
        for (JsonNode c : ProbeData.load("fitschan_alt_epoch").get("CASES")) {
            String name = c.get(0).asText();
            boolean fail = c.get(4).asBoolean();
            if (fail) {
                remapped++;
            }
            List<String> want = new ArrayList<>();
            want.add("wrote " + c.get(5).asInt());
            want.addAll(FitsHeaders.strings(c.get(6)));
            List<String> got =
                    written(c.get(1).asText(), c.get(2).asDouble(), c.get(3).asDouble(), fail);
            if (!FitsHeaders.cardsMatchC(got, want)) {
                failed.add(name + ":\n--- Java\n" + String.join("\n", got) + "\n--- C\n" + String.join("\n", want));
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
        assertEquals(3, remapped, "cases that remap the alternate Frame with a MathMap");
    }
}
