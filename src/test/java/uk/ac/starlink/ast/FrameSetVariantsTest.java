package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_frameset_variants.rs}, the port of the variant tests of C's
 * {@code ast_tester/testframeset.c} (errors 1-39): AddVariant (renaming and with a Mapping), the
 * Variant attribute, AllVariants, MirrorVariants, the change of transformation with the selected
 * variant, a Frame added on top of a variant, dump and restore, and the reading of a dump C wrote.
 * The transformed values are those of {@code testframeset.c}.
 *
 * <p>Rust's typed variant methods are the FrameSet's package-private {@code addVariant}, {@code
 * getVariant}, {@code setVariant}, {@code testVariant}, {@code clearVariant}, {@code
 * getAllVariants} and {@code mirrorVariants}. {@code testframeset.c} simplifies the variant
 * Mappings it builds, as Rust does; {@link Mapping#simplify} is called on them here too, and the
 * tests compare only transformations, which simplification does not change.
 */
class FrameSetVariantsTest {

    private static final int BASE = FrameSet.AST__BASE;
    private static final int CURRENT = FrameSet.AST__CURRENT;

    private static Frame frm(String domain) {
        Frame f = new Frame(2);
        f.setDomain(domain);
        return f;
    }

    /** A WinMap taking pixel (1,1)-(100,200) to the window from {@code outa} to {@code outb}. */
    private static WinMap window(double[] outa, double[] outb) {
        return new WinMap(2, new double[] {1.0, 1.0}, new double[] {100.0, 200.0}, outa, outb);
    }

    /** The PIXEL to focal-plane WinMap of {@code testframeset.c}. */
    private static WinMap p2fmap() {
        return window(new double[] {-2.5, -1.0}, new double[] {2.5, 1.0});
    }

    /** The {@code testframeset.c} FrameSet: PIXEL to FPLANE by {@link #p2fmap}. */
    private static FrameSet baseFs() {
        FrameSet fs = new FrameSet(frm("PIXEL"));
        fs.addFrame(CURRENT, p2fmap(), frm("FPLANE"));
        return fs;
    }

    /** The forward transformation of one point. */
    private static double[] tran(Mapping fs, double x, double y) {
        double[][] out = fs.tran2(1, new double[] {x}, new double[] {y}, true);
        return new double[] {out[0][0], out[1][0]};
    }

    private static void assertNear(double[] want, double[] got, String what) {
        assertTrue(
                Math.abs(got[0] - want[0]) < 1e-6 && Math.abs(got[1] - want[1]) < 1e-6,
                what + ": (" + got[0] + "," + got[1] + ")");
    }

    /** The FP2 variant Mapping: inverse of {@link #p2fmap} followed by the window to (100,100)-(200,200). */
    private static Mapping fp2Map() {
        Mapping inv = p2fmap();
        inv.invert();
        Mapping p2fmap2 = window(new double[] {100.0, 100.0}, new double[] {200.0, 200.0});
        return new CmpMap(inv, p2fmap2, true).simplify();
    }

    /** The FP3 variant Mapping: current to base of {@code fs}, then the window to (-100,-100)-(-200,-200). */
    private static Mapping fp3Map(FrameSet fs) {
        Mapping p2fmap3 = window(new double[] {-100.0, -100.0}, new double[] {-200.0, -200.0});
        return new CmpMap(fs.getMapping(CURRENT, BASE), p2fmap3, true).simplify();
    }

    @Test
    void variantLifecycleMatchesC() {
        FrameSet fs = baseFs();

        // Errors 1-3: the only variant is the current Frame's Domain, and Variant is not set.
        assertEquals("FPLANE", fs.getAllVariants());
        assertEquals("FPLANE", fs.getVariant());
        assertFalse(fs.testVariant());

        // Errors 4-6: AddVariant with no Mapping renames the current variant.
        fs.addVariant(null, "FP1");
        assertEquals("FP1", fs.getAllVariants());
        assertEquals("FP1", fs.getVariant());
        assertTrue(fs.testVariant());

        // Errors 7-9: clearing reverts to the Frame's Domain.
        fs.clearVariant();
        assertEquals("FPLANE", fs.getAllVariants());
        assertEquals("FPLANE", fs.getVariant());
        assertFalse(fs.testVariant());

        fs.addVariant(null, "FP1");
        fs.addVariant(fp2Map(), "FP2");

        // Errors 10-12.
        assertEquals("FP1 FP2", fs.getAllVariants());
        assertEquals("FP2", fs.getVariant());
        assertTrue(fs.testVariant());

        // Error 13: under FP2, pixel (50.5,100.5) maps to (150,150).
        assertNear(new double[] {150.0, 150.0}, tran(fs, 50.5, 100.5), "FP2");

        // Error 14: FP1 gives the original focal-plane Mapping.
        fs.setVariant("FP1");
        assertNear(new double[] {0.0, 0.0}, tran(fs, 50.5, 100.5), "FP1");

        fs.addVariant(fp3Map(fs), "FP3");

        // Errors 15-17.
        assertEquals("FP1 FP2 FP3", fs.getAllVariants());
        assertEquals("FP3", fs.getVariant());
        assertTrue(fs.testVariant());

        // Error 18: under FP3, (50.5,100.5) maps to (-150,-150).
        assertNear(new double[] {-150.0, -150.0}, tran(fs, 50.5, 100.5), "FP3");

        // Error 19: back to FP2.
        fs.setVariant("FP2");
        assertNear(new double[] {150.0, 150.0}, tran(fs, 50.5, 100.5), "FP2 again");
    }

    @Test
    void setUnknownVariantErrors() {
        FrameSet fs = baseFs();
        AstException e = assertThrows(AstException.class, () -> fs.setVariant("NOPE"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
    }

    /**
     * With no variants, the only variant of the current Frame is named by its Domain, and selecting
     * it does nothing: the values of a C probe.
     */
    @Test
    void selectingTheOnlyVariantOfAFrameWithoutVariants() {
        FrameSet fs = baseFs();
        fs.setVariant("  fplane ");
        assertEquals("FPLANE", fs.getVariant());
        assertFalse(fs.testVariant());
        for (String name : new String[] {"FP1", "pixel"}) {
            AstException e = assertThrows(AstException.class, () -> fs.setVariant(name));
            assertEquals(AstException.AST__ATTIN, e.getStatus());
            assertEquals(
                    "astSetVariant(FrameSet): Unknown Frame variant '"
                            + name.toUpperCase(Locale.ROOT)
                            + "' requested. (AST__ATTIN)",
                    e.getMessage());
        }
    }

    /** The point (x, y) mapped from Frame 1 to Frame 2. */
    private static double[] pixToFp(FrameSet fs, double x, double y) {
        return tran(fs.getMapping(1, 2), x, y);
    }

    /**
     * C reads outside its arrays when the Frame whose variant is selected is on the root node,
     * which has no Mapping from a parent to replace; the port moves the Frame to a new node joined
     * to the root by the change of variant, so the Frames derived from the root do not move.
     */
    @Test
    void selectingAVariantOfAFrameOnTheRootNode() {
        FrameSet fs = baseFs();
        fs.setCurrent(1);
        fs.addVariant(new ShiftMap(new double[] {10.0, 20.0}), "SHIFTED");
        assertEquals("SHIFTED", fs.getVariant());
        assertEquals(2, fs.getNframe());

        // PIXEL is now the root shifted by (10, 20); FPLANE still derives from the root, so
        // PIXEL (60.5, 120.5) is root (50.5, 100.5).
        FrameSet unshifted = baseFs();
        unshifted.setCurrent(2);
        double[] want = tran(unshifted, 50.5, 100.5);
        assertArrayEquals(want, pixToFp(fs, 60.5, 120.5));

        FrameSet back = assertInstanceOf(FrameSet.class, ChannelText.read(ChannelText.dump(fs)));
        assertArrayEquals(pixToFp(fs, 60.5, 120.5), pixToFp(back, 60.5, 120.5));

        fs.setVariant("PIXEL");
        assertEquals("PIXEL", fs.getVariant());
        assertArrayEquals(want, pixToFp(fs, 50.5, 100.5));
    }

    /** A unit WinMap, to be added as a variant. */
    private static WinMap unitWindow() {
        return new WinMap(
                2, new double[] {1.0, 1.0}, new double[] {2.0, 2.0}, new double[] {1.0, 1.0}, new double[] {2.0, 2.0});
    }

    @Test
    void duplicateVariantNameErrors() {
        FrameSet fs = baseFs();
        fs.addVariant(null, "FP1");
        AstException e = assertThrows(AstException.class, () -> fs.addVariant(unitWindow(), "FP1"));
        assertEquals(AstException.AST__BDVNM, e.getStatus());
    }

    /** The FP1/FP2/FP3 FrameSet with FP2 selected, the state at C's Error 19. */
    private static FrameSet threeVariantFs() {
        FrameSet fs = baseFs();
        fs.addVariant(null, "FP1");
        fs.addVariant(fp2Map(), "FP2");
        fs.addVariant(fp3Map(fs), "FP3");
        fs.setVariant("FP2");
        return fs;
    }

    /**
     * Errors 23-31: a Frame added on top of a variant Frame mirrors that Frame's variants, and a
     * variant cannot be added to a mirror.
     */
    @Test
    void addVariantToMirrorErrors() {
        FrameSet fs = threeVariantFs();
        // The Frame with the variants is the current one, FPLANE, Frame 2.
        int orig = 2;

        fs.addFrame(CURRENT, new UnitMap(2), frm("DSB"));

        // A Frame with no variants of its own reports just its Domain.
        assertEquals("DSB", fs.getAllVariants());
        assertFalse(fs.testVariant());

        fs.mirrorVariants(orig);
        assertEquals("FP1 FP2 FP3", fs.getAllVariants());

        AstException e = assertThrows(AstException.class, () -> fs.addVariant(unitWindow(), "FP4"));
        assertEquals(AstException.AST__MIRRO, e.getStatus());
    }

    /**
     * A FrameSet with FP1/FP2/FP3 variants, FP2 selected, survives a dump and restore: AllVariants,
     * the selected Variant and the transformation are kept (C's {@code Vrnts} item of the Frame).
     */
    @Test
    void variantsNativeRoundTrip() {
        String text = ChannelText.dump(threeVariantFs());
        assertTrue(text.contains("Vrnts"), "dump must carry the Vrnts object");

        FrameSet fs2 = assertInstanceOf(FrameSet.class, ChannelText.read(text));
        assertEquals("FP1 FP2 FP3", fs2.getAllVariants());
        assertEquals("FP2", fs2.getVariant());
        assertNear(new double[] {150.0, 150.0}, tran(fs2, 50.5, 100.5), "FP2");
    }

    /** A dump C wrote (FP1/FP2, FP2 selected) reads back with the variant state and transformations. */
    @Test
    void readsCGeneratedVariantDump() {
        FrameSet fs = assertInstanceOf(FrameSet.class, ChannelText.read(Fixtures.read("serialisation/c_variants.ast")));

        assertEquals("FP1 FP2", fs.getAllVariants());
        assertEquals("FP2", fs.getVariant());
        assertTrue(fs.testVariant());
        assertNear(new double[] {150.0, 150.0}, tran(fs, 50.5, 100.5), "FP2");

        fs.setVariant("FP1");
        assertNear(new double[] {0.0, 0.0}, tran(fs, 50.5, 100.5), "FP1");
    }

    /**
     * Remapping a Frame drops its variant Mappings, keeping the selected one, as C's {@code
     * astRemapFrame} clears the Variant attribute first; C reports AllVariants {@code "SKYX"} after
     * remapping a Frame with variants SKYX and V2. Permuting the current Frame's axes remaps it, so
     * it drops them too.
     */
    @Test
    void remappingAFrameDropsItsVariants() {
        FrameSet fs = new FrameSet(frm("PIX"));
        fs.addFrame(BASE, new ZoomMap(2, 2.0), frm("SKYX"));
        fs.addVariant(new ShiftMap(new double[] {1.0, 1.0}), "V2");
        assertEquals("SKYX V2", fs.getAllVariants());
        fs.remapFrame(CURRENT, new ZoomMap(2, 3.0));
        assertEquals("SKYX", fs.getAllVariants());
        assertFalse(fs.testVariant());

        fs.addVariant(new ShiftMap(new double[] {1.0, 1.0}), "V3");
        assertEquals("SKYX V3", fs.getAllVariants());
        fs.permAxes(new int[] {2, 1});
        assertEquals("SKYX", fs.getAllVariants());
    }

    /** The point (1,1) mapped from Frame {@code a} to Frame {@code b}. */
    private static double[] map11(FrameSet fs, int a, int b) {
        return tran(fs.getMapping(a, b), 1.0, 1.0);
    }

    /**
     * Selecting a variant replaces the Mapping of the variant owner's node in place, as C's {@code
     * SetVariant} does, so a Frame derived from the owner moves with it, and selecting through a
     * mirror changes the owner. C (PIX -Zoom 2-> SKY with variants SKY and V2 = Shift 1, then FOO
     * from SKY by Zoom 3): after {@code Variant = SKY}, PIX->SKY (2,2), PIX->FOO (6,6) and SKY->FOO
     * (3,3); then with FOO mirroring SKY, {@code Variant = V2} from FOO gives PIX->SKY (3,3),
     * PIX->FOO (9,9) and SKY->FOO (3,3).
     */
    @Test
    void setVariantReplacesTheOwnerNodeMapping() {
        FrameSet fs = new FrameSet(frm("PIX"));
        fs.addFrame(BASE, new ZoomMap(2, 2.0), frm("SKY"));
        fs.addVariant(new ShiftMap(new double[] {1.0, 1.0}), "V2");
        assertArrayEquals(new double[] {3.0, 3.0}, map11(fs, 1, 2));
        fs.addFrame(2, new ZoomMap(2, 3.0), frm("FOO"));
        assertArrayEquals(new double[] {9.0, 9.0}, map11(fs, 1, 3));

        fs.setCurrent(2);
        fs.setVariant("SKY");
        assertEquals("SKY", fs.getVariant());
        assertArrayEquals(new double[] {2.0, 2.0}, map11(fs, 1, 2));
        assertArrayEquals(new double[] {6.0, 6.0}, map11(fs, 1, 3));
        assertArrayEquals(new double[] {3.0, 3.0}, map11(fs, 2, 3));

        fs.setCurrent(3);
        fs.mirrorVariants(2);
        fs.setVariant("V2");
        assertEquals("V2", fs.getVariant());
        assertArrayEquals(new double[] {3.0, 3.0}, map11(fs, 1, 2));
        assertArrayEquals(new double[] {9.0, 9.0}, map11(fs, 1, 3));
        assertArrayEquals(new double[] {3.0, 3.0}, map11(fs, 2, 3));
    }
}
