package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_class_attrs.rs}: the class attributes of TimeFrame checked
 * against C ({@code oracle/class_attrs.json}, from {@code
 * tools/oracle_fixtures/class_attrs_probe.c}). The probe applies each {@code ;}-separated setting
 * to a default object of the class with {@code astSet}, reads every attribute back with {@code
 * astGetC}, {@code astGetI} or {@code astGetD} and its {@code astTest} result, and dumps the
 * object; {@link #make} and {@link #mismatch} do the same through the Java API.
 *
 * <p>The probe's SpecFrame, DSBSpecFrame and FluxFrame cases are skipped (out of scope), and the
 * tests {@code spec_frame_domain_is_cleaned}, {@code dsb_centre_with_no_topocentric_frequency_is_rejected}
 * and {@code flux_frame_domain_is_cleaned} are listed in {@code deferred/rust-tests.txt}.
 */
class ClassAttrsTest {

    /** Whether the probe's class is one this library provides. */
    private static boolean inScope(String cls) {
        return "TimeFrame".equals(cls);
    }

    /** A default object of {@code cls} with each {@code ;}-separated setting applied, as the probe does. */
    private static Frame make(String cls, String settings) {
        if (!"TimeFrame".equals(cls)) {
            throw new IllegalArgumentException("unexpected class " + cls);
        }
        Frame f = new TimeFrame();
        int start = 0;
        while (start < settings.length()) {
            int semi = settings.indexOf(';', start);
            int end = semi < 0 ? settings.length() : semi;
            if (end > start) {
                f.set(settings.substring(start, end));
            }
            start = end + 1;
        }
        return f;
    }

    /** The number {@code s} holds, or null if it holds none. */
    private static Double parse(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether {@code got} is within the Rust test's relative tolerance of {@code want}. */
    private static boolean close(double want, double got) {
        return want == got || Math.abs(want - got) <= 1e-12 * Math.max(Math.abs(want), 1e-300);
    }

    /**
     * Whether the value of {@code attr} on {@code f} matches C's {@code expected}: text exactly or
     * as numbers to 1e-12 relative, doubles to 1e-12 relative, integers exactly, as the Rust test
     * compares them (several values are converted between unit systems).
     */
    private static String mismatch(Frame f, String attr, JsonNode expected) {
        String variant = expected.get("variant").asText();
        JsonNode want = expected.get("value");
        switch (variant) {
            case "S": {
                String got = f.getC(attr);
                String w = want.asText();
                if (got.equals(w)) {
                    return null;
                }
                Double a = parse(w);
                Double b = parse(got);
                if (a != null && b != null && Math.abs(a - b) <= 1e-12 * Math.abs(a)) {
                    return null;
                }
                return "got \"" + got + "\", C gives \"" + w + "\"";
            }
            case "D": {
                double got = f.getD(attr);
                double w = ProbeData.toDouble(want);
                return close(w, got) ? null : "got " + got + ", C gives " + w;
            }
            case "I": {
                int got = f.getI(attr);
                return got == want.asInt() ? null : "got " + got + ", C gives " + want.asInt();
            }
            default:
                throw new IllegalArgumentException("unexpected variant " + variant);
        }
    }

    @Test
    void everyAttributeMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        String lastKey = null;
        Frame obj = null;
        int run = 0;
        JsonNode cases = ProbeData.load("class_attrs").get("VALUE_CASES");
        for (JsonNode c : cases) {
            String cls = c.get(0).asText();
            if (!inScope(cls)) {
                continue;
            }
            String setting = c.get(1).asText();
            String attr = c.get(2).asText();
            String key = cls + "|" + setting;
            if (!key.equals(lastKey)) {
                obj = make(cls, setting);
                lastKey = key;
            }
            run++;
            String what = cls + " \"" + setting + "\" " + attr;
            try {
                String bad = mismatch(obj, attr, c.get(3));
                if (bad != null) {
                    failures.add(what + ": " + bad);
                }
                JsonNode wantTest = c.get(4);
                if (!wantTest.isNull() && obj.test(attr) != wantTest.asBoolean()) {
                    failures.add(what + ": test " + obj.test(attr) + ", C gives " + wantTest.asBoolean());
                }
            } catch (AstException e) {
                failures.add(what + ": " + e.getMessage());
            }
        }
        assertEquals(165, run, "the in-scope cases the C probe recorded");
        assertTrue(
                failures.isEmpty(), failures.size() + " of " + run + " cases differ:\n" + String.join("\n", failures));
    }

    /** The dump is C's, and reading C's dump back gives an object that writes it again. */
    @Test
    void dumpMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("class_attrs").get("DUMP_CASES")) {
            String cls = c.get(0).asText();
            if (!inScope(cls)) {
                continue;
            }
            run++;
            String setting = c.get(1).asText();
            String expected = c.get(2).asText();
            String written = ChannelText.dump(make(cls, setting));
            if (!written.equals(expected)) {
                failures.add("dump for " + cls + " \"" + setting + "\":\n--- Java\n" + written + "--- C\n" + expected);
                continue;
            }
            String rewritten = ChannelText.dump(ChannelText.read(expected));
            if (!rewritten.equals(expected)) {
                failures.add("round trip of C's dump for " + cls + " \"" + setting + "\":\n--- Java\n" + rewritten
                        + "--- C\n" + expected);
            }
        }
        assertEquals(15, run, "the in-scope dumps the C probe recorded");
        assertTrue(
                failures.isEmpty(), failures.size() + " of " + run + " dumps differ:\n" + String.join("\n", failures));
    }

    /**
     * A TimeFrame stores its Domain with white space removed and letters in upper case, as C's
     * {@code astSetC( timeframe, "Domain", "my time" )} gives {@code "MYTIME"}.
     */
    @Test
    void timeFrameDomainIsCleaned() {
        TimeFrame tf = new TimeFrame();
        tf.set("System=MJD");
        tf.setDomain("my time");
        assertEquals("MYTIME", tf.getDomain());
    }

    /**
     * A CmpFrame stores its Domain cleaned, and an empty Domain or Title is a set value rather than
     * a clear, as C's {@code astSetC( cmpframe, "Domain", "my cmp" )} gives {@code "MYCMP"}, {@code
     * astSetC( cmpframe, "Domain", "" )} leaves {@code astTest( cmpframe, "Domain" )} true with the
     * value {@code ""}, and {@code astSetC( cmpframe, "Title", "" )} leaves {@code astTest( cmpframe,
     * "Title" )} true.
     *
     * <p>The Rust test joins a TimeFrame and a FluxFrame (out of scope); here the second component
     * is a 1-d Frame. The components do not affect these results: the Domain and Title are the
     * CmpFrame's own, and cmpframe.c {@code GetDomain} and {@code GetTitle} consult the components
     * only when the attribute is not set.
     */
    @Test
    void cmpFrameDomainIsCleanedAndEmptyIsSet() {
        TimeFrame a = new TimeFrame();
        a.set("System=MJD");
        CmpFrame cf = new CmpFrame(a, new Frame(1));
        cf.setDomain("my cmp");
        assertEquals("MYCMP", cf.getDomain());
        cf.setDomain("");
        assertTrue(cf.test("Domain"));
        assertEquals("", cf.getDomain());
        cf.setTitle("");
        assertTrue(cf.test("Title"));
    }
}
