package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_attr_test_clear_oracle.rs}: Mapping attributes set to their
 * default, and set then cleared, checked against C's {@code astTest} and C's dump ({@code
 * oracle/attr_test_clear.json}, from {@code tools/oracle_fixtures/attr_test_clear_probe.c}). A set
 * attribute is written whenever it is set, its default included; a cleared one is written
 * commented, as its default, only when the class marks it "helpful" (such as Zoom and PlrLg).
 *
 * <p>{@link #build} makes each object as the probe does. The cases of LutMap, PcdMap, GrismMap,
 * SplineMap and MathMap are skipped (out of scope).
 */
class AttrTestClearOracleTest {

    /** The cases whose classes are out of scope. */
    private static final List<String> OUT_OF_SCOPE = Arrays.asList(
            "lutmap_default",
            "lutmap_cleared",
            "pcdmap_cleared",
            "grismmap_cleared",
            "splinemap_default",
            "splinemap_cleared",
            "mathmap_default",
            "mathmap_cleared");

    private static PermMap perm() {
        return new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
    }

    private static PolyMap poly() {
        double[] pcf = {1.0, 1, 1, 0, 1.0, 2, 0, 1};
        PolyMap m = new PolyMap(2, 2, 2, pcf, 0, null);
        m.set("NiterInverse=4,TolInverse=1.0E-6");
        return m;
    }

    private static SphMap sph() {
        SphMap m = new SphMap();
        m.set("UnitRadius=0,PolarLong=1.0");
        return m;
    }

    /**
     * The object a case builds, and C's {@code astTest} route for each attribute: PermSplit is
     * protected in C, so the probe tests it through {@code astTestPermSplit}.
     */
    private static Mapping build(String name) {
        switch (name) {
            case "permmap_split_default": {
                PermMap m = perm();
                m.setPermSplit(false);
                return m;
            }
            case "permmap_split_cleared": {
                PermMap m = perm();
                m.setPermSplit(true);
                m.clearPermSplit();
                return m;
            }
            case "zoommap_cleared": {
                ZoomMap m = new ZoomMap(2, 3.0);
                m.clear("Zoom");
                assertEquals(1.0, m.getZoom());
                return m;
            }
            case "polymap_default":
                return poly();
            case "polymap_cleared": {
                PolyMap m = poly();
                m.clear("NiterInverse,TolInverse");
                // C's defaults (polymap.c astMAKE_GET of NiterInverse and TolInverse).
                assertEquals(4, m.getNiterInverse());
                assertEquals(1.0e-6, m.getTolInverse());
                return m;
            }
            case "wcsmap_pv_cleared": {
                WcsMap m = new WcsMap(2, WcsMap.AST__SIN, 1, 2);
                m.set("PV2_1=0.1,PV2_2=0.2");
                m.clear("PV2_1");
                return m;
            }
            case "sphmap_default":
                return sph();
            case "sphmap_cleared": {
                SphMap m = sph();
                m.clear("UnitRadius,PolarLong");
                return m;
            }
            case "invert_set_zero": {
                ZoomMap m = new ZoomMap(1, 2.0);
                m.set("Invert=0");
                return m;
            }
            case "invert_once": {
                ZoomMap m = new ZoomMap(1, 2.0);
                m.invert();
                return m;
            }
            case "invert_zero_inverted_twice": {
                ZoomMap m = new ZoomMap(1, 2.0);
                m.set("Invert=0");
                m.invert();
                m.invert();
                return m;
            }
            case "invert_cleared": {
                ZoomMap m = new ZoomMap(1, 2.0);
                m.set("Invert=1");
                m.clear("Invert");
                return m;
            }
            default:
                throw new IllegalArgumentException("no Java construction for case " + name);
        }
    }

    /** C's {@code astTest} of {@code attrib} on {@code obj}, by the route the probe takes. */
    private static boolean cTest(Mapping obj, String attrib) {
        if ("PermSplit".equals(attrib)) {
            return ((PermMap) obj).testPermSplit();
        }
        return obj.test(attrib);
    }

    @Test
    void testAndDumpMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("attr_test_clear").get("CASES")) {
            String name = c.get(0).asText();
            if (OUT_OF_SCOPE.contains(name)) {
                continue;
            }
            run++;
            Mapping obj = build(name);
            for (JsonNode t : c.get(1)) {
                boolean got = cTest(obj, t.get(0).asText());
                if (got != t.get(1).asBoolean()) {
                    failures.add(name + ": test " + t.get(0).asText() + " " + got + ", C "
                            + t.get(1).asBoolean());
                }
            }
            String written = ChannelText.dump(obj);
            if (!written.equals(c.get(2).asText())) {
                failures.add(name + ": dump\n" + written + "C:\n" + c.get(2).asText());
            }
        }
        assertEquals(12, run, "the in-scope cases the C probe recorded");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** Reading C's dump back gives what C's does. */
    @Test
    void cDumpsReadBackAsInC() {
        for (JsonNode c : ProbeData.load("attr_test_clear").get("CASES")) {
            String name = c.get(0).asText();
            if (OUT_OF_SCOPE.contains(name)) {
                continue;
            }
            AstObject obj = ChannelText.read(c.get(2).asText());
            assertEquals(c.get(3).asText(), ChannelText.dump(obj), name);
        }
    }

    /**
     * The protected WcsMap flags clear back to their true defaults, and a projection parameter or
     * PolarLong set to {@code AST__BAD} is unset, {@code AST__BAD} being C's unset value (wcsmap.c
     * {@code TestPV}, sphmap.c:1441).
     */
    @Test
    void protectedFlagsClearAndBadUnsets() {
        WcsMap w = new WcsMap(2, WcsMap.AST__SIN, 1, 2);
        w.setLonCheck(false);
        w.setFITSProj(false);
        w.setTPNTan(false);
        w.clearLonCheck();
        w.clearFITSProj();
        w.clearTPNTan();
        assertFalse(w.testLonCheck());
        assertFalse(w.testFITSProj());
        assertFalse(w.testTPNTan());
        assertTrue(w.getLonCheck());
        assertTrue(w.getFITSProj());
        assertTrue(w.getTPNTan());
        // PV2_1: zero-based axis 1, parameter 1.
        w.setPV(1, 1, 0.1);
        w.setPV(1, 1, AstObject.AST__BAD);
        assertFalse(w.testPV(1, 1));
        assertFalse(w.test("PV2_1"));

        SphMap s = new SphMap();
        s.setPolarLong(1.0);
        s.setPolarLong(AstObject.AST__BAD);
        assertFalse(s.test("PolarLong"));
        assertEquals(0.0, s.getPolarLong());
    }

    /** Setting and clearing Invert are C's {@code astSetInvert} and {@code astClearInvert}, whose default is not inverted. */
    @Test
    void setAndClearInvert() {
        ZoomMap m = new ZoomMap(1, 2.0);
        m.setInvert(true);
        assertTrue(m.getInvert());
        m.setInvert(true);
        assertTrue(m.getInvert());
        m.clearInvert();
        assertFalse(m.getInvert());
    }
}
