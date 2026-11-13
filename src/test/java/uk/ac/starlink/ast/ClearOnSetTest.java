package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_clear_on_set.rs}: a setter for an attribute C covers
 * discards both records of simplification ({@code mapping.h:449-460}). A record says that the
 * Mapping has already been simplified in the orientation it is now in; it can only ever suppress a
 * simplification, so C errs towards discarding it.
 *
 * <p>Each case records the flag on the Mapping, checks it is there, calls the setter and checks it
 * is gone. {@link #recordSurvivesInvertAndBack} is the control: inverting is not covered, so the
 * flag must survive it. The loader re-applies attributes through the same setters, which discard a
 * record only when the value changes, so a dumped {@code IsSimp} card survives a read.
 *
 * <p>The cases of LutMap, MathMap, SplineMap, PcdMap and GrismMap are listed in {@code
 * deferred/rust-tests.txt} (out of scope), and the same classes are left out of {@link
 * #loadPreservesTheRecordForEveryClearOnSetClass}.
 */
class ClearOnSetTest {

    /** Records the flag on {@code m}, runs {@code mutate}, and requires the flag to be gone. */
    private static <M extends Mapping> void assertDiscards(M m, String what, Consumer<M> mutate) {
        m.setIsSimple();
        assertTrue(m.getIsSimple(), what + ": the class does not record the simplified flag at all");
        mutate.accept(m);
        assertFalse(m.getIsSimple(), what + ": the simplified record survived the set");
    }

    private static PolyMap polyMap() {
        return new PolyMap(1, 1, 1, new double[] {2.0, 1, 1}, 0, null);
    }

    private static PermMap permMap() {
        return new PermMap(2, new int[] {1, 2}, 2, new int[] {1, 2}, null);
    }

    private static WcsMap wcsMap() {
        return new WcsMap(2, WcsMap.AST__TAN, 1, 2);
    }

    @Test
    void polyIterInverseDiscardsTheRecord() {
        assertDiscards(polyMap(), "PolyMap.setIterInverse", m -> m.setIterInverse(true));
    }

    @Test
    void clearingPolyIterInverseDiscardsTheRecord() {
        assertDiscards(polyMap(), "PolyMap.clearIterInverse", PolyMap::clearIterInverse);
    }

    @Test
    void polyNiterInverseDiscardsTheRecord() {
        assertDiscards(polyMap(), "PolyMap.setNiterInverse", m -> m.setNiterInverse(20));
    }

    @Test
    void polyTolInverseDiscardsTheRecord() {
        assertDiscards(polyMap(), "PolyMap.setTolInverse", m -> m.setTolInverse(1.0e-8));
    }

    @Test
    void sphUnitRadiusDiscardsTheRecord() {
        assertDiscards(new SphMap(), "SphMap.setUnitRadius", m -> m.setUnitRadius(true));
    }

    @Test
    void sphPolarLongDiscardsTheRecord() {
        assertDiscards(new SphMap(), "SphMap.setPolarLong", m -> m.setPolarLong(1.0));
    }

    @Test
    void wcsLonCheckDiscardsTheRecord() {
        assertDiscards(wcsMap(), "WcsMap.setLonCheck", m -> m.setLonCheck(false));
    }

    @Test
    void wcsFitsProjDiscardsTheRecord() {
        assertDiscards(wcsMap(), "WcsMap.setFITSProj", m -> m.setFITSProj(false));
    }

    @Test
    void permSplitDiscardsTheRecord() {
        assertDiscards(permMap(), "PermMap.setPermSplit", m -> m.setPermSplit(true));
    }

    @Test
    void clearingPermSplitDiscardsTheRecord() {
        assertDiscards(permMap(), "PermMap.clearPermSplit", PermMap::clearPermSplit);
    }

    @Test
    void zoomDiscardsTheRecord() {
        assertDiscards(new ZoomMap(2, 3.0), "ZoomMap.setZoom", m -> m.setZoom(4.0));
    }

    /**
     * C leaves the Zoom unchanged when a set of zero is rejected ({@code zoommap.c:1706-1710}
     * evaluates to {@code this->zoom} after the {@code astError}), but {@code astClearIsSimple} is
     * the first term of the same comma expression, so the records go whether the assignment
     * happens or not.
     */
    @Test
    void rejectedZoomStillDiscardsTheRecord() {
        ZoomMap m = new ZoomMap(2, 3.0);
        m.setIsSimple();
        AstException err = assertThrows(AstException.class, () -> m.setZoom(0.0));
        assertEquals(AstException.AST__ZOOMI, err.getStatus());
        assertEquals(3.0, m.getZoom(), "the rejected set changed the zoom factor");
        assertFalse(m.getIsSimple(), "the rejected set kept the simplified record");
    }

    /**
     * The control. Invert is not an attribute C covers: inverting a Mapping changes which record
     * applies, and inverting it back restores the original one ({@code mapping.h:424-437}).
     */
    @Test
    void recordSurvivesInvertAndBack() {
        SphMap m = new SphMap();
        m.setIsSimple();
        m.invert();
        assertFalse(m.getIsSimple(), "the inverted orientation carries its own record, which is unset");
        m.invert();
        assertTrue(m.getIsSimple(), "inverting and inverting back lost the record");
    }

    /**
     * Records the flag on {@code m}, writes it to native text, reads it back, and requires the flag
     * to have survived. C's loader re-applies a validated attribute through the private setter,
     * which discards the record only when the value differs, and the value re-applied is the one
     * just read; {@code m} must carry an explicitly set attribute for the case to prove anything.
     */
    private static void assertLoadPreserves(Mapping m, String what) {
        m.setIsSimple();
        String text = ChannelText.dump(m);
        assertTrue(text.contains("IsSimp"), what + ": the dump carries no IsSimp card, so the case proves nothing");
        Mapping loaded = (Mapping) ChannelText.read(text);
        assertTrue(loaded.getIsSimple(), what + ": a no-op set inside the loader discarded the simplified record");
    }

    /** The control: a class with no clear-on-set attribute routes nothing through a setter. */
    @Test
    void loadKeepsTheRecordForAClassThatRoutesNothing() {
        ShiftMap m = new ShiftMap(new double[] {1.0, 2.0});
        m.setIsSimple();
        Mapping back = (Mapping) ChannelText.read(ChannelText.dump(m));
        assertTrue(back.getIsSimple(), "ShiftMap: the card was discarded by a class that clears nothing");
    }

    @Test
    void loadPreservesTheRecordForEveryClearOnSetClass() {
        ZoomMap zoom = new ZoomMap(2, 3.0);
        zoom.setIsSimple();
        assertLoadPreserves(zoom, "ZoomMap Zoom");

        PolyMap poly = polyMap();
        poly.setIterInverse(true);
        poly.setNiterInverse(7);
        poly.setTolInverse(1e-8);
        assertLoadPreserves(poly, "PolyMap IterInverse");

        SphMap sph = new SphMap();
        sph.setUnitRadius(true);
        sph.setPolarLong(0.5);
        assertLoadPreserves(sph, "SphMap UnitRadius");

        WcsMap wcs = wcsMap();
        wcs.setFITSProj(true);
        wcs.setLonCheck(true);
        assertLoadPreserves(wcs, "WcsMap FITSProj");

        PermMap perm = permMap();
        perm.setPermSplit(true);
        assertLoadPreserves(perm, "PermMap PermSplit");
    }
}
