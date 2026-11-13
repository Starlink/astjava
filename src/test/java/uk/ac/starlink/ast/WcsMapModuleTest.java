package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The unit tests of Rust {@code src/mappings/wcsmap.rs}, and WcsMap's attribute strings, copying
 * and immutability as C's {@code wcsmap.c} defines them. Rust's {@code
 * inverse_longitudes_near_a_limit_are_decided_as_per_value} tests the redo of points whose
 * longitudes come from its vectorised kernels ({@code hardware SIMD}).
 */
class WcsMapModuleTest {

    private static WcsMap wcsmap(int type, double... pv) {
        WcsMap map = new WcsMap(2, type, 1, 2);
        for (int m = 0; m < pv.length; m++) {
            map.setPV(1, m, pv[m]);
        }
        return map;
    }

    /** SZP is cyclic for the parameter sets the projection tests use, so its inverse never tests the longitude. */
    @Test
    void szpIsCyclicSoItsLongitudeIsNotTested() {
        for (double[] pv : new double[][] {{}, {0.0, 2.0, 30.0, 60.0}, {0.0, 0.5, 0.0, -30.0}}) {
            assertFalse(wcsmap(WcsMap.AST__SZP, pv).lonRange().tested);
        }
        assertTrue(wcsmap(WcsMap.AST__AIT).lonRange().tested);
    }

    /** The attribute strings of wcsmap.c GetAttrib, formatted with %.15g and %d. */
    @Test
    void attributeStringsFollowC() {
        WcsMap map = new WcsMap(3, WcsMap.AST__COO, 2, 3);
        map.set("PV3_1=45, PV3_2 = 10");
        assertEquals("45", map.getC("PV3_1"));
        assertEquals("45", map.getC("ProjP(1)"));
        assertEquals("10", map.getC("pv3_2"));
        assertEquals("0", map.getC("PV2_0"));
        assertEquals("0", map.getC("PV2_1"));
        assertEquals("45", map.getC("PV2_2"));
        assertEquals("21", map.getC("WcsType"));
        assertEquals("2", map.getC("WcsAxis(1)"));
        assertEquals("3", map.getC("WcsAxis(2)"));
        assertEquals("0.785398163397448", map.getC("NatLat"));
        assertEquals("0", map.getC("NatLon"));
        assertEquals("4", map.getC("PVMax(2)"));
        assertEquals("2", map.getC("PVMax(3)"));
        assertEquals("-1", map.getC("PVMax(1)"));
        assertTrue(map.test("ProjP(1)"));
        assertFalse(map.test("WcsType"));
        map.set("ProjP(2)=5");
        assertEquals(5.0, map.getD("PV3_2"));
        map.clear("PV3_2");
        assertFalse(map.test("PV3_2"));
    }

    /** Read-only attributes raise AST__NOWRT on set and clear (wcsmap.c SetAttrib, ClearAttrib). */
    @Test
    void readOnlyAttributes() {
        WcsMap map = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        for (String name : new String[] {"WcsType", "NatLat", "NatLon", "WcsAxis(1)"}) {
            assertEquals(
                    AstException.AST__NOWRT,
                    assertThrows(AstException.class, () -> map.set(name + "=1")).getStatus(),
                    name);
            assertEquals(
                    AstException.AST__NOWRT,
                    assertThrows(AstException.class, () -> map.clear(name)).getStatus(),
                    name);
        }
    }

    /**
     * C's ClearAttrib tests for "prpjp(%d)" (wcsmap.c:1228), so a ProjP attribute cannot be
     * cleared: the name goes to the parent class, which does not know it.
     */
    @Test
    void projPCannotBeCleared() {
        WcsMap map = new WcsMap(2, WcsMap.AST__SIN, 1, 2);
        map.set("ProjP(1)=0.5");
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> map.clear("ProjP(1)")).getStatus());
        assertTrue(map.test("PV2_1"));
    }

    /** Projection parameters cannot change once the WcsMap is shared (wcsmap.c:1300, 3924); a copy can. */
    @Test
    void aSharedWcsMapRefusesParameterChanges() {
        WcsMap map = wcsmap(WcsMap.AST__ZPN, 0.0, 1.0);
        new CmpMap(map, new UnitMap(2), true);
        assertEquals(
                AstException.AST__IMMUT,
                assertThrows(AstException.class, () -> map.set("PV2_2=0.1")).getStatus());
        assertEquals(
                AstException.AST__IMMUT,
                assertThrows(AstException.class, () -> map.clear("PV2_1")).getStatus());
        WcsMap copy = (WcsMap) map.copy();
        copy.set("PV2_2=0.1");
        assertEquals(0.1, copy.getD("PV2_2"));
        assertFalse(map.test("PV2_2"));
    }

    /** A copy has its own parameters (wcsmap.c Copy). */
    @Test
    void aCopyHasItsOwnParameters() {
        WcsMap map = wcsmap(WcsMap.AST__AZP, 0.0, 2.0);
        WcsMap copy = (WcsMap) map.copy();
        copy.set("PV2_1=3");
        assertEquals(2.0, map.getD("PV2_1"));
        assertEquals(3.0, copy.getD("PV2_1"));
    }

    /** TPNTan cleared makes a TPN WcsMap work in degrees (wcsmap.c Map). */
    @Test
    void tpnTanClearedWorksInDegrees() {
        WcsMap map = new WcsMap(2, WcsMap.AST__TPN, 1, 2);
        map.setTPNTan(false);
        double[][] out = map.transform(new double[][] {{0.0}, {60.0}}, 1, true);
        assertEquals(0.0, out[0][0], 1e-10);
        assertEquals(60.0, out[1][0], 1e-10);
        String dump = ChannelText.dump(map);
        assertTrue(dump.contains("TpnTan = 0"));
        WcsMap back = (WcsMap) ChannelText.read(dump);
        assertFalse(back.getTPNTan());
    }
}
