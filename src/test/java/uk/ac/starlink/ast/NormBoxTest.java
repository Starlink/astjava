package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * {@code normBox}, which widens a box to take in the singularities of a Frame that lie inside it,
 * gives C's results ({@code astNormBox}, from {@code probes/frame_line_probe.c}): a plain Frame
 * leaves the box alone; a SkyFrame takes in all longitudes and the pole when its Mapping keeps a
 * pole (boxes across RA 0, holding the north or the south pole, with latitude bounds beyond a
 * pole or bad, on a SkyFrame with swapped axes); a CmpFrame asks each component with the other
 * component's axes bad, so a Mapping that makes a point with a bad axis bad (as a Region does)
 * leaves the box alone; and a FrameSet asks its current Frame.
 *
 * <p>The probe's Mappings stand in for the Regions that call {@code astNormBox}: "north" and
 * "south" keep a point beyond a latitude, and make it bad otherwise or when any coordinate is bad,
 * as a Region makes a point outside it; "north_loose" does not look at the other coordinates;
 * "all", "unit" and "none" keep every point, pass every point unchanged, and make every point bad.
 * The values involve no rounding that depends on the platform, so they are compared exactly.
 */
class NormBoxTest {

    /** The Mapping the probe's {@code makemap} makes for {@code name}. */
    static Mapping makemap(String name, int naxes, int ilat) {
        switch (name) {
            case "none":
                return new PermMap(naxes, null, naxes, new int[naxes], null);
            case "all": {
                int[] outperm = new int[naxes];
                for (int i = 0; i < naxes; i++) {
                    outperm[i] = -(i + 1);
                }
                return new PermMap(naxes, null, naxes, outperm, new double[naxes]);
            }
            case "unit":
                return new UnitMap(naxes);
            case "north":
            case "north_loose":
            case "south": {
                String cond = "south".equals(name) ? "< -1.1" : "> 1.25";
                boolean loose = "north_loose".equals(name);
                StringBuilder sum = new StringBuilder();
                for (int i = 0; i < naxes; i++) {
                    sum.append(i > 0 ? "+" : "").append('i').append(i);
                }
                String[] fwd = new String[naxes];
                String[] inv = new String[naxes];
                for (int i = 0; i < naxes; i++) {
                    fwd[i] = "o" + i + " = qif(i" + ilat + " " + cond + ", i" + i + ", <bad>)"
                            + (loose ? "" : " + 0*(" + sum + ")");
                    inv[i] = "i" + i + " = o" + i;
                }
                return new MathMap(naxes, naxes, fwd, inv);
            }
            default:
                throw new IllegalArgumentException(name);
        }
    }

    /** Runs the box case {@code name} on {@code frame}, whose latitude is axis {@code ilat}. */
    private static void assertCaseMatchesC(String name, Frame frame, int ilat) {
        int naxes = frame.getNaxes();
        for (FrameProbe.Op op : FrameProbe.ops(name)) {
            if (!"box".equals(op.name)) {
                fail("Unknown probe operation " + op.line);
            }
            double[] lbnd = op.argRange(0, naxes);
            double[] ubnd = op.argRange(naxes, 2 * naxes);
            Mapping reg = makemap(op.args.get(2 * naxes), naxes, ilat);
            frame.normBox(lbnd, ubnd, reg);
            FrameProbe.assertClose(op.resultRange(0, naxes), lbnd, 0.0, op.line + " lbnd");
            FrameProbe.assertClose(op.resultRange(naxes, 2 * naxes), ubnd, 0.0, op.line + " ubnd");
        }
    }

    @Test
    void aFrameLeavesTheBoxAlone() {
        assertCaseMatchesC("normbox_frame", new Frame(2), 1);
    }

    @Test
    void aSkyFrameTakesInThePoles() {
        assertCaseMatchesC("normbox_sky", new SkyFrame(), 1);
    }

    @Test
    void aSwappedSkyFrameTakesInThePoles() {
        SkyFrame s = new SkyFrame();
        s.permAxes(new int[] {2, 1});
        assertCaseMatchesC("normbox_sky_swapped", s, 0);
    }

    @Test
    void aCmpFrameAsksEachComponent() {
        assertCaseMatchesC("normbox_cmpframe_skytime", new CmpFrame(new SkyFrame(), timeFrame()), 1);
        assertCaseMatchesC("normbox_cmpframe_timesky", new CmpFrame(timeFrame(), new SkyFrame()), 2);
    }

    /**
     * A permuted CmpFrame applies the Mapping to the external axis order and hands the first
     * component the first of the Mapping's outputs in that order, as C does (C's inverse of its
     * permuting PermMap copies each coordinate to the same position).
     */
    @Test
    void aPermutedCmpFrameAsksEachComponent() {
        CmpFrame c = new CmpFrame(new SkyFrame(), timeFrame());
        c.permAxes(new int[] {3, 2, 1});
        assertCaseMatchesC("normbox_cmpframe_permuted", c, 1);
    }

    /**
     * The CmpMaps a CmpFrame builds around the Mapping are gone when it returns, as C annuls them,
     * so the Mapping can still be changed.
     */
    @Test
    void aCmpFrameLeavesTheMappingFree() {
        ZoomMap zoom = new ZoomMap(3, 1.0);
        double[] lbnd = {-0.3, 1.3, 0.0};
        double[] ubnd = {0.3, Math.PI / 2, 1.0};
        new CmpFrame(new SkyFrame(), timeFrame()).normBox(lbnd, ubnd, zoom);
        zoom.setZoom(2.0);
        assertEquals(2.0, zoom.getZoom());
    }

    @Test
    void aFrameSetAsksItsCurrentFrame() {
        assertCaseMatchesC("normbox_frameset", FrameLineTest.frameset(new SkyFrame()), 1);
    }

    static TimeFrame timeFrame() {
        TimeFrame t = new TimeFrame();
        t.setSystem("MJD");
        return t;
    }
}
