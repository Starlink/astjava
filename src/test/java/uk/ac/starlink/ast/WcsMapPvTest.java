package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * The tests of Rust {@code tests/test_wcsmap_pv.rs}: a projection parameter reads as C's {@code
 * astGetPV} gives it (wcsmap.c {@code GetPV}), with the probe data of {@code
 * oracle/wcsmap_pv.json}.
 */
class WcsMapPvTest {

    /** The C projection type of a FITS code. */
    private static int type(String code) {
        String[] codes = {
            "AZP", "SZP", "TAN", "STG", "SIN", "ARC", "ZPN", "ZEA", "AIR", "CYP", "CEA", "CAR", "MER", "SFL", "PAR",
            "MOL", "AIT", "COP", "COE", "COD", "COO", "BON", "PCO", "TSC", "CSC", "QSC", "NCP", "GLS", "TPN", "HPX",
            "XPH"
        };
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(code)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(code);
    }

    @Test
    void getPvMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("wcsmap_pv").get("CASES")) {
            String name = c.get(0).textValue();
            WcsMap map = new WcsMap(2, type(c.get(1).textValue()), 1, 2);
            String settings = c.get(2).textValue();
            if (!CValues.isBlank(settings)) {
                map.set(settings);
            }
            int axis = c.get(3).intValue();
            int m = c.get(4).intValue();
            JsonNode want = c.get(5);
            String got;
            boolean ok;
            try {
                double v = map.getPV(axis - 1, m);
                got = Double.toString(v);
                ok = !want.isNull()
                        && Double.doubleToRawLongBits(v) == Double.doubleToRawLongBits(ProbeData.toDouble(want));
            } catch (AstException e) {
                got = e.getMessage();
                ok = want.isNull() && e.getStatus() == AstException.AST__AXIIN;
            }
            if (!ok) {
                failures.add(name + ": " + got + ", C gives " + want);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** test_pv reports only parameters that were set. */
    @Test
    void testPvReportsSetParameters() {
        WcsMap map = new WcsMap(2, WcsMap.AST__COP, 1, 2);
        assertFalse(map.test("PV2_1"));
        map.set("PV2_1=45");
        assertTrue(map.test("PV2_1"));
        assertFalse(map.test("PV1_1"));
    }
}
