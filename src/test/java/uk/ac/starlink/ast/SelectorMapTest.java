package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_selectormap.rs} (C's {@code selectormap.c}): the index of
 * the Region holding each point, the bad value for a point bad on any axis, the constructor's
 * refusals, the undefined inverse, the cancelling of a SelectorMap and its inverse in series, and
 * native dumps. {@code selectormap/two_regions.ast} is C's dump of a SelectorMap of two Circles,
 * copied from the Rust port's {@code tests/selectormap_oracle}, where {@code
 * tools/oracle_fixtures/selectormap_dump_probe.c} wrote it.
 */
class SelectorMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static Box box(double[] lo, double[] hi) {
        return new Box(new Frame(lo.length), 1, lo, hi, null);
    }

    /** Two separate boxes: [0,10]x[0,10] and [20,30]x[20,30]. */
    private static Region[] twoBoxes() {
        return new Region[] {
            box(new double[] {0.0, 0.0}, new double[] {10.0, 10.0}),
            box(new double[] {20.0, 20.0}, new double[] {30.0, 30.0})
        };
    }

    /** The output of {@code sm} for each point, {@code [x, y]}. */
    private static double[] tran(Mapping sm, double[]... points) {
        double[][] in = new double[2][points.length];
        for (int i = 0; i < points.length; i++) {
            in[0][i] = points[i][0];
            in[1][i] = points[i][1];
        }
        return sm.tranP(points.length, 2, in, true, 1)[0];
    }

    @Test
    void selectormapBasicConstruction() {
        SelectorMap sm = new SelectorMap(twoBoxes(), BAD);
        assertEquals(2, sm.getNin());
        assertEquals(1, sm.getNout());
        assertFalse(sm.getInvert());
        assertTrue(sm.getTranForward());
        assertFalse(sm.getTranInverse());
    }

    @Test
    void selectormapEmptyRegionsRejected() {
        AstException e = assertThrows(AstException.class, () -> new SelectorMap(new Region[0], BAD));
        assertEquals(AstException.AST__BDPAR, e.getStatus());
    }

    @Test
    void selectormapMismatchedAxesRejected() {
        Region box2d = box(new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
        Region box3d = box(new double[] {0.0, 0.0, 0.0}, new double[] {1.0, 1.0, 1.0});
        AstException e = assertThrows(AstException.class, () -> new SelectorMap(new Region[] {box2d, box3d}, BAD));
        assertEquals(AstException.AST__BADNI, e.getStatus());
    }

    @Test
    void selectormapPointInRegion1() {
        assertEquals(1.0, tran(new SelectorMap(twoBoxes(), BAD), new double[] {5.0, 5.0})[0]);
    }

    @Test
    void selectormapPointInRegion2() {
        assertEquals(2.0, tran(new SelectorMap(twoBoxes(), BAD), new double[] {25.0, 25.0})[0]);
    }

    @Test
    void selectormapPointInNoRegion() {
        assertEquals(0.0, tran(new SelectorMap(twoBoxes(), BAD), new double[] {15.0, 15.0})[0]);
    }

    @Test
    void selectormapBadInputReturnsBadval() {
        assertEquals(99.0, tran(new SelectorMap(twoBoxes(), 99.0), new double[] {BAD, 5.0})[0]);
    }

    @Test
    void selectormapMultiplePoints() {
        double[] out = tran(
                new SelectorMap(twoBoxes(), BAD),
                new double[] {5.0, 5.0},
                new double[] {25.0, 25.0},
                new double[] {15.0, 15.0},
                new double[] {BAD, 5.0});
        assertEquals(1.0, out[0]);
        assertEquals(2.0, out[1]);
        assertEquals(0.0, out[2]);
        assertEquals(BAD, out[3]);
    }

    @Test
    void selectormapFirstRegionWins() {
        Region[] regs = {
            box(new double[] {0.0, 0.0}, new double[] {10.0, 10.0}),
            box(new double[] {0.0, 0.0}, new double[] {10.0, 10.0})
        };
        assertEquals(1.0, tran(new SelectorMap(regs, BAD), new double[] {5.0, 5.0})[0]);
    }

    /** A SelectorMap has no inverse (selectormap.c), so C's base Transform raises AST__TRNND. */
    @Test
    void selectormapInverseIsAnError() {
        SelectorMap sm = new SelectorMap(twoBoxes(), BAD);
        assertFalse(sm.getTranInverse());
        AstException e = assertThrows(AstException.class, () -> sm.tranN(1, 1, new double[] {1.0}, false, 2));
        assertEquals(AstException.AST__TRNND, e.getStatus());
    }

    /** The fixture is a series CmpMap of a SelectorMap of Boxes and its inverse: a UnitMap of 2. */
    @Test
    void selectormapSimplifyInverseCancelFixture() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/selectormap_inverse_cancel.map"));
        Mapping s = m.simplify();
        assertInstanceOf(UnitMap.class, s);
        assertEquals(2, s.getNin());
        assertEquals(2, s.getNout());
    }

    @Test
    void selectormapSerializationRoundtrip() {
        SelectorMap sm = new SelectorMap(twoBoxes(), 99.0);
        String text = ChannelText.dump(sm);
        SelectorMap loaded = assertInstanceOf(SelectorMap.class, ChannelText.read(text));
        assertTrue(text.contains("Reg2 = "), text);
        assertFalse(text.contains("Reg3"), text);
        assertTrue(text.contains("BadVal = 99 "), text);
        assertEquals(text, ChannelText.dump(loaded));
        assertEquals(2, loaded.getNin());
        assertEquals(1, loaded.getNout());
        double[][] pts = {{5.0, 5.0}, {25.0, 25.0}, {15.0, 15.0}};
        double[] out1 = tran(sm, pts);
        double[] out2 = tran(loaded, pts);
        for (int i = 0; i < pts.length; i++) {
            assertEquals(out1[i], out2[i]);
        }
    }

    /**
     * C writes the second and later Regions of a SelectorMap without their Frame, which the
     * first Region's gives them all (selectormap.c {@code Dump}).
     */
    @Test
    void selectormapDumpMatchesTheCLibrary() {
        String want = resource("/selectormap/two_regions.ast");
        Region[] regs = {
            new Circle(new Frame(2), new double[] {0.0, 0.0}, 1.0, null),
            new Circle(new Frame(2), new double[] {5.0, 5.0}, 1.0, null)
        };
        assertEquals(want, ChannelText.dump(new SelectorMap(regs, BAD)));
        assertEquals(want, ChannelText.dump(ChannelText.read(want)));
    }

    private static String resource(String name) {
        try (InputStream in = SelectorMapTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("no resource " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
