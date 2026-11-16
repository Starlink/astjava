package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/sky_pole.rs} that test
 * {@code sky_pole} (C {@code SkyPole}, fitschan.c), and the LONPOLE and LATPOLE C writes for the
 * headers of {@code oracle/sky_pole.json} (made by {@code tools/oracle_fixtures/sky_pole_probe.c}),
 * compared with what {@link FitsCelestialAxes#celestialAxes} stores. The {@code wcs_nat_pole} test
 * is translated in {@link FitsNativePoleTest}.
 */
class FitsSkyPoleTest {

    private static final int[] WPERM = {0, 1};

    private static void skyPole(WcsMap wm, Mapping map3, FitsStore store) {
        FitsSkyPole.skyPole(wm, map3, 0, 1, WPERM, ' ', store, "astWrite", "FitsChan");
    }

    @Test
    void tanWithNoPvOverridesEmitsNothing() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        FitsStore store = new FitsStore(2);
        skyPole(wm, new UnitMap(2), store);
        assertNull(store.lonpole);
        assertNull(store.latpole);
        assertNull(store.pv[0][3]);
        assertNull(store.pv[0][4]);
    }

    @Test
    void tanWithPvOverridesEmitsLonpoleLatpole() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.setPV(0, 3, 90.0);
        wm.setPV(0, 4, 45.0);
        FitsStore store = new FitsStore(2);
        skyPole(wm, new UnitMap(2), store);
        assertEquals(90.0, store.lonpole);
        assertEquals(45.0, store.latpole);
        assertEquals(90.0, store.pv[0][3]);
        assertEquals(45.0, store.pv[0][4]);
    }

    @Test
    void tpnSkipsPvCopy() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TPN, 1, 2);
        wm.setPV(0, 3, Math.toRadians(99.0));
        wm.setPV(0, 4, Math.toRadians(99.0));
        FitsStore store = new FitsStore(2);
        skyPole(wm, new UnitMap(2), store);
        assertNull(store.lonpole);
        assertNull(store.latpole);
        assertNull(store.pv[0][3]);
    }

    /**
     * CAR with map3 a UnitMap and no CRVAL: the celestial pole is at native (0, pi/2); WcsNatPole,
     * with LATPOLE 999, gives the solution pi/2 nearer to it, so LATPOLE takes its default, as does
     * LONPOLE (0, the celestial latitude of the fiducial point, 0, being at least its native
     * latitude, 0). The Rust test asserts nothing; these values are worked through C's code.
     */
    @Test
    void nonZenithalWithUnitMap3EmitsNoExplicitKeywords() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__CAR, 1, 2);
        FitsStore store = new FitsStore(2);
        skyPole(wm, new UnitMap(2), store);
        assertNull(store.lonpole);
        assertNull(store.latpole);
        assertNull(store.pv[0][3]);
        assertNull(store.pv[0][4]);
    }

    /** C's {@code IsZenithal}, including its reading of SZP parameter 2 (a C quirk). */
    @Test
    void isZenithalFollowsC() {
        assertTrue(new WcsMap(2, WcsMap.AST__TAN, 1, 2).isZenithal());
        assertTrue(new WcsMap(2, WcsMap.AST__TPN, 1, 2).isZenithal());
        assertFalse(new WcsMap(2, WcsMap.AST__CAR, 1, 2).isZenithal());
        assertTrue(new WcsMap(2, WcsMap.AST__SIN, 1, 2).isZenithal());
        WcsMap slant = new WcsMap(2, WcsMap.AST__SIN, 1, 2);
        slant.setPV(1, 1, 0.1);
        assertFalse(slant.isZenithal());
        WcsMap azp = new WcsMap(2, WcsMap.AST__AZP, 1, 2);
        azp.setPV(1, 1, 2.0);
        assertTrue(azp.isZenithal());
        azp.setPV(1, 2, 10.0);
        assertFalse(azp.isZenithal());
        // SZP parameter 2 defaults to 0, so a default SZP projection is not zenithal.
        assertFalse(new WcsMap(2, WcsMap.AST__SZP, 1, 2).isZenithal());
        WcsMap szp = new WcsMap(2, WcsMap.AST__SZP, 1, 2);
        szp.setPV(1, 2, 90.0);
        assertTrue(szp.isZenithal());
    }

    /** The header of an oracle case, as the probe builds it. */
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

    private static void assertPole(String name, JsonNode want, Double got) {
        if (want.isNull()) {
            assertNull(got, name);
        } else {
            // C writes the value to 15 significant digits.
            assertEquals(want.asDouble(), got, 1e-9, name);
        }
    }

    /**
     * Each header C reads and writes, read here and its FrameSet's celestial axes described: the
     * LONPOLE and LATPOLE stored are those C writes. A zenithal projection gives only the pole set
     * in its WcsMap; any other has the pole worked out from its Mappings.
     */
    @Test
    void skyPoleMatchesTheCLibrary() {
        int checked = 0;
        for (JsonNode c : ProbeData.load("sky_pole").get("CASES")) {
            if (!c.get(5).asBoolean()) {
                continue;
            }
            String name = c.get(0).asText();
            FitsChan in = new FitsChan();
            in.setEncoding("FITS-WCS");
            for (String card : header(c.get(1).asText(), c.get(2).asText())) {
                in.putFits(card, false);
            }
            in.clear("Card");
            FrameSet fs = (FrameSet) in.read();
            FitsStore store = new FitsStore(2);
            FitsChan out = new FitsChan();
            out.setEncoding("FITS-WCS");
            FitsCelestialAxes.celestialAxes(
                    out,
                    fs,
                    new double[] {100, 100},
                    new int[] {0, 1},
                    ' ',
                    store,
                    new boolean[2],
                    0,
                    "astWrite",
                    "FitsChan");
            assertPole(name + " LONPOLE", c.get(3), store.lonpole);
            assertPole(name + " LATPOLE", c.get(4), store.latpole);
            checked++;
        }
        assertNotEquals(0, checked);
    }
}
