package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The string-based access to the Mapping and ZoomMap attributes, which has no Rust counterpart:
 * names and errors as C's {@code GetAttrib}, {@code SetAttrib}, {@code TestAttrib} and {@code
 * ClearAttrib} (mapping.c, zoommap.c) give them.
 */
class MappingAttributeTest {

    @Test
    void theReadOnlyAttributesDescribeTheMapping() {
        ZoomMap zoom = new ZoomMap(2, 2.0);
        assertEquals(2, zoom.getI("Nin"));
        assertEquals(2, zoom.getI(" NOUT "));
        assertTrue(zoom.getB("IsLinear"));
        assertFalse(zoom.getB("IsSimple"));
        assertTrue(zoom.getB("TranForward"));
        assertTrue(zoom.getB("TranInverse"));
        assertFalse(new NoInverseMap().getB("TranInverse"));
        for (String name : new String[] {"Nin", "Nout", "IsLinear", "IsSimple", "TranForward", "TranInverse"}) {
            assertFalse(zoom.test(name), name);
            assertEquals(
                    AstException.AST__NOWRT,
                    assertThrows(AstException.class, () -> zoom.set(name + "=1"))
                            .getStatus(),
                    name);
            assertEquals(
                    AstException.AST__NOWRT,
                    assertThrows(AstException.class, () -> zoom.clear(name)).getStatus(),
                    name);
        }
    }

    @Test
    void invertExchangesTheCountsAndDirections() {
        Mapping map = new NoInverseMap();
        assertFalse(map.test("Invert"));
        map.set("Invert=1");
        assertTrue(map.getInvert());
        assertFalse(map.getTranForward());
        assertTrue(map.getTranInverse());
        // Inverting a Mapping set to be inverted leaves Invert unset, as C's astInvert does.
        map.invert();
        assertFalse(map.getInvert());
        assertFalse(map.test("Invert"));
        map.invert();
        assertEquals(1, map.getI("Invert"));
        assertTrue(map.test("Invert"));
        map.clear("Invert");
        assertFalse(map.getInvert());
        // Setting Invert to 0 still sets it.
        map.setInvert(false);
        assertTrue(map.test("Invert"));
    }

    @Test
    void reportIsAnOrdinaryAttribute() {
        UnitMap map = new UnitMap(1);
        assertFalse(map.test("Report"));
        assertFalse(map.getReport());
        map.set("Report = 1");
        assertTrue(map.getReport());
        assertEquals("1", map.getC("report"));
        map.clear("Report");
        assertFalse(map.test("Report"));
    }

    @Test
    void zoomIsReadAndWrittenAsC() {
        ZoomMap zoom = new ZoomMap(1, 0.1);
        // C formats it with %.17g (zoommap.c GetAttrib).
        assertEquals("0.10000000000000001", zoom.getC("Zoom"));
        zoom.set("Zoom= 2.5 ");
        assertEquals(2.5, zoom.getD("zoom"));
        assertEquals(
                AstException.AST__ZOOMI,
                assertThrows(AstException.class, () -> zoom.set("Zoom=0")).getStatus());
        assertEquals(2.5, zoom.getZoom(), "a rejected zero leaves the factor unchanged");
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> zoom.set("Zoom=abc")).getStatus());
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> new UnitMap(1).getC("Zoom"))
                        .getStatus());
    }

    @Test
    void simplifyRecordsTheMappingAsSimplified() {
        ZoomMap zoom = new ZoomMap(1, 2.0);
        assertSame(zoom, zoom.simplify(), "the Mapping itself is returned");
        assertTrue(zoom.getB("IsSimple"));
        // Each orientation has its own record, which inverting does not discard.
        zoom.invert();
        assertFalse(zoom.getB("IsSimple"));
        zoom.invert();
        assertTrue(zoom.getB("IsSimple"));
        // A new Zoom discards the record; the same Zoom does not.
        zoom.setZoom(2.0);
        assertTrue(zoom.getB("IsSimple"));
        zoom.setZoom(3.0);
        assertFalse(zoom.getB("IsSimple"));
        // A Mapping with Ident set is left alone (mapping.c astDoNotSimplify).
        UnitMap unit = new UnitMap(1);
        unit.setIdent("");
        unit.simplify();
        assertFalse(unit.getB("IsSimple"));
    }

    @Test
    void negativeCountsAreRejected() {
        assertEquals(
                AstException.AST__BADNI,
                assertThrows(AstException.class, () -> new UnitMap(-1)).getStatus());
    }

    @Test
    void resamplingIsUnavailable() {
        UnitMap map = new UnitMap(1);
        assertThrows(
                UnsupportedOperationException.class,
                () -> map.resampleD(
                        1,
                        new int[] {1},
                        new int[] {2},
                        new double[2],
                        null,
                        Mapping.LINEAR_INTERPOLATOR,
                        new ResampleFlags(),
                        0.0,
                        100,
                        AstObject.AST__BAD,
                        1,
                        new int[] {1},
                        new int[] {2},
                        new int[] {1},
                        new int[] {2},
                        new double[2],
                        null));
    }

    @Test
    void resampleFlagsCombineAsC() {
        ResampleFlags f = new ResampleFlags();
        assertEquals(0, f.getFlagsInt());
        f.setUseBad(true);
        f.setConserveFlux(true);
        f.setNoBad(true);
        // AST__USEBAD, AST__CONSERVEFLUX and AST__NOBAD (mapping.h).
        assertEquals(32 | 64 | 2048, f.getFlagsInt());
    }
}
