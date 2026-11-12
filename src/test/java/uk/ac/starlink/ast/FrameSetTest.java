package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_frameset.rs}: building a FrameSet, adding, removing and
 * remapping Frames, the Mappings between its Frames, the FrameSet as a Frame and as a Mapping,
 * inversion, and the Base and Current attributes, against C's {@code frameset.c}. Rust's {@code
 * FrameRef::Base}, {@code FrameRef::Current} and {@code FrameRef::Index(i)} are {@link
 * FrameSet#AST__BASE}, {@link FrameSet#AST__CURRENT} and {@code i}; Rust's {@code
 * set_base_by_domain} and {@code set_current_by_domain} are settings of Base and Current by
 * Domain name, as C reads them.
 *
 * <p>{@code new_given_an_inverted_frameset_stays_inverted} compares the dump with that of the
 * same FrameSet built and inverted, as C's {@code astInitFrameSet} takes the stored Base, Current
 * and Invert, rather than with the Rust repository's dump.
 *
 * <p>The tests that need {@code astConvert}, {@code astFindFrame} or {@code astAddVariant}, which
 * calls {@code astConvert}, are translated in Task 17: {@code
 * new_given_a_frameset_takes_its_variants_and_mirrors}, {@code with_integrity_noop_for_basicframe},
 * {@code convert_same_naxes_succeeds}, {@code convert_different_naxes_returns_none}, {@code
 * frameset_convert_delegates_to_current}, {@code add_variant_succeeds}, {@code
 * find_frame_stub_returns_none}, {@code
 * convert_takes_an_inverted_framesets_other_frames_in_descending_order}, {@code
 * convert_sets_the_base_of_a_frameset_argument_to_the_frame_used}, {@code
 * failed_convert_leaves_the_base_alone} and {@code find_frame_makes_the_frame_matched_current}.
 */
class FrameSetTest {

    private static final int BASE = FrameSet.AST__BASE;
    private static final int CURRENT = FrameSet.AST__CURRENT;

    private static Frame frame(int naxes) {
        return new Frame(naxes);
    }

    private static Frame named(int naxes, String domain) {
        Frame f = new Frame(naxes);
        f.setDomain(domain);
        return f;
    }

    /** A Mapping from 2 axes to 3, appending a constant third coordinate. */
    private static Mapping twoToThree() {
        return new PermMap(2, new int[] {1, 2}, 3, new int[] {1, 2, -1}, new double[] {0.0});
    }

    /** The forward transformation of one point. */
    private static double[] fwd(Mapping m, double... in) {
        return point(transformRows(m, true, in), 0);
    }

    /** The inverse transformation of one point. */
    private static double[] inv(Mapping m, double... in) {
        return point(transformRows(m, false, in), 0);
    }

    private static int status(Runnable r) {
        return assertThrows(AstException.class, r::run).getStatus();
    }

    // ---- Construction ----

    @Test
    void newFramesetSingleFrame() {
        FrameSet fs = new FrameSet(frame(2));
        assertEquals(1, fs.getNframe());
        assertEquals(2, fs.getFrame(BASE).getNaxes());
        assertEquals(2, fs.getFrame(CURRENT).getNaxes());
    }

    /** Frames X, Y and Z, with X to Y a ZoomMap of 2 and Y to Z a ShiftMap of (1, 2). */
    private static FrameSet xyzUnnamed() {
        FrameSet fs = new FrameSet(named(2, "X"));
        fs.addFrame(1, new ZoomMap(2, 2.0), named(2, "Y"));
        fs.addFrame(2, new ShiftMap(new double[] {1.0, 2.0}), named(2, "Z"));
        return fs;
    }

    /** {@link #xyzUnnamed} with Ident "orig". */
    private static FrameSet xyz() {
        FrameSet fs = xyzUnnamed();
        fs.setIdent("orig");
        return fs;
    }

    @Test
    void newGivenAFramesetTakesItsFramesAndMappings() {
        FrameSet fs = new FrameSet(xyz());
        assertEquals(3, fs.getNframe());
        assertEquals(1, fs.getBase());
        assertEquals(3, fs.getCurrent());
        assertFalse(fs.testBase());
        assertTrue(fs.testCurrent());
        assertFalse(fs.testInvert());
        assertEquals("Z", fs.getDomain());
        assertEquals("", fs.getIdent());
        assertEquals(ChannelText.dump(xyzUnnamed()), ChannelText.dump(fs));

        FrameSet old = xyz();
        old.setCurrent(2);
        old.setBase(3);
        fs = new FrameSet(old);
        assertEquals(3, fs.getBase());
        assertEquals(2, fs.getCurrent());
        assertTrue(fs.testBase() && fs.testCurrent());
        assertEquals("Y", fs.getDomain());

        old = xyz();
        old.setInvert(false);
        assertTrue(new FrameSet(old).testInvert());
    }

    @Test
    void newGivenAnInvertedFramesetStaysInverted() {
        FrameSet old = xyz();
        old.invert();
        FrameSet fs = new FrameSet(old);
        assertEquals(3, fs.getNframe());
        assertTrue(fs.getInvert());
        assertEquals(3, fs.getBase());
        assertEquals(1, fs.getCurrent());
        assertTrue(fs.testBase());
        assertFalse(fs.testCurrent());
        assertEquals("X", fs.getDomain());
        assertArrayEquals(new double[] {0.0, -0.5}, fwd(fs, 1.0, 1.0));
        FrameSet built = xyzUnnamed();
        built.invert();
        assertEquals(ChannelText.dump(built), ChannelText.dump(fs));
    }

    @Test
    void baseAndCurrentAreSameInitially() {
        FrameSet fs = new FrameSet(named(3, "PIXEL"));
        assertEquals(3, fs.getNin());
        assertEquals(3, fs.getNout());
    }

    // ---- addFrame ----

    @Test
    void addFrameIncreasesCount() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(CURRENT, new ZoomMap(2, 2.0), frame(2));
        assertEquals(2, fs.getNframe());
    }

    @Test
    void addFrameSetsCurrent() {
        FrameSet fs = new FrameSet(named(2, "PIXEL"));
        fs.addFrame(BASE, twoToThree(), named(3, "GRID"));
        assertEquals(3, fs.getFrame(CURRENT).getNaxes());
        assertEquals(2, fs.getFrame(BASE).getNaxes());
    }

    // ---- Mapping composition ----

    @Test
    void mappingBaseToCurrentZoom() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 3.0), frame(2));
        assertArrayEquals(new double[] {3.0, 6.0}, fwd(fs.getMapping(BASE, CURRENT), 1.0, 2.0));
    }

    @Test
    void mappingCurrentToBaseInverts() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 3.0), frame(2));
        double[] out = fwd(fs.getMapping(CURRENT, BASE), 3.0, 6.0);
        assertEquals(1.0, out[0], 1e-10);
        assertEquals(2.0, out[1], 1e-10);
    }

    @Test
    void mappingSameFrameIsUnit() {
        FrameSet fs = new FrameSet(frame(2));
        assertArrayEquals(new double[] {7.0, 8.0}, fwd(fs.getMapping(BASE, CURRENT), 7.0, 8.0));
    }

    @Test
    void mappingChainComposes() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(CURRENT, new ZoomMap(2, 2.0), frame(2));
        fs.addFrame(CURRENT, new ShiftMap(new double[] {10.0, 20.0}), frame(2));
        assertEquals(3, fs.getNframe());
        assertArrayEquals(new double[] {12.0, 24.0}, fwd(fs.getMapping(BASE, CURRENT), 1.0, 2.0));
    }

    @Test
    void mappingByIndex() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(CURRENT, new ZoomMap(2, 2.0), frame(2));
        fs.addFrame(CURRENT, new ShiftMap(new double[] {10.0, 10.0}), frame(2));
        assertArrayEquals(new double[] {10.0, 10.0}, fwd(fs.getMapping(1, 2), 5.0, 5.0));
        assertArrayEquals(new double[] {15.0, 15.0}, fwd(fs.getMapping(2, 3), 5.0, 5.0));
    }

    // ---- Tree topology ----

    @Test
    void treeTopologyPathComposition() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(1, new ZoomMap(2, 2.0), frame(2));
        fs.addFrame(1, new ShiftMap(new double[] {10.0, 10.0}), frame(2));
        assertEquals(3, fs.getNframe());
        double[] out = fwd(fs.getMapping(2, 3), 4.0, 6.0);
        assertEquals(12.0, out[0], 1e-10);
        assertEquals(13.0, out[1], 1e-10);
    }

    // ---- FrameSet as Mapping ----

    @Test
    void framesetTransformForward() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 5.0), frame(2));
        assertEquals(2, fs.getNin());
        assertEquals(2, fs.getNout());
        assertArrayEquals(new double[] {10.0, 15.0}, fwd(fs, 2.0, 3.0));
    }

    @Test
    void framesetTransformInverse() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 5.0), frame(2));
        double[] out = inv(fs, 10.0, 15.0);
        assertEquals(2.0, out[0], 1e-10);
        assertEquals(3.0, out[1], 1e-10);
    }

    @Test
    void framesetTransformMultiPoint() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ShiftMap(new double[] {1.0, -1.0}), frame(2));
        double[][] out = transformRows(
                fs, true, new double[] {0.0, 0.0}, new double[] {10.0, 20.0}, new double[] {100.0, 200.0});
        assertArrayEquals(new double[] {1.0, -1.0}, point(out, 0));
        assertArrayEquals(new double[] {11.0, 19.0}, point(out, 1));
        assertArrayEquals(new double[] {101.0, 199.0}, point(out, 2));
    }

    // ---- Frame delegation ----

    @Test
    void frameDelegationNaxes() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, twoToThree(), frame(3));
        assertEquals(3, fs.getNaxes());
    }

    @Test
    void frameDelegationDistance() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 2.0), frame(2));
        assertEquals(5.0, fs.distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}), 1e-10);
    }

    @Test
    void frameDelegationFormat() {
        FrameSet fs = new FrameSet(frame(1));
        fs.addFrame(BASE, new UnitMap(1), frame(1));
        assertTrue(fs.format(1, 3.14).contains("3.14"));
    }

    // ---- setBase / setCurrent ----

    @Test
    void setCurrentChangesNaxes() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, twoToThree(), frame(3));
        assertEquals(3, fs.getNaxes());
        fs.setCurrent(1);
        assertEquals(2, fs.getNaxes());
    }

    @Test
    void setBaseChangesNin() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, twoToThree(), frame(3));
        assertEquals(2, fs.getNin());
        fs.setBase(CURRENT);
        assertEquals(3, fs.getNin());
    }

    @Test
    void setCurrentInvalidIndex() {
        FrameSet fs = new FrameSet(frame(2));
        // Index 0 is AST__BASE here, as in C, so -2 stands for Rust's invalid index 0.
        assertEquals(AstException.AST__FRMIN, status(() -> fs.getFrame(-2)));
        assertEquals(AstException.AST__FRMIN, status(() -> fs.getFrame(2)));
    }

    // ---- getFrame ----

    @Test
    void frameAccessByRef() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, twoToThree(), frame(3));
        assertEquals(2, fs.getFrame(1).getNaxes());
        assertEquals(3, fs.getFrame(2).getNaxes());
        assertEquals(2, fs.getFrame(BASE).getNaxes());
        assertEquals(3, fs.getFrame(CURRENT).getNaxes());
    }

    /** The Frame {@code getFrame} returns is the FrameSet's own, as C's {@code astGetFrame} clones it. */
    @Test
    void frameMutEscapeHatch() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 2.0), frame(2));
        Frame f = fs.getFrame(CURRENT);
        assertEquals(2, f.getNaxes());
        f.setDomain("CHANGED");
        assertEquals("CHANGED", fs.getDomain());
        assertArrayEquals(new double[] {2.0, 4.0}, fwd(fs, 1.0, 2.0));
    }

    // ---- Integrity ----

    /** A setting the current Frame rejects is an error, which leaves the FrameSet as it was. */
    @Test
    void withIntegrityErrorPropagates() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 2.0), frame(2));
        String before = ChannelText.dump(fs);
        assertEquals(AstException.AST__BADAT, status(() -> fs.set("Digits=many")));
        assertEquals(before, ChannelText.dump(fs));
        assertArrayEquals(new double[] {2.0, 4.0}, fwd(fs, 1.0, 2.0));
    }

    // ---- removeFrame ----

    @Test
    void removeFrameReducesCount() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(CURRENT, new ZoomMap(2, 2.0), frame(2));
        fs.addFrame(CURRENT, new ShiftMap(new double[] {10.0, 20.0}), frame(2));
        assertEquals(3, fs.getNframe());
        fs.removeFrame(2);
        assertEquals(2, fs.getNframe());
    }

    @Test
    void removeLastFrameFails() {
        FrameSet fs = new FrameSet(frame(2));
        assertEquals(AstException.AST__REMIN, status(() -> fs.removeFrame(BASE)));
    }

    @Test
    void removeCurrentResetsToDefault() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, twoToThree(), frame(3));
        assertEquals(3, fs.getNaxes());
        fs.removeFrame(CURRENT);
        assertEquals(1, fs.getNframe());
        assertEquals(2, fs.getNaxes());
    }

    @Test
    void removeCurrentMakesTheLastFrameCurrent() {
        FrameSet fs = new FrameSet(named(2, "A"));
        for (String d : new String[] {"B", "C"}) {
            fs.addFrame(BASE, new UnitMap(2), named(2, d));
        }
        fs.setCurrent(2);
        fs.removeFrame(CURRENT);
        assertFalse(fs.testCurrent());
        assertEquals("C", fs.getFrame(CURRENT).getDomain());
    }

    // ---- remapFrame ----

    @Test
    void remapFrameAppendsDelta() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 2.0), frame(2));
        assertArrayEquals(new double[] {2.0, 4.0}, fwd(fs, 1.0, 2.0));
        fs.remapFrame(CURRENT, new ShiftMap(new double[] {5.0, 5.0}));
        assertArrayEquals(new double[] {7.0, 9.0}, fwd(fs, 1.0, 2.0));
    }

    /**
     * Remapping a Frame leaves the Current attribute set, since C's {@code RemapFrame} makes the
     * Frame current while it removes its variant Mappings and then sets Current back to the Frame
     * that was current (frameset.c:8610-8619).
     */
    @Test
    void remapFrameLeavesCurrentSet() {
        FrameSet fs = new FrameSet(frame(1));
        fs.addFrame(BASE, new ZoomMap(1, 2.0), frame(1));
        fs.clearCurrent();
        assertFalse(fs.testCurrent());
        fs.remapFrame(1, new ZoomMap(1, 3.0));
        assertTrue(fs.testCurrent());
        assertEquals(2, fs.getCurrent());
        assertArrayEquals(new double[] {2.0 / 3.0}, fwd(fs, 1.0));
    }

    // ---- invert ----

    @Test
    void invertSwapsDirection() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 3.0), frame(2));
        assertArrayEquals(new double[] {6.0, 12.0}, fwd(fs, 2.0, 4.0));
        fs.invert();
        double[] out = fwd(fs, 2.0, 4.0);
        assertEquals(2.0 / 3.0, out[0], 1e-10);
        assertEquals(4.0 / 3.0, out[1], 1e-10);
    }

    @Test
    void doubleInvertIsIdentity() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 7.0), frame(2));
        fs.invert();
        fs.invert();
        assertArrayEquals(new double[] {7.0, 14.0}, fwd(fs, 1.0, 2.0));
    }

    // ---- simplify ----

    @Test
    void simplifiedReturnsBaseToCurrentMapping() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 4.0), frame(2));
        assertArrayEquals(new double[] {8.0, 12.0}, fwd(fs.simplify(), 2.0, 3.0));
    }

    // ---- Edge cases ----

    @Test
    void singleFrameTransformIsIdentity() {
        FrameSet fs = new FrameSet(frame(2));
        assertArrayEquals(new double[] {1.0, 2.0}, fwd(fs, 1.0, 2.0));
    }

    @Test
    void transformIntoPreallocated() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ShiftMap(new double[] {1.0, 2.0}), frame(2));
        double[][] out = {{0.0}, {0.0}};
        fs.transformInto(new double[][] {{10.0}, {20.0}}, 1, true, out);
        assertArrayEquals(new double[] {11.0, 22.0}, point(out, 0));
    }

    @Test
    void rateOnFrameset() {
        FrameSet fs = new FrameSet(frame(2));
        fs.addFrame(BASE, new ZoomMap(2, 3.0), frame(2));
        assertEquals(3.0, fs.rate(new double[] {1.0, 1.0}, 1, 1), 1e-6);
        assertEquals(0.0, fs.rate(new double[] {1.0, 1.0}, 1, 2), 1e-6);
    }

    /**
     * Adding a FrameSet re-roots its tree at its current Frame, as C's {@code astAddFrame} does,
     * reversing the link on the way, which a native dump records as {@code Inv<n>}; reading the
     * dump back gives the same transformation.
     */
    @Test
    void addFramesetReversesLinksAndRoundTrips() {
        FrameSet outer = new FrameSet(named(1, "GRID"));
        FrameSet inner = new FrameSet(named(1, "A"));
        inner.addFrame(BASE, new ShiftMap(new double[] {10.0}), named(1, "B"));
        outer.addFrame(BASE, new ZoomMap(1, 2.0), inner);
        assertEquals(3, outer.getNframe());
        assertEquals("B", outer.getFrame(CURRENT).getDomain());

        String text = ChannelText.dump(outer);
        assertTrue(text.contains("    Inv2 = 1 \t"), text);
        FrameSet back = assertInstanceOf(FrameSet.class, ChannelText.read(text));
        for (FrameSet fs : new FrameSet[] {outer, back}) {
            assertEquals(-4.0, fwd(fs.getMapping(1, 2), 3.0)[0]);
        }
    }

    @Test
    void addFrameRejectsAMappingOfTheWrongSize() {
        FrameSet fs = new FrameSet(named(2, "PIX"));
        assertEquals(AstException.AST__NCPIN, status(() -> fs.addFrame(BASE, new ZoomMap(3, 2.0), frame(3))));
        assertEquals(AstException.AST__NCPIN, status(() -> fs.addFrame(BASE, new ZoomMap(2, 2.0), frame(3))));
        assertEquals(1, fs.getNframe());
    }

    @Test
    void addFrameWithAFramesetMergesIt() {
        FrameSet a = new FrameSet(named(2, "PIX"));
        a.addFrame(BASE, new ZoomMap(2, 2.0), named(2, "SKYX"));
        FrameSet c = new FrameSet(named(2, "GRID"));
        c.addFrame(BASE, new ShiftMap(new double[] {1.0, 1.0}), a);
        assertEquals(3, c.getNframe());
        assertEquals("SKYX", c.getFrame(3).getDomain());
        assertEquals("SKYX", c.getFrame(CURRENT).getDomain());
        assertArrayEquals(new double[] {2.0, 2.0}, fwd(c, 1.0, 1.0));
        assertArrayEquals(new double[] {1.0, 1.0}, fwd(c.getMapping(1, 2), 1.0, 1.0));

        FrameSet m = new FrameSet(frame(2));
        m.addFrame(BASE, new ZoomMap(2, 3.0), frame(2));
        FrameSet d = new FrameSet(named(2, "GRID"));
        d.addFrame(BASE, m, named(2, "Q"));
        Mapping map = d.getMapping(BASE, CURRENT);
        assertFalse(map instanceof FrameSet);
        assertArrayEquals(new double[] {3.0, 3.0}, fwd(map, 1.0, 1.0));
    }

    /** C's dump of an inverted FrameSet: A -ZoomMap 2-> B, then {@code astInvert}. */
    private static final String C_INVERTED_FRAMESET = ChannelText.text(
            " Begin FrameSet \t# Set of inter-related coordinate systems",
            "    Invert = 1 \t# Mapping inverted",
            " IsA Mapping \t# Mapping between coordinate systems",
            "#   Title = \"1-d coordinate system\" \t# Title of coordinate system",
            "#   Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"A\" \t# Coordinate system domain",
            "#   Lbl1 = \"Axis 1\" \t# Label for axis 1",
            " IsA Frame \t# Coordinate system description",
            "    Nframe = 2 \t# Number of Frames in FrameSet",
            "#   Base = 1 \t# Index of base Frame",
            "    Currnt = 2 \t# Index of current Frame", // codespell:ignore currnt
            "    Lnk2 = 1 \t# Node 2 is derived from node 1",
            "    Frm1 = \t# Frame number 1",
            "       Begin Frame \t# Coordinate system description",
            "#         Title = \"1-d coordinate system\" \t# Title of coordinate system",
            "          Naxes = 1 \t# Number of coordinate axes",
            "          Domain = \"A\" \t# Coordinate system domain",
            "#         Lbl1 = \"Axis 1\" \t# Label for axis 1",
            "          Ax1 = \t# Axis number 1",
            "             Begin Axis \t# Coordinate axis",
            "             End Axis",
            "       End Frame",
            "    Frm2 = \t# Frame number 2",
            "       Begin Frame \t# Coordinate system description",
            "#         Title = \"1-d coordinate system\" \t# Title of coordinate system",
            "          Naxes = 1 \t# Number of coordinate axes",
            "          Domain = \"B\" \t# Coordinate system domain",
            "#         Lbl1 = \"Axis 1\" \t# Label for axis 1",
            "          Ax1 = \t# Axis number 1",
            "             Begin Axis \t# Coordinate axis",
            "             End Axis",
            "       End Frame",
            "    Map2 = \t# Mapping between nodes 1 and 2",
            "       Begin ZoomMap \t# Zoom about the origin",
            "          Nin = 1 \t# Number of input coordinates",
            "       IsA Mapping \t# Mapping between coordinate systems",
            "          Zoom = 2 \t# Zoom factor",
            "       End ZoomMap",
            " End FrameSet");

    @Test
    void anInvertedFramesetReadsAndWritesAsCDoes() {
        FrameSet back = assertInstanceOf(FrameSet.class, ChannelText.read(C_INVERTED_FRAMESET));
        assertTrue(back.getInvert());
        assertEquals("B", back.getFrame(BASE).getDomain());
        assertEquals("A", back.getFrame(CURRENT).getDomain());
        assertArrayEquals(new double[] {1.5}, back.tran1(1, new double[] {3.0}, true));

        FrameSet fs = new FrameSet(named(1, "A"));
        fs.addFrame(BASE, new ZoomMap(1, 2.0), named(1, "B"));
        fs.invert();
        assertTrue(fs.getInvert());
        assertEquals(C_INVERTED_FRAMESET, ChannelText.dump(fs));
    }

    // ---- Default Base and Current of an inverted FrameSet ----

    /** Frames A, B and C, with A to B a ZoomMap of 2 and B to C one of 3. */
    private static FrameSet threeDomains() {
        FrameSet fs = new FrameSet(named(1, "A"));
        fs.addFrame(CURRENT, new ZoomMap(1, 2.0), named(1, "B"));
        fs.addFrame(CURRENT, new ZoomMap(1, 3.0), named(1, "C"));
        return fs;
    }

    private static void checkBaseCurrent(
            FrameSet fs, String when, String base, String current, boolean baseSet, boolean currentSet, double value) {
        assertEquals(base, fs.getFrame(BASE).getDomain(), when + ": base");
        assertEquals(current, fs.getFrame(CURRENT).getDomain(), when + ": current");
        assertEquals(baseSet, fs.testBase(), when + ": Base set");
        assertEquals(currentSet, fs.testCurrent(), when + ": Current set");
        assertEquals(value, fs.tran1(1, new double[] {1.0}, true)[0], 1e-12, when + ": tran1");
    }

    /** Checks {@code fs}, before and after a native dump and reload. */
    private static void assertBaseCurrent(
            FrameSet fs, String base, String current, boolean baseSet, boolean currentSet, double value) {
        checkBaseCurrent(fs, "in memory", base, current, baseSet, currentSet, value);
        FrameSet back = assertInstanceOf(FrameSet.class, ChannelText.read(ChannelText.dump(fs)));
        checkBaseCurrent(back, "after a dump and reload", base, current, baseSet, currentSet, value);
    }

    @Test
    void invertedClearCurrentDefaultsToTheFirstFrame() {
        FrameSet fs = threeDomains();
        fs.invert();
        assertBaseCurrent(fs, "C", "A", true, false, 1.0 / 6.0);
        fs.clearCurrent();
        assertBaseCurrent(fs, "C", "A", true, false, 1.0 / 6.0);

        fs = threeDomains();
        fs.setBase(2);
        fs.invert();
        assertBaseCurrent(fs, "C", "B", true, true, 1.0 / 3.0);
        fs.clearCurrent();
        assertBaseCurrent(fs, "C", "A", true, false, 1.0 / 6.0);
    }

    @Test
    void invertedClearBaseDefaultsToTheLastFrame() {
        FrameSet fs = threeDomains();
        fs.invert();
        fs.clearBase();
        assertBaseCurrent(fs, "C", "A", false, false, 1.0 / 6.0);
    }

    @Test
    void invertedRemoveFrameKeepsTheDefaultCurrentFirst() {
        FrameSet fs = threeDomains();
        fs.invert();
        fs.removeFrame(2);
        assertBaseCurrent(fs, "C", "A", true, false, 1.0 / 6.0);

        fs = threeDomains();
        fs.invert();
        fs.removeFrame(1);
        assertBaseCurrent(fs, "C", "B", true, false, 1.0 / 3.0);
    }

    @Test
    void invertedRemoveFrameMovesTheDefaultBaseToTheLastFrame() {
        FrameSet fs = threeDomains();
        fs.invert();
        fs.removeFrame(3);
        assertBaseCurrent(fs, "B", "A", false, false, 0.5);

        fs = threeDomains();
        fs.invert();
        fs.clearBase();
        fs.setCurrent(2);
        fs.removeFrame(1);
        assertBaseCurrent(fs, "C", "B", false, true, 1.0 / 3.0);

        fs = threeDomains();
        fs.invert();
        fs.clearBase();
        fs.setCurrent(2);
        fs.removeFrame(3);
        assertBaseCurrent(fs, "B", "B", false, true, 1.0);
    }

    @Test
    void invertedAddFrameMovesTheDefaultBaseToTheNewFrame() {
        FrameSet fs = threeDomains();
        fs.invert();
        fs.clearBase();
        fs.addFrame(BASE, new ZoomMap(1, 5.0), named(1, "D"));
        assertBaseCurrent(fs, "D", "D", false, true, 1.0);

        fs = threeDomains();
        fs.invert();
        fs.clearBase();
        fs.clearCurrent();
        fs.addFrame(2, new ZoomMap(1, 5.0), named(1, "D"));
        assertBaseCurrent(fs, "D", "D", false, true, 1.0);
    }

    // ---- Base and Current indices, and selection by Domain ----

    /** Frames with Domains X, Y, Y and Z, each added from the first through a ZoomMap of 2, 3 and 5. */
    private static FrameSet fourDomains() {
        FrameSet fs = new FrameSet(named(1, "X"));
        double[] zooms = {2.0, 3.0, 5.0};
        String[] domains = {"Y", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            fs.addFrame(1, new ZoomMap(1, zooms[i]), named(1, domains[i]));
        }
        return fs;
    }

    @Test
    void baseAndCurrentIndices() {
        FrameSet fs = fourDomains();
        assertEquals(1, fs.getBase());
        assertEquals(4, fs.getCurrent());
        fs.setCurrent(2);
        assertEquals(2, fs.getCurrent());
        fs.invert();
        assertEquals(2, fs.getBase());
        assertEquals(1, fs.getCurrent());
        assertEquals(2, fs.getI("Base"));
        assertEquals(1, fs.getI("Current"));
    }

    @Test
    void setBaseAndCurrentByDomain() {
        FrameSet fs = fourDomains();
        fs.setC("Base", " y  ");
        assertEquals(2, fs.getBase());
        assertEquals(4, fs.getCurrent());
        fs.set("Current=x");
        assertEquals(1, fs.getCurrent());

        FrameSet inv = fourDomains();
        inv.invert();
        assertEquals(4, inv.getBase());
        assertEquals(1, inv.getCurrent());
        inv.set("Base=Y");
        assertEquals(2, inv.getBase());
        assertEquals(1, inv.getCurrent());
        inv.set("Current=z");
        assertEquals(2, inv.getBase());
        assertEquals(4, inv.getCurrent());
    }

    @Test
    void setByUnknownDomainIsAttin() {
        FrameSet fs = fourDomains();
        assertEquals(AstException.AST__ATTIN, status(() -> fs.set("Base=Q")));
        assertEquals(AstException.AST__ATTIN, status(() -> fs.set("Current=w")));
        assertEquals(1, fs.getBase());
        assertEquals(4, fs.getCurrent());
    }
}
