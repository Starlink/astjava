package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/celestial_axes.rs} (C
 * {@code CelestialAxes}, {@code SplitMap} and {@code SplitMap2}, fitschan.c); the values C writes
 * for the FrameSets of Rust {@code tests/fitschan_splitmap_oracle/cases.rs} (made by {@code
 * tools/oracle_fixtures/fitschan_splitmap_probe.c}), whose pixel to sky Mapping holds no WcsMap;
 * and tests of branches read from C's code.
 */
class FitsCelestialAxesTest {

    /** A FrameSet from a GRID Frame to {@code current} through {@code map}. */
    private static FrameSet frameSet(Mapping map, Frame current) {
        Frame pixel = new Frame(map.getNin());
        pixel.setDomain("GRID");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, map, current);
        return fs;
    }

    private static SkyFrame fk5() {
        SkyFrame sky = new SkyFrame();
        sky.setSystem("FK5");
        return sky;
    }

    /** The result of a call: the Mapping, with the store, FITS axes and flags it changed. */
    private static final class Result {
        final Mapping map;
        final FitsStore store;
        final int[] wperm;
        final boolean[] axisDone;

        Result(Mapping map, FitsStore store, int[] wperm, boolean[] axisDone) {
            this.map = map;
            this.store = store;
            this.wperm = wperm;
            this.axisDone = axisDone;
        }
    }

    private static Result celestialAxes(FitsChan fc, FrameSet fs, double[] dim) {
        int nwcs = fs.getNout();
        int[] wperm = new int[nwcs];
        for (int i = 0; i < nwcs; i++) {
            wperm[i] = i;
        }
        FitsStore store = new FitsStore(nwcs);
        boolean[] axisDone = new boolean[nwcs];
        Mapping map =
                FitsCelestialAxes.celestialAxes(fc, fs, dim, wperm, ' ', store, axisDone, 0, "astWrite", "FitsChan");
        return new Result(map, store, wperm, axisDone);
    }

    private static Result celestialAxes(FrameSet fs) {
        double[] dim = new double[fs.getNin()];
        java.util.Arrays.fill(dim, 100.0);
        return celestialAxes(new FitsChan(), fs, dim);
    }

    /** A pixel to sky Mapping of an inverted TAN WcsMap. */
    private static FrameSet tanFrameSet() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        return frameSet(wm, fk5());
    }

    @Test
    void noCelestialAxesReturnsUnitmap() {
        Result r = celestialAxes(frameSet(new UnitMap(2), new Frame(2)));
        assertEquals(2, r.map.getNin());
        assertEquals(2, r.map.getNout());
        assertInstanceOf(UnitMap.class, r.map);
        assertFalse(r.axisDone[0]);
        assertFalse(r.axisDone[1]);
        assertNull(r.store.crval[0]);
        assertNull(r.store.crval[1]);
    }

    /**
     * Pixel axes exchanged before the projection: the intermediate world axes are those of the
     * pixel axes the other way round, so the celestial FITS axes are exchanged.
     */
    @Test
    void autoAxisSwapFiresWhenPixelToIwcTransposes() {
        PermMap swap = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        Result r = celestialAxes(frameSet(new CmpMap(swap, wm, true), fk5()));
        assertArrayEquals(new int[] {1, 0}, r.wperm);
        assertEquals("DEC--TAN", r.store.ctype[0]);
        assertEquals("RA---TAN", r.store.ctype[1]);
        assertEquals(90.0, r.store.crval[0]);
        assertEquals(0.0, r.store.crval[1]);
    }

    /**
     * A default TAN projection: its fiducial point, the native pole, is CRVAL (0, 90) through a
     * UnitMap.
     */
    @Test
    void tan2axisWritesCelestialKeywords() {
        Result r = celestialAxes(tanFrameSet());
        assertTrue(r.axisDone[0]);
        assertTrue(r.axisDone[1]);
        assertEquals("RA---TAN", r.store.ctype[0]);
        assertEquals("DEC--TAN", r.store.ctype[1]);
        assertEquals("FK5", r.store.radesys);
        assertNotNull(r.store.equinox);
        assertEquals(0.0, r.store.crval[0]);
        assertEquals(90.0, r.store.crval[1]);
        assertNull(r.store.lonpole);
        assertNull(r.store.latpole);
        assertEquals(2, r.map.getNin());
        assertEquals(2, r.map.getNout());

        // The Mapping takes world coordinates to intermediate world coordinates in degrees. The
        // pixel to world Mapping has native and celestial coordinates the same, a native
        // longitude of the celestial pole of 180 degrees; with no LONPOLE stored, WcsNative uses
        // the FITS-WCS default for a fiducial point at the pole, 0, so the intermediate world
        // coordinates are the projection plane coordinates turned through 180 degrees.
        Mapping pixToIwc = new CmpMap(tanFrameSet().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT), r.map, true);
        double[][] iwc = pixToIwc.transform(new double[][] {{0.01}, {-0.02}}, 1, true);
        assertEquals(-Math.toDegrees(0.01), iwc[0][0], 1e-10);
        assertEquals(Math.toDegrees(0.02), iwc[1][0], 1e-10);
    }

    // ------------------------------------------------------------------
    // The FrameSets of Rust tests/fitschan_splitmap_oracle/cases.rs
    // ------------------------------------------------------------------

    /** The probe's SkyFrame: ICRS, its System cleared. */
    private static SkyFrame probeSky() {
        SkyFrame sky = new SkyFrame();
        sky.setSystem("ICRS");
        sky.clear("System");
        return sky;
    }

    /** A diagonal MatrixMap followed by a ShiftMap. */
    private static Mapping linear(double[] diag, double[] shift) {
        return new CmpMap(new MatrixMap(diag.length, diag.length, diag), new ShiftMap(shift), true);
    }

    /** C's cards for the celestial axes of the probe's {@code linear} and {@code linear_3d} cases. */
    private static void assertLinearCase(Result r) {
        assertEquals(11.459155902616466, r.store.crval[0]);
        assertEquals(0.0, r.store.crval[1]);
        assertEquals("RA---CAR", r.store.ctype[0]);
        assertEquals("DEC--CAR", r.store.ctype[1]);
        assertEquals("ICRS", r.store.radesys);
        // C writes 348.54084409738351.
        assertEquals(348.5408440973835, r.store.lonpole, 1e-12);
        assertEquals(348.5408440973835, r.store.pv[0][3], 1e-12);
        assertNull(r.store.latpole);
        assertNull(r.store.pv[0][4]);
        assertNull(r.store.equinox);
    }

    /** A linear Mapping is given a CAR projection, its longitude origin moved to the pixel origin. */
    @Test
    void linearMappingIsGivenACarProjection() {
        Result r = celestialAxes(frameSet(linear(new double[] {-0.001, 0.002}, new double[] {0.2, 0.3}), probeSky()));
        assertLinearCase(r);
    }

    @Test
    void linear3dMappingIsGivenACarProjection() {
        Frame x = new Frame(1);
        x.setDomain("X");
        Result r = celestialAxes(frameSet(
                linear(new double[] {-0.001, 0.002, 3.0}, new double[] {0.2, 0.3, 4.0}), new CmpFrame(probeSky(), x)));
        assertLinearCase(r);
        assertTrue(r.axisDone[0]);
        assertTrue(r.axisDone[1]);
        assertFalse(r.axisDone[2]);
        assertEquals(3, r.map.getNin());
        assertEquals(3, r.map.getNout());
        assertNull(r.store.crval[2]);
    }

    /** Constant celestial values are given the projection of a TAN header with them at CRVAL. */
    @Test
    void constantCelestialValuesAreGivenATanProjection() {
        PermMap pm = new PermMap(2, new int[] {0, 0}, 2, new int[] {-1, -2}, new double[] {0.5, -0.25});
        Result r = celestialAxes(frameSet(pm, probeSky()));
        assertEquals(28.647889756541154, r.store.crval[0]);
        // C writes -14.323944878270581.
        assertEquals(-14.32394487827058, r.store.crval[1]);
        assertEquals("RA---TAN", r.store.ctype[0]);
        assertEquals("DEC--TAN", r.store.ctype[1]);
        assertEquals("ICRS", r.store.radesys);
        assertNull(r.store.lonpole);
        assertNull(r.store.latpole);
    }

    // ------------------------------------------------------------------
    // Branches read from C's code
    // ------------------------------------------------------------------

    /** A non-linear Mapping with no projection and celestial outputs that vary. */
    private static FrameSet noProjectionFrameSet() {
        // x' = x + 0.001 x^2, y' = y.
        PolyMap poly = new PolyMap(2, 2, 3, new double[] {1, 1, 1, 0, 1, 2, 0, 1, 0.001, 1, 2, 0}, 0, new double[0]);
        Mapping map = new CmpMap(poly, new ZoomMap(2, 0.001), true);
        return frameSet(map, fk5());
    }

    /** With no projection and TabOK 0, C describes nothing: a UnitMap, the axes still classified. */
    @Test
    void noProjectionWithoutTabOkGivesAUnitMap() {
        Result r = celestialAxes(noProjectionFrameSet());
        assertInstanceOf(UnitMap.class, r.map);
        assertTrue(r.axisDone[0]);
        assertTrue(r.axisDone[1]);
        assertNull(r.store.ctype[0]);
    }

    /** With TabOK positive, C describes the axes with -TAB look-up tables, which need LutMap. */
    @Test
    void noProjectionWithTabOkNeedsLutMap() {
        FitsChan fc = new FitsChan();
        fc.set("TabOK=1");
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> celestialAxes(fc, noProjectionFrameSet(), new double[] {100, 100}));
        assertTrue(e.getMessage().endsWith("needs LutMap"), e.getMessage());
    }

    /** ForceTab asks for -TAB even with a projection; without TabOK nothing is described. */
    @Test
    void forceTab() {
        FitsChan fc = new FitsChan();
        fc.set("ForceTab=1");
        Result r = celestialAxes(fc, tanFrameSet(), new double[] {100, 100});
        assertInstanceOf(UnitMap.class, r.map);
        assertNull(r.store.crval[0]);

        FitsChan tab = new FitsChan();
        tab.set("ForceTab=1, TabOK=1");
        assertThrows(
                UnsupportedOperationException.class, () -> celestialAxes(tab, tanFrameSet(), new double[] {100, 100}));
    }

    /** A SkyFrame whose Domain is not SKY is not celestial (C's {@code IsASkyFrame}). */
    @Test
    void aSkyFrameWithAnotherDomainIsNotCelestial() {
        SkyFrame sky = fk5();
        sky.setDomain("OTHER");
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        Result r = celestialAxes(frameSet(wm, sky));
        assertInstanceOf(UnitMap.class, r.map);
        assertFalse(r.axisDone[0]);
    }

    /** Without FitsAxisOrder {@code <auto>}, the FITS axes are kept whatever the rotation. */
    @Test
    void noSwapWithAFixedAxisOrder() {
        PermMap swap = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        FitsChan fc = new FitsChan();
        fc.set("FitsAxisOrder=<copy>");
        Result r = celestialAxes(fc, frameSet(new CmpMap(swap, wm, true), fk5()), new double[] {100, 100});
        assertArrayEquals(new int[] {0, 1}, r.wperm);
        assertEquals("RA---TAN", r.store.ctype[0]);
    }

    /**
     * A WcsMap in parallel with another Mapping: SplitMap2 makes a WcsMap with as many axes as the
     * parallel outputs, its axes moved by the outputs before it and its parameters with them, and
     * inverts it whatever the Invert value of the WcsMap found.
     */
    @Test
    void splitMap2LiftsAWcsMapInParallel() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__SIN, 2, 1);
        wm.setPV(0, 1, 0.1);
        CmpMap par = new CmpMap(new ZoomMap(1, 2.0), wm, false);
        FitsCelestialAxes.Split split = FitsCelestialAxes.splitMap2(par, false);
        assertNotNull(split);
        assertEquals(3, split.map2.getNin());
        assertEquals(WcsMap.AST__SIN, split.map2.getWcsType());
        assertEquals(3, split.map2.getWcsAxis(1));
        assertEquals(2, split.map2.getWcsAxis(2));
        assertEquals(0.1, split.map2.getPV(1, 1));
        assertTrue(split.map2.getInvert());
        assertEquals(3, split.map1.getNout());
        assertEquals(3, split.map3.getNin());
    }

    /** A WcsMap without FITSProj is not a projection. */
    @Test
    void aWcsMapWithoutFitsProjIsNotFound() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.setFITSProj(false);
        assertNull(FitsCelestialAxes.splitMap2(wm, true));
        WcsMap fp = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        FitsCelestialAxes.Split split = FitsCelestialAxes.splitMap2(fp, true);
        assertNotNull(split);
        assertTrue(split.map2.getInvert());
        assertInstanceOf(UnitMap.class, split.map1);
        assertInstanceOf(UnitMap.class, split.map3);
    }

    /** SplitMap needs no projection in the Mappings either side of the one it uses. */
    @Test
    void twoProjectionsAreRefused() {
        WcsMap a = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        a.invert();
        WcsMap b = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        b.invert();
        CmpMap two = new CmpMap(a, b, true);
        assertNotNull(FitsCelestialAxes.splitMap2(two, false));
        // Not linear, and the celestial outputs vary.
        assertNull(FitsCelestialAxes.splitMap(two, false, 0, 1));
    }

    /** A CmpMap with Ident set, which astMapList gives back whole, holds no WcsMap that is found. */
    @Test
    void anIdentCmpMapIsNotSearched() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        CmpMap cmp = new CmpMap(new ZoomMap(2, 0.001), wm, true);
        cmp.setIdent("keep");
        assertNull(FitsCelestialAxes.splitMap2(cmp, false));
    }
}
