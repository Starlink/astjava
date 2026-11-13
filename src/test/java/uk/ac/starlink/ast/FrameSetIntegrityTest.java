package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_frameset_integrity.rs}: FrameSet integrity, against C for
 * each Frame class ({@code oracle/integrity.json}, from {@code
 * tools/oracle_fixtures/integrity_probe.c}). Each case joins a Frame of one class to a plain base
 * Frame with a UnitMap, sets attributes of that current Frame through the FrameSet as the probe
 * does ({@code astSetC} on the FrameSet), and transforms points from the base Frame to the current
 * one. C restores the integrity of a FrameSet with one routine for every class (frameset.c {@code
 * RestoreIntegrity}): it converts the recorded state of the current Frame to its new state with
 * {@code astConvert}, and reports {@code AST__ILOST} when no conversion exists.
 *
 * <p>The probe's FluxFrame, SpecFluxFrame, SpecFrame and DSBSpecFrame cases, and the CmpFrame cases
 * (a CmpFrame of a SkyFrame and a SpecFrame), need classes outside the first phase and are skipped.
 * Every value is compared within libm rounding, which is no wider than the Rust test's comparison.
 * {@code unit_change_leaves_mappings_alone} reads a FrameSet holding a DSBSpecFrame, a LutMap and
 * a SpecMap and is deferred.
 */
class FrameSetIntegrityTest {

    private static final JsonNode PROBE = ProbeData.load("integrity");

    /** The prefixes of the cases that need a class outside the first phase. */
    private static final List<String> SKIPPED_PREFIXES =
            Arrays.asList("flux_", "specflux_", "spec_", "dsb_", "cmp_sky_spec_");

    private static boolean skipped(String name) {
        for (String p : SKIPPED_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static SkyFrame sky(String settings) {
        SkyFrame f = new SkyFrame();
        f.set(settings);
        return f;
    }

    /** The probe's current Frame for {@code name}. */
    private static Frame current(String name) {
        switch (name) {
            case "frame_activeunit_km":
                Frame f = new Frame(1);
                f.set("Unit(1)=m");
                f.setActiveUnit(true);
                return f;
            case "frame_title":
                return new Frame(2);
            case "sky_fk5_equinox":
                return sky("System=FK5");
            case "sky_gappt_epoch":
                return sky("System=GAPPT");
            case "sky_fk4_to_fk5":
                return sky("System=FK4");
            case "sky_icrs_to_galactic":
            case "sky_skyref_origin":
                return sky("System=ICRS");
            case "sky_alignoffset_galactic":
                SkyFrame s = sky("System=ICRS,AlignOffset=1,SkyRefIs=Origin");
                s.setC("SkyRef", "4:00:00,30:00:00");
                return s;
            case "sky_unknown_title":
                return sky("System=UNKNOWN");
            case "sky_permuted_galactic":
                SkyFrame p = sky("System=ICRS");
                p.permAxes(new int[] {2, 1});
                return p;
            case "time_jd":
            case "time_utc":
            case "time_origin":
                TimeFrame t = new TimeFrame();
                t.set("System=MJD,TimeScale=TAI");
                return t;
            default:
                throw new IllegalArgumentException("no case " + name);
        }
    }

    /** The probe's settings for {@code name}, each a name and a value for {@code astSetC}. */
    private static String[][] settings(String name) {
        switch (name) {
            case "frame_activeunit_km":
                return new String[][] {{"Unit(1)", "km"}};
            case "frame_title":
            case "sky_unknown_title":
                return new String[][] {{"Title", "Renamed"}};
            case "sky_fk5_equinox":
                return new String[][] {{"Equinox", "J2050"}};
            case "sky_gappt_epoch":
                return new String[][] {{"Epoch", "2010.0"}};
            case "sky_fk4_to_fk5":
                return new String[][] {{"System", "FK5"}};
            case "sky_icrs_to_galactic":
            case "sky_alignoffset_galactic":
            case "sky_permuted_galactic":
                return new String[][] {{"System", "GALACTIC"}};
            case "sky_skyref_origin":
                return new String[][] {{"SkyRef", "4:00:00,30:00:00"}, {"SkyRefIs", "Origin"}};
            case "time_jd":
                return new String[][] {{"System", "JD"}};
            case "time_utc":
                return new String[][] {{"TimeScale", "UTC"}};
            case "time_origin":
                return new String[][] {{"TimeOrigin", "55000.0"}};
            default:
                throw new IllegalArgumentException("no case " + name);
        }
    }

    /** The probe's input points for {@code name}, axis by axis. */
    private static double[][] points(String name) {
        if ("frame_activeunit_km".equals(name)) {
            return new double[][] {{1000.0, 5.0}};
        }
        if (name.startsWith("time_")) {
            return new double[][] {{0.25, 1.75}};
        }
        return new double[][] {{1.0, 4.2}, {0.5, -0.9}};
    }

    /** Builds the case's FrameSet and applies its settings as the probe does. */
    private static FrameSet run(String name) {
        Frame current = current(name);
        int naxes = current.getNaxes();
        FrameSet fs = new FrameSet(new Frame(naxes));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(naxes), current);
        for (String[] s : settings(name)) {
            fs.setC(s[0], s[1]);
        }
        return fs;
    }

    /** Whether the case matches C, with a description of the difference when it does not. */
    private static String compare(String name, JsonNode expected) {
        String variant = expected.get("variant").asText();
        FrameSet fs;
        try {
            fs = run(name);
        } catch (AstException e) {
            if ("Err".equals(variant)
                    && "ILOST".equals(expected.get("value").asText())
                    && e.getStatus() == AstException.AST__ILOST) {
                return null;
            }
            return name + ": error " + e.getMessage() + ", C gives " + expected;
        }
        if (!"Ok".equals(variant)) {
            return name + ": no error, C reports " + expected.get("value").asText();
        }
        double[][] in = points(name);
        int naxes = in.length;
        int npoint = in[0].length;
        double[][] out = fs.tranP(npoint, naxes, in, true, naxes);
        JsonNode want = expected.get("value");
        List<String> diffs = new ArrayList<>();
        if (want.size() != npoint) {
            return name + ": C gives " + want.size() + " points";
        }
        for (int j = 0; j < npoint; j++) {
            for (int i = 0; i < naxes; i++) {
                double w = ProbeData.toDouble(want.get(j).get(i));
                if (!CValues.cValueEq(out[i][j], w)) {
                    diffs.add("point " + j + " axis " + (i + 1) + ": got " + out[i][j] + ", C gives " + w);
                }
            }
        }
        return diffs.isEmpty() ? null : name + ": " + String.join("; ", diffs);
    }

    @Test
    void integrityMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (JsonNode c : PROBE.get("CASES")) {
            String name = c.get(0).asText();
            if (skipped(name)) {
                continue;
            }
            checked++;
            String failure = compare(name, c.get(1));
            if (failure != null) {
                failures.add(failure);
            }
        }
        assertEquals(13, checked, "cases in the first phase");
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + checked + " cases differ from C:\n" + String.join("\n", failures));
    }

    /**
     * A setting whose integrity cannot be restored reports the error of the restore, with its
     * status, and C's further message naming the setting (frameset.c:11951-11952). The header is
     * {@code wcsconv/inputs/skyref-alt.head} with an infinite {@code SREF2A}: setting {@code
     * SkyRefIs} on the FrameSet read from the alternate description makes a MatrixMap from a
     * matrix of bad values while restoring integrity, which C reports with {@code AST__MTRMT}
     * (status checked against the pinned C library).
     */
    @Test
    void aFailedIntegrityRestoreReportsTheStatusOfTheFailure() {
        String header = Fixtures.read("wcsconv/inputs/skyref-alt.head")
                .replace("SREF2A  =               50.669 / Alternate A: sky ref Gal lat (deg)", "SREF2A  = 1.0E99999");
        assertTrue(header.contains("1.0E99999"), "the fixture still holds the SREF2A card replaced");
        FitsChan fc = FitsHeaders.chanFromLines(header);
        AstException e = assertThrows(AstException.class, fc::read);
        assertEquals(AstException.AST__MTRMT, e.getStatus(), e.getMessage());
        assertTrue(
                e.getMsgText().endsWith("Unable to accommodate the attribute setting \"SkyRefIs=ORIGIN\"."),
                e.getMsgText());
    }
}
