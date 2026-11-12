package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_sky_last.rs}: AZEL conversions whose LAST comes from the
 * caches C keeps: the LAST each SkyFrame extrapolates for Epoch changes under 0.4 day, and the
 * table of LAST values already computed, which interpolates between tabulated epochs ({@code
 * oracle/sky_last.json}, from {@code tools/oracle_fixtures/sky_last_probe.c}).
 *
 * <p>The probe runs its steps in one process, so each step sees the caches the earlier steps left.
 * The table is per thread, so each test runs its steps in order on a new thread, which starts
 * with an empty table as the probe's process did.
 */
class SkyLastTest {

    /** Runs {@code body} on a new thread and rethrows anything it throws. */
    private static void assertPassesOnFreshThread(Runnable body) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(body, "fresh LAST table");
        t.setUncaughtExceptionHandler((th, e) -> failure.set(e));
        t.start();
        t.join();
        Throwable e = failure.get();
        if (e instanceof Error) {
            throw (Error) e;
        }
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e != null) {
            throw new AssertionError(e);
        }
    }

    private static SkyFrame sky(String system, double epoch, double obslon) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        f.setEpochMjd(epoch);
        f.setObsLatValue(0.3459);
        f.setObsLonValue(obslon);
        return f;
    }

    private static void setEpochs(SkyFrame a, SkyFrame b, double epoch) {
        a.setEpochMjd(epoch);
        b.setEpochMjd(epoch);
    }

    /** The probe's points transformed by {@code map}, as {@code {x0, y0, x1, y1, ...}}. */
    private static double[] points(Mapping map) {
        JsonNode pts = ProbeData.load("sky_last").get("POINTS");
        int n = pts.size();
        double[] xin = new double[n];
        double[] yin = new double[n];
        for (int i = 0; i < n; i++) {
            xin[i] = pts.get(i).get(0).asDouble();
            yin[i] = pts.get(i).get(1).asDouble();
        }
        double[][] out = map.tran2(n, xin, yin, true);
        double[] got = new double[2 * n];
        for (int i = 0; i < n; i++) {
            got[2 * i] = out[0][i];
            got[2 * i + 1] = out[1][i];
        }
        return got;
    }

    /** Converts {@code from} to {@code to} and transforms the probe's points. */
    private static double[] step(Frame from, Frame to) {
        FrameSet fs = from.convert(to, "");
        assertNotNull(fs, "a conversion");
        return points(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT));
    }

    private static double[] flatten(JsonNode rows) {
        double[] v = new double[2 * rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            v[2 * i] = ProbeData.toDouble(rows.get(i).get(0));
            v[2 * i + 1] = ProbeData.toDouble(rows.get(i).get(1));
        }
        return v;
    }

    @Test
    void cachedLastFollowsTheCLibrary() throws InterruptedException {
        assertPassesOnFreshThread(SkyLastTest::cachedLastSteps);
    }

    private static void cachedLastSteps() {
        JsonNode data = ProbeData.load("sky_last");
        double e0 = data.get("E0").asDouble();
        double lon = data.get("LON").asDouble();
        List<String> names = new ArrayList<>();
        List<double[]> got = new ArrayList<>();

        SkyFrame azel = sky("AZEL", e0, lon);
        SkyFrame icrs = sky("ICRS", e0, lon);
        names.add("pair_e0");
        got.add(step(azel, icrs));
        setEpochs(azel, icrs, e0 + 0.1);
        names.add("pair_plus_0.1");
        got.add(step(azel, icrs));
        setEpochs(azel, icrs, e0 + 0.5);
        names.add("pair_plus_0.5");
        got.add(step(azel, icrs));
        setEpochs(azel, icrs, e0 + 0.45);
        names.add("pair_plus_0.45");
        got.add(step(azel, icrs));
        names.add("pair_reverse");
        got.add(step(icrs, azel));

        SkyFrame a = sky("AZEL", e0 + 0.25, lon);
        SkyFrame b = sky("ICRS", e0 + 0.25, lon);
        names.add("fresh_plus_0.25");
        got.add(step(a, b));
        setEpochs(a, b, e0);
        names.add("fresh_e0");
        got.add(step(a, b));

        a = sky("AZEL", e0 + 0.25, lon + 0.5);
        b = sky("ICRS", e0 + 0.25, lon + 0.5);
        names.add("other_lon_plus_0.25");
        got.add(step(a, b));

        a = sky("FK5", e0 + 2.0, lon);
        b = sky("ICRS", e0 + 2.3, lon);
        names.add("fk5_plus_2");
        got.add(step(a, b));
        a = sky("AZEL", e0 + 2.1, lon);
        b = sky("ICRS", e0 + 2.1, lon);
        names.add("azel_after_fk5");
        got.add(step(a, b));

        SkyFrame copy = (SkyFrame) azel.copy();
        copy.setEpochMjd(e0 + 0.6);
        names.add("copy_plus_0.6");
        got.add(step(copy, icrs));

        azel.setObsLonValue(lon + 0.5);
        icrs.setObsLonValue(lon + 0.5);
        setEpochs(azel, icrs, e0 + 0.35);
        names.add("pair_new_lon_plus_0.35");
        got.add(step(azel, icrs));

        JsonNode steps = data.get("STEPS");
        List<String> wantNames = new ArrayList<>();
        for (JsonNode s : steps) {
            wantNames.add(s.get(0).asText());
        }
        assertEquals(wantNames, names, "the test runs the probe's steps");
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            double[] want = flatten(steps.get(i).get(1));
            if (!CValues.cValuesEq(got.get(i), want)) {
                failures.add(
                        names.get(i) + ": got " + Arrays.toString(got.get(i)) + ", C gives " + Arrays.toString(want));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * An AZEL SkyFrame inside a FrameSet keeps the LAST it computes across conversions of the
     * FrameSet, as one converted directly does, so a change of Epoch under 0.4 day extrapolates it.
     * Values from the Rust repository's {@code tools/oracle_fixtures/frameset_last_probe.c}.
     */
    @Test
    void aFramesetMemberKeepsItsCachedLast() throws InterruptedException {
        assertPassesOnFreshThread(SkyLastTest::framesetMemberSteps);
    }

    private static void framesetMemberSteps() {
        String[] names = {"e0", "plus_0.1", "plus_0.2", "plus_0.35"};
        double[] de = {0.0, 0.1, 0.2, 0.35};
        double[][] want = {
            {
                3.5775542230687107, 0.6552904263691074,
                6.230149614830779, -0.5861196625131542,
                2.259479107493585, 0.5951211878327282
            },
            {
                4.207977312246345, 0.654894463593453,
                0.5774247306448278, -0.5859315528148976,
                2.8898206248374603, 0.5954877789422492
            },
            {
                4.838172355204902, 0.6542902600966608,
                1.2077027700038605, -0.5854121876510435,
                3.5202652721300987, 0.5954854423642868
            },
            {
                5.782937976583033, 0.653501347865355,
                2.1526058615684747, -0.5844650343847089,
                4.465787441810724, 0.5948320834871238
            }
        };
        double[] reverse = {
            0.482364212634511, -0.6218385616399411,
            3.386748910059638, 0.2795961381420723,
            0.2838730065316609, 0.4016498791056195
        };
        JsonNode data = ProbeData.load("sky_last");
        double e0 = data.get("E0").asDouble();
        double lon = data.get("LON").asDouble();
        FrameSet fs = new FrameSet(sky("AZEL", e0, lon));
        SkyFrame icrs = sky("ICRS", e0, lon);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            fs.getFrame(FrameSet.AST__CURRENT).setEpochMjd(e0 + de[i]);
            icrs.setEpochMjd(e0 + de[i]);
            FrameSet cvt = fs.convert(icrs, "");
            assertNotNull(cvt, names[i]);
            double[] got = points(cvt);
            if (!CValues.cValuesEq(got, want[i])) {
                failures.add(names[i] + ": got " + Arrays.toString(got) + ", C gives " + Arrays.toString(want[i]));
            }
        }
        FrameSet cvt = icrs.convert(fs, "");
        assertNotNull(cvt, "reverse");
        double[] got = points(cvt);
        if (!CValues.cValuesEq(got, reverse)) {
            failures.add("reverse: got " + Arrays.toString(got) + ", C gives " + Arrays.toString(reverse));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
