package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_frameset_repair.rs}: removing a Frame keeps the paths
 * through its node, Base and Current after a removal, the FrameSet's Frame attributes forwarded
 * to its current Frame, simplification returning a FrameSet, and the definedness of the
 * base-to-current Mapping.
 *
 * <p>{@code frameset_simplified_returns_frameset} is translated as far as this version simplifies:
 * the result is a FrameSet with the same Frames; that the CmpMap of a ZoomMap and its inverse
 * becomes a UnitMap needs simplification.
 *
 * <p>{@code addFrameAllframesAppendsAxes} checks the FrameSet C's {@code AppendAxes} makes
 * (frameset.c {@code AddFrame} with {@code AST__ALLFRAMES}) by its Frames and transformation:
 * C simplifies each new CmpMap, which this version does not, so the Mappings are not compared.
 */
class FrameSetRepairTest {

    private static Frame named(int naxes, String domain) {
        Frame f = new Frame(naxes);
        f.setDomain(domain);
        return f;
    }

    /** FIRST -(Zoom 2)- SECOND -(Shift +1)- THIRD, all with one axis. */
    private static FrameSet threeFrameChain() {
        FrameSet fs = new FrameSet(named(1, "FIRST"));
        fs.addFrame(1, new ZoomMap(1, 2.0), named(1, "SECOND"));
        fs.addFrame(2, new ShiftMap(new double[] {1.0}), named(1, "THIRD"));
        return fs;
    }

    private static double fwd(Mapping m, double x) {
        return point(transformRows(m, true, new double[] {x}), 0)[0];
    }

    // RemoveFrame: graph connectivity and Base/Current reset

    @Test
    void removeMiddleFramePreservesConnectivity() {
        FrameSet fs = threeFrameChain();
        fs.removeFrame(2);
        assertEquals(2, fs.getNframe());
        assertEquals(7.0, fwd(fs.getMapping(1, 2), 3.0), 1e-12);
    }

    /**
     * Removing the Frame at node zero, from which two Frames are derived, joins their two
     * Mappings, the first used the other way, as C's {@code TidyNodes} does for node zero.
     */
    @Test
    void removeRootFrameJoinsItsTwoBranches() {
        FrameSet fs = new FrameSet(named(1, "ROOT"));
        fs.addFrame(1, new ZoomMap(1, 2.0), named(1, "LEFT"));
        fs.addFrame(1, new ShiftMap(new double[] {10.0}), named(1, "RIGHT"));
        fs.removeFrame(1);
        assertEquals(2, fs.getNframe());
        assertEquals("LEFT", fs.getFrame(1).getDomain());
        assertEquals(12.0, fwd(fs.getMapping(1, 2), 4.0), 1e-12);
        assertEquals(4.0, fwd(fs.getMapping(2, 1), 12.0), 1e-12);
    }

    @Test
    void removeLeafFrameKeepsRemainingChain() {
        FrameSet fs = threeFrameChain();
        fs.removeFrame(3);
        assertEquals(2, fs.getNframe());
        assertEquals(6.0, fwd(fs.getMapping(1, 2), 3.0), 1e-12);
    }

    @Test
    void removeCurrentFrameResetsCurrentToLast() {
        FrameSet fs = threeFrameChain();
        fs.setCurrent(2);
        fs.removeFrame(2);
        assertEquals("THIRD", fs.getDomain());
    }

    @Test
    void removeFrameAboveCurrentKeepsCurrent() {
        FrameSet fs = threeFrameChain();
        fs.setCurrent(2);
        fs.removeFrame(3);
        assertEquals("SECOND", fs.getDomain());
    }

    // Frame-method delegation to the current Frame

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    @Test
    void framesetDelegatesDomainButOwnsIdent() {
        SkyFrame skyFrame = sky("ICRS");
        skyFrame.setIdent("Q");
        FrameSet fs = new FrameSet(named(2, "PIXEL"));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), skyFrame);

        // C forwards GetDomain to the current Frame.
        assertEquals("SKY", fs.getDomain());

        // Ident is the FrameSet's own, not the current Frame's (frameset.c:4841 passes this).
        assertEquals("", fs.getIdent());
        fs.setIdent("FS");
        assertEquals("FS", fs.getIdent());
        assertEquals("Q", fs.getFrame(FrameSet.AST__CURRENT).getIdent());

        fs.setCurrent(fs.getBase());
        assertEquals("PIXEL", fs.getDomain());
        assertEquals("FS", fs.getIdent());
    }

    @Test
    void framesetDelegatesMatchControlAttributes() {
        FrameSet fs = new FrameSet(named(3, "PIXEL"));
        assertEquals(3, fs.getMinAxes());
        fs.setMinAxes(5);
        assertEquals(5, fs.getMinAxes());
        assertEquals(5, fs.getFrame(FrameSet.AST__CURRENT).getMinAxes());
    }

    /**
     * A FrameSet whose current Frame is a SkyFrame matches as a SkyFrame template (C forwards
     * astMatch to the current Frame): it matches a SkyFrame target but not a plain Frame target.
     */
    @Test
    void framesetTemplateMatchesLikeCurrentFrame() {
        FrameSet fs = new FrameSet(named(2, "PIXEL"));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), sky("ICRS"));

        assertNotNull(fs.match(sky("ICRS"), false), "FrameSet(SkyFrame) should match a SkyFrame");
        assertNull(fs.match(new Frame(2), false), "FrameSet(SkyFrame) must not match a plain Frame target");
    }

    // Simplify returns a FrameSet

    @Test
    void framesetSimplifiedReturnsFrameset() {
        ZoomMap z2 = new ZoomMap(1, 2.0);
        z2.invert();
        CmpMap cmp = new CmpMap(new ZoomMap(1, 2.0), z2, true);
        FrameSet fs = new FrameSet(named(1, "A"));
        fs.addFrame(FrameSet.AST__BASE, cmp, named(1, "B"));
        FrameSet sfs =
                assertInstanceOf(FrameSet.class, fs.simplify(), "astSimplify of a FrameSet must return a FrameSet");
        assertEquals(2, sfs.getNframe());
        assertEquals(3.0, fwd(sfs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT), 3.0), 1e-12);
    }

    // Transform definedness follows the base-to-current path

    @Test
    void framesetDefinednessFollowsPath() {
        PolyMap pm = new PolyMap(1, 1, 1, new double[] {1.0, 1.0, 2.0}, 0, null);
        pm.setIterInverse(false);
        FrameSet fs = new FrameSet(named(1, "A"));
        fs.addFrame(FrameSet.AST__BASE, pm, named(1, "B"));
        assertTrue(fs.getTranForward());
        assertFalse(fs.getTranInverse());
    }

    // Convert from a FrameSet tries every member Frame

    /**
     * Converting from a FrameSet tries every member Frame, not just the current one. Here the
     * current Frame (PIXEL) cannot align with a Galactic SkyFrame, but the base Frame (an FK5
     * SkyFrame) can. C's astConvert of the same FrameSet to a Galactic SkyFrame maps (0.1, 0.2)
     * rad to (1.951902536797, -0.886577028810) rad, given to 12 decimal places.
     */
    @Test
    void convertViaNoncurrentMemberFrame() {
        FrameSet fs = new FrameSet(sky("FK5"));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), named(2, "PIXEL"));

        FrameSet out = fs.convert(sky("GALACTIC"), "");
        assertNotNull(out, "FrameSet should convert to Galactic via its FK5 base member");
        Mapping m = out.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        assertTrue(m.getTranForward());
        assertTrue(m.getTranInverse());

        double[] res = point(transformRows(m, true, new double[] {0.1, 0.2}), 0);
        assertEquals(1.951902536797, res[0], 1e-9);
        assertEquals(-0.88657702881, res[1], 1e-9);
    }

    /** When the current Frame can align directly, converting still gives a usable result. */
    @Test
    void convertViaCurrentFrameStillWorks() {
        FrameSet fs = new FrameSet(named(2, "PIXEL"));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), sky("FK5"));

        FrameSet out = fs.convert(sky("GALACTIC"), "");
        assertNotNull(out, "current FK5 Frame converts to Galactic");
        Mapping m = out.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        assertTrue(m.getTranForward() && m.getTranInverse());
    }

    // AST__ALLFRAMES (frameset.c AddFrame and AppendAxes)

    /**
     * Adding with {@code AST__ALLFRAMES} appends the Frame's axes to every Frame of the set: each
     * Frame becomes a CmpFrame of itself and the Frame, and each Mapping a parallel CmpMap of
     * itself and a UnitMap. The Mapping given is ignored. In C, a 2-axis GRID to FPLANE WinMap
     * FrameSet widened by a 1-axis SPEC Frame maps (5, 5, 42) to (40, 40, 42).
     */
    @Test
    void addFrameAllframesAppendsAxes() {
        WinMap wm = new WinMap(
                2, new double[] {1.0, 1.0}, new double[] {10.0, 10.0}, new double[] {0.0, 0.0}, new double[] {90.0, 90.0
                });
        FrameSet fs = new FrameSet(named(2, "GRID"));
        fs.addFrame(FrameSet.AST__CURRENT, wm, named(2, "FPLANE"));

        fs.addFrame(FrameSet.AST__ALLFRAMES, new UnitMap(1), named(1, "SPEC"));

        assertEquals(2, fs.getNframe());
        assertEquals(3, fs.getNin());
        assertEquals(3, fs.getNout());
        assertEquals(
                3,
                assertInstanceOf(CmpFrame.class, fs.getFrame(FrameSet.AST__BASE))
                        .getNaxes());
        assertEquals(
                3,
                assertInstanceOf(CmpFrame.class, fs.getFrame(FrameSet.AST__CURRENT))
                        .getNaxes());

        double[] out = point(transformRows(fs, true, new double[] {5.0, 5.0, 42.0}), 0);
        assertTrue(Math.abs(out[0] - 40.0) < 1e-9, "axis 1 " + out[0]);
        assertTrue(Math.abs(out[1] - 40.0) < 1e-9, "axis 2 " + out[1]);
        assertTrue(Math.abs(out[2] - 42.0) < 1e-9, "axis 3 " + out[2]);
    }

    /** {@code AST__ALLFRAMES} is not the index of a Frame (frameset.c {@code ValidateFrameIndex}). */
    @Test
    void allframesRejectedAsSingleFrame() {
        FrameSet fs = threeFrameChain();
        AstException e = assertThrows(AstException.class, () -> fs.getFrame(FrameSet.AST__ALLFRAMES));
        assertEquals(AstException.AST__FRMIN, e.getStatus());
    }
}
