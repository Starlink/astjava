package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_time_convert.rs}: the Mapping {@code convert} finds between
 * two TimeFrames, checked against C ({@code oracle/time_convert.json}, from {@code
 * tools/oracle_fixtures/time_convert_probe.c}).
 *
 * <p>The probe converts between every pair of time scales, both between Frames that share every
 * property and between Frames that differ in observer position, DUT1, DTAI and LTOffset, aligning
 * in the default AlignTimeScale (TAI), UTC and LAST; C makes such a conversion in two parts when
 * one of them depends on a property the Frames differ in (timeframe.c {@code MakeTimeMapping}). A
 * second grid converts between every pair of time systems with TimeOrigins and Units set. The
 * Frames are built as the probe builds them, through the same attribute settings.
 *
 * <p>{@code tdbConvIsTheConversionBetweenMjdTimeFrames} has no Rust counterpart: it checks that
 * the conversion FitsChan uses (fitschan.c {@code TDBConv}) is the one {@code convert} finds.
 */
class TimeConvertTest {

    private static final String[] SYSTEMS = {"MJD", "JD", "JEPOCH", "BEPOCH"};
    private static final String[] ORIGINS = {"55000.5", "2455000.5", "2009.25", "2009.5"};
    private static final String[] UNITS = {"d", "h", "yr", "yr"};

    /** The probe's {@code scale_frame( c, ts, is_to )}. */
    private static TimeFrame scaleFrame(JsonNode props, int c, String ts, boolean isTo) {
        TimeFrame f = new TimeFrame();
        f.set("System=MJD,TimeOrigin=55000.5");
        f.setC("TimeScale", ts);
        if (c > 0) {
            JsonNode p = props.get(isTo ? 1 : 0);
            f.setObsLonValue(ProbeData.toDouble(p.get(0)));
            f.setObsLatValue(ProbeData.toDouble(p.get(1)));
            f.setD("Dut1", ProbeData.toDouble(p.get(2)));
            f.setD("Dtai", ProbeData.toDouble(p.get(3)));
            f.setD("LTOffset", ProbeData.toDouble(p.get(4)));
            if (isTo && c == 2) {
                f.setC("AlignTimeScale", "UTC");
            }
            if (isTo && c == 3) {
                f.setC("AlignTimeScale", "LAST");
            }
        }
        return f;
    }

    /** A Frame of the System grid: the probe sets System, TimeOrigin and Unit on a TT Frame. */
    private static TimeFrame systemFrame(String sys) {
        int i = Arrays.asList(SYSTEMS).indexOf(sys);
        TimeFrame f = new TimeFrame();
        f.set("TimeScale=TT");
        f.setC("System", SYSTEMS[i]);
        f.setC("TimeOrigin", ORIGINS[i]);
        f.setC("Unit", UNITS[i]);
        return f;
    }

    /** The transformed points, "None" if there is no conversion, or "Err" if one fails. */
    private static Object convert(TimeFrame from, TimeFrame to, double[] points) {
        try {
            FrameSet fs = from.convert(to, "");
            if (fs == null) {
                return "None";
            }
            return fs.tran1(2, points, true);
        } catch (AstException e) {
            return "Err";
        }
    }

    /** The parts of a case name {@code grid:from:to}. */
    private static String[] parts(String name) {
        int a = name.indexOf(':');
        int b = name.indexOf(':', a + 1);
        return new String[] {name.substring(0, a), name.substring(a + 1, b), name.substring(b + 1)};
    }

    private static Object run(JsonNode consts, String name) {
        String[] p = parts(name);
        if ("y".equals(p[0])) {
            return convert(systemFrame(p[1]), systemFrame(p[2]), new double[] {0.25, 1.75});
        }
        int c = Integer.parseInt(p[0].substring(1));
        JsonNode props = consts.get("PROPS");
        JsonNode pts = consts.get("POINTS");
        double[] points = {ProbeData.toDouble(pts.get(0)), ProbeData.toDouble(pts.get(1))};
        return convert(scaleFrame(props, c, p[1], false), scaleFrame(props, c, p[2], true), points);
    }

    private static Object expected(JsonNode want) {
        if ("Err".equals(want.get("variant").asText())) {
            return "Err";
        }
        JsonNode v = want.get("value");
        if (v == null || v.isNull()) {
            return "None";
        }
        return new double[] {ProbeData.toDouble(v.get(0)), ProbeData.toDouble(v.get(1))};
    }

    private static boolean same(Object got, Object want) {
        if (got instanceof double[] && want instanceof double[]) {
            return CValues.cValuesEq((double[]) got, (double[]) want);
        }
        return got.equals(want);
    }

    private static String show(Object o) {
        return o instanceof double[] ? Arrays.toString((double[]) o) : o.toString();
    }

    @Test
    void tdbConvIsTheConversionBetweenMjdTimeFrames() {
        double mjd = 55000.75;
        for (String ts : new String[] {"UTC", "TAI", "TT", "TCB", "LAST"}) {
            TimeFrame tdb = new TimeFrame();
            tdb.set("System=MJD,TimeScale=TDB");
            TimeFrame other = new TimeFrame();
            other.set("System=MJD,TimeScale=" + ts);
            FrameSet fs = tdb.convert(other, "");
            int code = TimeFrame.timeScaleCode(ts);
            assertEquals(fs.tran1(1, new double[] {mjd}, false)[0], TimeFrame.tdbConv(mjd, code, false), ts);
            assertEquals(fs.tran1(1, new double[] {mjd}, true)[0], TimeFrame.tdbConv(mjd, code, true), ts);
        }
        assertEquals(mjd, TimeFrame.tdbConv(mjd, TimeFrame.AST__TDB, false));
        assertEquals(AstObject.AST__BAD, TimeFrame.tdbConv(AstObject.AST__BAD, TimeFrame.AST__UTC, false));
    }

    @Test
    void convertMatchesTheCLibrary() {
        JsonNode consts = ProbeData.load("time_convert");
        JsonNode cases = consts.get("CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get(0).asText();
            Object got = run(consts, name);
            Object want = expected(c.get(1));
            if (!same(got, want)) {
                failures.add(name + ": got " + show(got) + ", C gives " + show(want));
            }
        }
        assertEquals(500, cases.size());
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ from C:\n" + String.join("\n", failures));
    }
}
