package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_allow_simplify.rs}: a restricted simplification follows
 * C's AllowSimplify flags. A CmpMap with RestrictedSimplify set nominates only components that
 * have AllowSimplify set, and a merge gives the Mappings it creates the flag when every Mapping it
 * removed had it (mapping.c {@code astMapMerge_}), so a chain of allowed merges continues.
 *
 * <p>The probe cases come from {@code oracle/allow_simplify.json} (C probe {@code
 * allow_simplify_probe.c}): each is a CmpMap read from C's dump and C's dump of its
 * simplification. The other tests are the ports of {@code testresimp.c} Errors 4 to 13.
 */
class AllowSimplifyOracleTest {

    private static String[] lines(JsonNode arr) {
        String[] out = new String[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).asText();
        }
        return out;
    }

    @Test
    void restrictedSimplificationFollowsTheCLibrary() {
        JsonNode cases = ProbeData.load("allow_simplify").get("CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get(0).asText();
            Mapping map = (Mapping) ChannelText.read(ChannelText.text(lines(c.get(1))));
            String want = ChannelText.text(lines(c.get(2)));
            String got = ChannelText.dump(map.simplify());
            if (!CValues.nativeStringsEquivalent(got, want, 4)) {
                failures.add(name + ":\n" + got + "\nC gives:\n" + want);
            }
        }
        assertEquals(6, cases.size());
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** A ShiftMap then a ZoomMap with AllowSimplify set, in series. */
    private static CmpMap shiftThenAllowedZoom() {
        ZoomMap zm1 = new ZoomMap(1, 2.0);
        CmpMap cm1 = new CmpMap(new ShiftMap(new double[] {1.0}), zm1, true);
        // As C does, the flag is set through the ZoomMap the CmpMap shares.
        zm1.setAllowSimplify(true);
        return cm1;
    }

    /**
     * testresimp.c Errors 4-8. A ShiftMap then a ZoomMap with AllowSimplify, followed by its own
     * inverse, simplifies to a UnitMap; under a restricted simplify only the ZoomMaps may be
     * nominated, so they cancel and leave a ShiftMap followed by an inverted ShiftMap. The
     * expected dump is what the C library writes for the same sequence.
     */
    @Test
    void testresimpRestrictedCancellationKeepsTheShiftmaps() {
        CmpMap cm1 = shiftThenAllowedZoom();
        CmpMap m1 = (CmpMap) cm1.copy();
        m1.invert();
        CmpMap cm2 = new CmpMap(cm1, m1, true);
        assertInstanceOf(UnitMap.class, cm2.simplify(), "Error 4");

        cm2.setRestrictedSimplify(true);
        Mapping m2 = cm2.simplify();
        assertFalse(m2 instanceof UnitMap, "Error 5");
        String expected = ChannelText.text(
                " Begin CmpMap \t# Compound Mapping",
                "    Nin = 1 \t# Number of input coordinates",
                " IsA Mapping \t# Mapping between coordinate systems",
                "    InvB = 1 \t# Second Mapping used in inverse direction",
                "    MapA = \t# First component Mapping",
                "       Begin ShiftMap \t# Shift each coordinate axis",
                "          Nin = 1 \t# Number of input coordinates",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          Sft1 = 1 \t# Shift for axis 1",
                "       End ShiftMap",
                "    MapB = \t# Second component Mapping",
                "       Begin ShiftMap \t# Shift each coordinate axis",
                "          Nin = 1 \t# Number of input coordinates",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          Sft1 = 1 \t# Shift for axis 1",
                "       End ShiftMap",
                " End CmpMap");
        assertEquals(expected, ChannelText.dump(m2), "Errors 6-8");

        CmpMap cmp = assertInstanceOf(CmpMap.class, m2);
        boolean[] series = new boolean[1];
        boolean[] inverts = new boolean[2];
        Mapping[] parts = cmp.decompose(series, inverts);
        assertTrue(parts[0] instanceof ShiftMap && parts[1] instanceof ShiftMap, "Error 6");
        assertFalse(inverts[0], "Error 7");
        assertTrue(inverts[1], "Error 8");
    }

    /**
     * testresimp.c Errors 11-13: Shift(1), Zoom(2), Shift(-2) under a restricted simplify with
     * AllowSimplify on the ZoomMap merges the first two into a WinMap and keeps the last ShiftMap.
     */
    @Test
    void testresimpRestrictedShiftZoomShiftKeepsTheLastShiftmap() {
        CmpMap cm2 = new CmpMap(shiftThenAllowedZoom(), new ShiftMap(new double[] {-2.0}), true);
        cm2.setRestrictedSimplify(true);
        CmpMap cmp = assertInstanceOf(CmpMap.class, cm2.simplify());
        boolean[] series = new boolean[1];
        boolean[] inverts = new boolean[2];
        Mapping[] parts = cmp.decompose(series, inverts);
        assertTrue(parts[0] instanceof WinMap && parts[1] instanceof ShiftMap, "Error 11");
        assertFalse(inverts[0], "Error 12");
        assertFalse(inverts[1], "Error 13");
    }

    /**
     * testresimp.c Errors 9-10: an unrestricted simplify of Shift(1), Zoom(2), Shift(-2) merges to
     * a single ZoomMap of 2.
     */
    @Test
    void testresimpUnrestrictedShiftZoomShiftIsAZoommap() {
        CmpMap cm1 = new CmpMap(new ShiftMap(new double[] {1.0}), new ZoomMap(1, 2.0), true);
        CmpMap cm2 = new CmpMap(cm1, new ShiftMap(new double[] {-2.0}), true);
        ZoomMap zoom = assertInstanceOf(ZoomMap.class, cm2.simplify(), "Error 9");
        assertEquals(2.0, zoom.getZoom(), "Error 10");
    }
}
