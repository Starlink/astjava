package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/make_int_world.rs} (C
 * {@code MakeIntWorld}), with the values the C function gives for them, and tests of the
 * degenerate-axis branches and of C's {@code NearestPix}.
 */
class FitsMakeIntWorldTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final double FITSTOL = 0.001;

    /** A FrameSet from a GRID Frame to an IWC Frame through {@code pixelToIwc}. */
    private static FrameSet frameSet(Mapping pixelToIwc, Frame iwc) {
        Frame pixel = new Frame(pixelToIwc.getNin());
        pixel.setDomain("GRID");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, pixelToIwc, iwc);
        return fs;
    }

    private static boolean makeIntWorld(FrameSet fs, FitsStore store, double[] dim, boolean sipok) {
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Frame fr = fs.getFrame(FrameSet.AST__CURRENT);
        int[] wperm = new int[map.getNout()];
        for (int i = 0; i < wperm.length; i++) {
            wperm[i] = i;
        }
        return FitsMakeIntWorld.makeIntWorld(map, fr, wperm, ' ', store, dim, FITSTOL, sipok, "astWrite", "FitsChan");
    }

    /**
     * Pixel and IWC coordinates equal: the root pixel is the grid centre (50.5, 50.5), which is
     * also its IWC position, so CRPIX is (0, 0) and the CD matrix is the unit matrix.
     */
    @Test
    void identityPixelToIwc() {
        FrameSet fs = frameSet(new UnitMap(2), new Frame(2));
        FitsStore store = new FitsStore(2);
        assertTrue(makeIntWorld(fs, store, new double[] {100, 100}, false));
        assertEquals(0.0, store.crpix[0], 1e-9);
        assertEquals(0.0, store.crpix[1], 1e-9);
        assertEquals(1.0, store.cdelt[0], 1e-9);
        assertEquals(1.0, store.cdelt[1], 1e-9);
        assertNull(store.pc[0][0]);
        assertNull(store.pc[0][1]);
        assertNull(store.wcsaxes);
    }

    /**
     * IWC = -128 + 0.001 * pixel: CDELT is 0.001 and CRPIX the pixel at IWC zero, 128000, which
     * the Mapping takes back to IWC zero.
     */
    @Test
    void winMap2x2RecoversCrpixAndCd() {
        WinMap win = new WinMap(new double[] {-128.0, -128.0}, new double[] {0.001, 0.001});
        FrameSet fs = frameSet(win, new Frame(2));
        FitsStore store = new FitsStore(2);
        assertTrue(makeIntWorld(fs, store, new double[] {100, 100}, false));
        assertEquals(0.001, store.cdelt[0], 1e-9);
        assertEquals(0.001, store.cdelt[1], 1e-9);
        assertEquals(128000.0, store.crpix[0], 1e-6);
        assertEquals(128000.0, store.crpix[1], 1e-6);
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        double[][] iwc = map.transform(new double[][] {{store.crpix[0]}, {store.crpix[1]}}, 1, true);
        assertEquals(0.0, iwc[0][0], 1e-6);
        assertEquals(0.0, iwc[1][0], 1e-6);
    }

    /** A CD matrix of 30 degrees rotation and scale 0.5, after a shift putting IWC zero at (50.5, 50.5). */
    @Test
    void rotatedMatrixRecoversPcAndCdelt() {
        double theta = Math.toRadians(30.0);
        double s = Math.sin(theta);
        double c = Math.cos(theta);
        double cdelt = 0.5;
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{cdelt * c, -cdelt * s}, {cdelt * s, cdelt * c}});
        CmpMap chain = new CmpMap(new ShiftMap(new double[] {-50.5, -50.5}), mm, true);
        FrameSet fs = frameSet(chain, new Frame(2));
        FitsStore store = new FitsStore(2);
        assertTrue(makeIntWorld(fs, store, new double[] {100, 100}, false));
        assertEquals(cdelt, store.cdelt[0], 1e-9);
        assertEquals(cdelt, store.cdelt[1], 1e-9);
        assertEquals(50.5, store.crpix[0], 1e-6);
        assertEquals(50.5, store.crpix[1], 1e-6);
        assertEquals(c, store.pc[0][0], 1e-9);
        assertEquals(-s, store.pc[0][1], 1e-9);
        assertEquals(s, store.pc[1][0], 1e-9);
        assertEquals(c, store.pc[1][1], 1e-9);
    }

    /** A Mapping with fewer outputs than inputs cannot be described. */
    @Test
    void noutLessThanNinReturnsFalse() {
        PermMap pm = new PermMap(3, new int[] {1, 2, -1}, 2, new int[] {1, 2}, new double[] {0.0});
        FrameSet fs = frameSet(pm, new Frame(2));
        FitsStore store = new FitsStore(3);
        assertFalse(makeIntWorld(fs, store, new double[] {100, 100, 100}, false));
        assertNull(store.crpix[0]);
        assertNull(store.cdelt[0]);
    }

    /**
     * One pixel axis and two WCS axes, the second constant at 0.25: the degenerate axis gets a
     * unit vector orthogonal to the first, its CRPIX is 1 - 0.25 scaled to a unit diagonal element,
     * and, the WCS axis being linear and fed by the degenerate axis alone, CRPIX is moved to 1 and
     * CRVAL to 1 * (1 - 0.75) + 20. WCSAXES is written since there are fewer pixel than WCS axes.
     */
    @Test
    void wcsaxesSetWhenNinLessThanNwcs() {
        PermMap pm = new PermMap(1, new int[] {1}, 2, new int[] {1, -1}, new double[] {0.25});
        FrameSet fs = frameSet(pm, new Frame(2));
        FitsStore store = new FitsStore(2);
        store.crval[0] = 10.0;
        store.crval[1] = 20.0;
        assertTrue(makeIntWorld(fs, store, new double[] {100}, false));
        assertEquals(2, store.wcsaxes.intValue());
        assertEquals(0.0, store.crpix[0], 1e-9);
        assertEquals(1.0, store.crpix[1]);
        assertEquals(10.0, store.crval[0]);
        assertEquals(20.25, store.crval[1], 1e-12);
        assertEquals(1.0, store.cdelt[0], 1e-9);
        assertEquals(1.0, store.cdelt[1], 1e-9);
    }

    /** The degenerate pixel axis of a SkyFrame pair, as in {@link #degenerateLatitudeAxis}. */
    private static FitsStore degenerateSky(String domain) {
        Mapping map = new CmpMap(
                new ZoomMap(1, -0.001), new PermMap(1, new int[] {1}, 2, new int[] {1, -1}, new double[] {0.02}), true);
        SkyFrame sky = new SkyFrame();
        if (domain != null) {
            sky.setDomain(domain);
        }
        FitsStore store = new FitsStore(2);
        assertTrue(makeIntWorld(frameSet(map, sky), store, new double[] {100}, false));
        return store;
    }

    /**
     * A celestial pair with the latitude axis degenerate: its column is scaled so that its
     * diagonal element is minus that of the longitude axis, k = 0.001, and CRPIX = 1 + (1 - 0.02 -
     * 1) / k = -19.
     */
    @Test
    void degenerateLatitudeAxis() {
        FitsStore store = degenerateSky(null);
        assertEquals(0.0, store.crpix[0], 1e-9);
        assertEquals(-19.0, store.crpix[1], 1e-9);
        assertEquals(-0.001, store.cdelt[0], 1e-15);
        assertEquals(0.001, store.cdelt[1], 1e-15);
    }

    /**
     * C's MakeIntWorld pairs celestial axes with its IsASkyFrame macro, which also needs the Domain
     * SKY; with another Domain the degenerate column keeps a unit diagonal element and CRPIX is 1
     * - 0.02.
     */
    @Test
    void degenerateSkyAxisNeedsTheSkyDomain() {
        FitsStore store = degenerateSky("OTHER");
        assertEquals(0.98, store.crpix[1], 1e-12);
        assertEquals(-0.001, store.cdelt[0], 1e-15);
        assertEquals(1.0, store.cdelt[1], 1e-15);
    }

    /** With SipOK set and a celestial pair, C describes any distortion with SIPIntWorld. */
    @Test
    void sipWithACelestialPairNeedsSipIntWorld() {
        FrameSet fs = frameSet(new UnitMap(2), new SkyFrame());
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> makeIntWorld(fs, new FitsStore(2), new double[] {100, 100}, true));
        assertTrue(e.getMessage().endsWith("needs FitsSipIntWorld"), e.getMessage());
        assertTrue(
                makeIntWorld(frameSet(new UnitMap(2), new Frame(2)), new FitsStore(2), new double[] {100, 100}, true));
    }

    /** NearestPix: 5.3 is pixel 2.65, which rounds to 3, value 6; -5 is pixel -2.5, rounded away from zero. */
    @Test
    void nearestPixRoundsThePixelPosition() {
        ZoomMap zoom = new ZoomMap(1, 2.0);
        assertEquals(6.0, FitsMakeIntWorld.nearestPix(zoom, 5.3, 0));
        assertEquals(-6.0, FitsMakeIntWorld.nearestPix(zoom, -5.0, 0));
        assertEquals(BAD, FitsMakeIntWorld.nearestPix(zoom, BAD, 0));
    }

    /**
     * NearestPix on a Mapping with no inverse splits off the axis; an axis whose split Mapping has
     * no inverse keeps its value.
     */
    @Test
    void nearestPixSplitsAMappingWithNoInverse() {
        PolyMap square = new PolyMap(1, 1, 1, new double[] {1.0, 1, 2}, 0, null);
        square.set("IterInverse=0");
        CmpMap map = new CmpMap(new ZoomMap(1, 2.0), square, false);
        assertEquals(6.0, FitsMakeIntWorld.nearestPix(map, 5.3, 0));
        assertEquals(5.3, FitsMakeIntWorld.nearestPix(map, 5.3, 1));
        assertFalse(map.getInvert());
    }
}
