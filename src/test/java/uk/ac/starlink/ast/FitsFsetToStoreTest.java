package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/mod.rs}, {@code
 * fitschan/wcs_write/plane_stores.rs} and {@code spectral_axes.rs} (C {@code FsetToStore}, {@code
 * AddVersion}, {@code MakeFitsFrameSet}, {@code FitsAxisOrder}, {@code ReadCrval} and {@code
 * SpectralAxes}, fitschan.c) that need no class this library lacks, and the store C fills for
 * FrameSets written by a C-oracle probe linked against the pinned C library: each expected value
 * is the card C writes from that store entry. C's cards are rounded (its {@code RoundFString}
 * removes runs of four or more 0s or 9s), so where the value of a card is rounded it is compared
 * to within 4 units in the last place.
 *
 * <p>Not translated: the {@code spectral_axes.rs} tests other than {@code
 * no_spectral_axis_returns_unitmap}, which need SpecFrame.
 */
class FitsFsetToStoreTest {

    /** A FrameSet from a GRID Frame to {@code current} through {@code map}. */
    private static FrameSet frameSet(Mapping map, Frame current) {
        Frame pixel = new Frame(map.getNin());
        pixel.setDomain("GRID");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, map, current);
        return fs;
    }

    private static Frame frame(int naxes, String settings) {
        Frame f = new Frame(naxes);
        f.set(settings);
        return f;
    }

    private static FitsPlaneStores fsetToStore(FitsChan fc, FrameSet fs, double... dim) {
        return FitsFsetToStore.fsetToStore(
                fc, fs, fs.getNin(), dim, FitsChan.Encoding.FITS_WCS, "FitsChan", "astWrite");
    }

    /** Whether {@code actual} is within 4 units in the last place of {@code expected}. */
    private static void assertUlp(double expected, Double actual) {
        assertNotNull(actual);
        assertEquals(expected, actual, 4 * Math.ulp(expected), "value " + actual);
    }

    /** {@link #assertUlp} for each element. */
    private static void assertUlp(double[] expected, Double[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertUlp(expected[i], actual[i]);
        }
    }

    /** The FrameSet of the probe's {@code basic_shift}, Rust's {@code basic_two_axis_shift}. */
    private static FrameSet basicShift() {
        return frameSet(
                new ShiftMap(new double[] {10, 20}),
                frame(2, "Domain=WORLD,Label(1)=wx,Label(2)=wy,Unit(1)=m,Unit(2)=m"));
    }

    @Test
    void basicTwoAxisShift() {
        FitsPlaneStores stores = fsetToStore(new FitsChan(), basicShift(), 100, 100);
        assertNotNull(stores);
        assertEquals(2, stores.naxis);
        FitsStore store = stores.get(' ');
        assertEquals("WORLD", store.wcsname);
        assertArrayEquals(new Double[] {50.0, 50.0}, store.crpix);
        assertArrayEquals(new Double[] {60.0, 70.0}, store.crval);
        assertArrayEquals(new String[] {"WORLD1", "WORLD2"}, store.ctype);
        assertArrayEquals(new String[] {"wx", "wy"}, store.ctypeCom);
        assertArrayEquals(new Double[] {1.0, 1.0}, store.cdelt);
        assertArrayEquals(new String[] {"m", "m"}, store.cunit);
        assertNull(store.pc[0][1]);
        assertNull(store.wcsaxes);
        for (char s = 'A'; s <= 'Z'; s++) {
            assertNull(stores.get(s));
        }
    }

    @Test
    void identityChainYieldsCentralPixelCrpix() {
        FitsStore store = fsetToStore(new FitsChan(), frameSet(new UnitMap(2), frame(2, "Domain=WORLD")), 100, 100)
                .get(' ');
        assertEquals(50.0, store.crpix[0], 1e-6);
        assertEquals(50.0, store.crpix[1], 1e-6);
    }

    @Test
    void noSpectralAxisReturnsUnitMap() {
        Mapping map = FitsFsetToStore.spectralAxes(basicShift());
        assertInstanceOf(UnitMap.class, map);
        assertEquals(2, map.getNin());
    }

    @Test
    void fitsAxisOrderSwapsTheAxes() {
        // C probe axisorder_swap and axisorder_extra_words: words naming no axis are passed over.
        for (String order : new String[] {"WORLD2 WORLD1", "foo WORLD2 bar world1"}) {
            FitsChan fc = new FitsChan();
            fc.setC("FitsAxisOrder", order);
            FitsStore store = fsetToStore(fc, basicShift(), 100, 100).get(' ');
            assertArrayEquals(new Double[] {70.0, 60.0}, store.crval, order);
            assertArrayEquals(new String[] {"WORLD2", "WORLD1"}, store.ctype, order);
            assertArrayEquals(new String[] {"wy", "wx"}, store.ctypeCom, order);
            assertEquals(0.0, store.pc[0][0], order);
            assertEquals(1.0, store.pc[0][1], order);
            assertEquals(1.0, store.pc[1][0], order);
            assertEquals(0.0, store.pc[1][1], order);
        }
    }

    @Test
    void fitsAxisOrderCopyKeepsTheFrameOrder() {
        FitsChan fc = new FitsChan();
        fc.setC("FitsAxisOrder", "<copy>");
        FitsStore store = fsetToStore(fc, basicShift(), 100, 100).get(' ');
        assertArrayEquals(new Double[] {60.0, 70.0}, store.crval);
    }

    @Test
    void fitsAxisOrderNamingAnAxisTwiceIsAnError() {
        FitsChan fc = new FitsChan();
        fc.setC("FitsAxisOrder", "WORLD1 world1 WORLD2");
        AstException e = assertThrows(AstException.class, () -> fsetToStore(fc, basicShift(), 100, 100));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertEquals(
                "astWrite(fitschan): attribute FitsAxisOrder (WORLD1 world1 WORLD2) refers to axis world1 more"
                        + " than once. (AST__ATTIN)",
                e.getMessage());
    }

    @Test
    void fitsAxisOrderLeavingOutAnAxisIsAnError() {
        FitsChan fc = new FitsChan();
        fc.setC("FitsAxisOrder", "WORLD2");
        AstException e = assertThrows(AstException.class, () -> fsetToStore(fc, basicShift(), 100, 100));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertEquals(
                "astWrite(fitschan): attribute FitsAxisOrder (WORLD2) does not specify a position for WCS axis"
                        + " 'WORLD1'. (AST__ATTIN)",
                e.getMessage());
    }

    @Test
    void primaryOnlyYieldsOnePlane() {
        FitsPlaneStores stores =
                fsetToStore(new FitsChan(), frameSet(new UnitMap(2), frame(2, "Domain=WORLD")), 100, 100);
        assertNotNull(stores.get(' '));
        for (char s = 'A'; s <= 'Z'; s++) {
            assertNull(stores.get(s));
        }
    }

    @Test
    void altFrameWithIdentAYieldsTwoPlanes() {
        FrameSet fs = frameSet(new UnitMap(2), frame(2, "Domain=WORLD"));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), frame(2, "Domain=WORLD2,Ident=A"));
        fs.setCurrent(2);
        FitsPlaneStores stores = fsetToStore(new FitsChan(), fs, 100, 100);
        assertEquals("WORLD", stores.get(' ').wcsname);
        assertEquals("WORLD2", stores.get('A').wcsname);
        for (char s = 'B'; s <= 'Z'; s++) {
            assertNull(stores.get(s));
        }
    }

    /** The FrameSet of the probe's {@code ident_letters}. */
    private static FrameSet identLetters() {
        FrameSet fs = frameSet(new ShiftMap(new double[] {10, 20}), frame(2, "Domain=WORLD"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), frame(2, "Domain=A3,Ident=Z"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 3.0), frame(2, "Domain=A4"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 4.0), frame(2, "Domain=IWCX"));
        fs.setCurrent(2);
        return fs;
    }

    @Test
    void altFramesTakeIdentLettersThenTheFreeOnes() {
        // C probe ident_letters: frame 3 takes its Ident Z, frame 4 the first free letter A, and
        // the IWC frame none.
        FitsPlaneStores stores = fsetToStore(new FitsChan(), identLetters(), 100, 100);
        FitsStore a = stores.get('A');
        assertEquals("A4", a.wcsname);
        assertUlp(new double[] {150.0, 150.0}, a.crval);
        assertUlp(new double[] {3.0, 3.0}, a.cdelt);
        assertArrayEquals(new String[] {"A41", "A42"}, a.ctype);
        FitsStore z = stores.get('Z');
        assertEquals("A3", z.wcsname);
        assertUlp(new double[] {100.0, 100.0}, z.crval);
        assertUlp(new double[] {2.0, 2.0}, z.cdelt);
        assertNull(stores.get('B'));
        assertEquals("WORLD", stores.get(' ').wcsname);
    }

    @Test
    void altAxesIdentUsesOnlyIdentLetters() {
        FitsChan fc = new FitsChan();
        fc.setC("AltAxes", "IDENT");
        FitsPlaneStores stores = fsetToStore(fc, identLetters(), 100, 100);
        assertNull(stores.get('A'));
        assertEquals("A3", stores.get('Z').wcsname);
    }

    @Test
    void altAxesNoneWritesOnlyThePrimary() {
        FitsChan fc = new FitsChan();
        fc.setC("AltAxes", "NONE");
        FitsPlaneStores stores = fsetToStore(fc, identLetters(), 100, 100);
        assertNotNull(stores.get(' '));
        for (char s = 'A'; s <= 'Z'; s++) {
            assertNull(stores.get(s));
        }
    }

    /** A 2-D PolyMap whose first output is a quadratic that is zero at pixel 50. */
    private static PolyMap zeroAtCentreQuadratic() {
        PolyMap pm = new PolyMap(2, 2, 3, new double[] {-25.0, 1, 0, 0, 0.01, 1, 2, 0, 1.0, 2, 0, 1}, 0, new double[0]);
        pm.set("IterInverse=1");
        return pm;
    }

    @Test
    void anAlternateThatFailsLeavesNoCrpix() {
        // C probe alt_fails: frame 3 cannot be described, so its version A gets no CRPIX (and C
        // writes no cards for it); frame 4 is B.
        FrameSet fs = frameSet(new ShiftMap(new double[] {10, 20}), frame(2, "Domain=WORLD"));
        fs.addFrame(FrameSet.AST__BASE, zeroAtCentreQuadratic(), frame(2, "Domain=BAD"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 3.0), frame(2, "Domain=GOOD"));
        fs.setCurrent(2);
        FitsPlaneStores stores = fsetToStore(new FitsChan(), fs, 100, 100);
        FitsStore a = stores.get('A');
        assertNotNull(a);
        assertNull(a.crpix[0]);
        assertEquals(0.0, a.crval[0]);
        assertNull(a.wcsname);
        FitsStore b = stores.get('B');
        assertEquals("GOOD", b.wcsname);
        assertUlp(new double[] {150.0, 150.0}, b.crval);
    }

    @Test
    void aPrimaryThatFailsGivesNoStore() {
        // C probe primary_fails: the write returns 0.
        FrameSet fs = frameSet(zeroAtCentreQuadratic(), frame(2, "Domain=BAD"));
        assertNull(fsetToStore(new FitsChan(), fs, 100, 100));
    }

    @Test
    void aMappingWithNoInverseNeedsTabOk() {
        PolyMap pm = new PolyMap(1, 1, 1, new double[] {2.0, 1, 1}, 0, new double[0]);
        pm.set("IterInverse=0");
        assertFalse(pm.getTranInverse());
        assertNull(fsetToStore(new FitsChan(), frameSet(pm, new Frame(1)), 100));
    }

    @Test
    void noDomainGivesNoWcsname() {
        // C probe no_domain_small_crval.
        FitsStore store = fsetToStore(new FitsChan(), frameSet(new ZoomMap(2, 1.0e-9), new Frame(2)), 100, 100)
                .get(' ');
        assertNull(store.wcsname);
        assertArrayEquals(new String[] {"x1", "x2"}, store.ctype);
        assertUlp(5.0e-8, store.crval[0]);
        assertUlp(1.0e-9, store.cdelt[0]);
    }

    @Test
    void aCrvalSmallAgainstThePixelSizeIsZero() {
        // AddVersion: |CRVAL| below sqrt(DBL_EPSILON) times the pixel size becomes zero; the
        // centre pixel 50 maps to 1e-10 here, against a pixel size of 1.
        FrameSet fs = frameSet(new ShiftMap(new double[] {-50 + 1.0e-10}), new Frame(1));
        FitsStore store = fsetToStore(new FitsChan(), fs, 100).get(' ');
        assertEquals(0.0, store.crval[0]);
        FrameSet big = frameSet(new ShiftMap(new double[] {-50 + 1.0e-7}), new Frame(1));
        assertTrue(fsetToStore(new FitsChan(), big, 100).get(' ').crval[0] > 0.0);
    }

    /** A FitsChan holding a 1-D header whose reference value is 0.1 at pixel 1, 7 per pixel. */
    private static FitsChan oneAxisHeader() {
        return FitsChan.fromHeader(String.join(
                "\n", "NAXIS   = 1", "NAXIS1  = 100", "CTYPE1  = 'X'", "CRPIX1  = 1", "CRVAL1  = 0.1", "CDELT1  = 7"));
    }

    @Test
    void crpixIsRoundedToAMillionthOfAPixel() {
        // The FrameSet read from the FitsChan, with its pixel coordinates shifted by 1/7, written
        // back: the reference value 0.1 the FitsChan holds is at pixel 1 + 1/7 = 1.142857142...,
        // which AddVersion rounds to 1.142857.
        FitsChan fc = oneAxisHeader();
        FrameSet fs = (FrameSet) fc.read();
        fs.remapFrame(FrameSet.AST__BASE, new ShiftMap(new double[] {1.0 / 7}));
        FitsStore store = fsetToStore(fc, fs, 100).get(' ');
        assertEquals(0.1, store.crval[0]);
        assertEquals(Math.floor((1 + 1.0 / 7) * 1.0e6 + 0.5) * 1.0e-6, store.crpix[0]);
    }

    @Test
    void aReferencePointThatCannotBeConvertedIsNotUsed() {
        // C probe winmap_naxis: a FrameSet to a plain Frame written to a FitsChan holding a
        // reference value cannot convert the FitsChan's Frame to its own, and takes the centre
        // pixel 50 as reference, with the value 0.3 there.
        FitsChan fc = FitsChan.fromHeader(
                String.join("\n", "NAXIS1  = 100", "CTYPE1  = 'X'", "CRPIX1  = 1", "CRVAL1  = 0.1"));
        FrameSet fs = frameSet(
                new WinMap(1, new double[] {0}, new double[] {1}, new double[] {-349.7}, new double[] {-342.7}),
                new Frame(1));
        assertNull(FitsFsetToStore.readCrval(fc, fs.getFrame(FrameSet.AST__CURRENT), ' ', "FitsChan", "astWrite"));
        FitsStore store = fsetToStore(fc, fs, 100).get(' ');
        assertEquals(0.3, store.crval[0], 1.0e-12);
        assertArrayEquals(new Double[] {50.0}, store.crpix);
        assertUlp(7.0, store.cdelt[0]);
        assertArrayEquals(new String[] {"x1"}, store.ctype);
    }

    @Test
    void dut1AndDtaiAreStoredForThePrimary() {
        Frame w = frame(2, "Domain=WORLD,Dut1=0.5,Dtai=37");
        FitsPlaneStores stores = fsetToStore(new FitsChan(), frameSet(new UnitMap(2), w), 100, 100);
        assertEquals(0.5 / 86400.0, stores.get(' ').dut1);
        assertEquals(37.0, stores.get(' ').dtai);
    }

    @Test
    void aNonSkyDomainSkyFrameIsRemappedToDegrees() {
        // C probe foo_skyframe: MakeFitsFrameSet maps the radians of a SkyFrame whose Domain is
        // not SKY into its external degrees, and OtherAxes describes its axes as linear.
        SkyFrame sky = new SkyFrame();
        sky.setDomain("FOO");
        FitsStore store = fsetToStore(new FitsChan(), frameSet(new ZoomMap(2, 0.001), sky), 100, 100)
                .get(' ');
        assertEquals("FOO", store.wcsname);
        assertArrayEquals(new Double[] {50.0, 50.0}, store.crpix);
        assertUlp(2.8647889756541165, store.crval[0]);
        assertUlp(2.8647889756541165, store.crval[1]);
        assertArrayEquals(new String[] {"RA", "Dec"}, store.ctype);
        assertEquals("Type of co-ordinate on axis 1", store.ctypeCom[0]); // codespell:ignore
        assertEquals("Type of co-ordinate on axis 2", store.ctypeCom[1]); // codespell:ignore
        assertUlp(0.057295779513082325, store.cdelt[0]);
        assertUlp(0.057295779513082325, store.cdelt[1]);
        assertArrayEquals(new String[] {"deg", "deg"}, store.cunit);
    }

    @Test
    void twoSkyFramesCannotBeDescribed() {
        // MakeFitsFrameSet finds two SkyFrames and makes no FrameSet; C then passes the NULL
        // FrameSet to AddEncodingFrame, which crashes (C probe: EXC_BAD_ACCESS in astGetFrame_).
        SkyFrame gal = new SkyFrame();
        gal.setSystem("Galactic");
        CmpFrame cf = new CmpFrame(new SkyFrame(), gal);
        FrameSet fs = frameSet(new ZoomMap(4, 0.001), cf);
        assertNull(FitsFsetToStore.makeFitsFrameSet(
                new FitsChan(), fs, 1, 2, FitsChan.Encoding.FITS_WCS, "FitsChan", "astWrite"));
        assertNull(fsetToStore(new FitsChan(), fs, 100, 100, 100, 100));
    }

    @Test
    void theReferencePointAlreadyInTheFitsChanIsUsed() {
        // C probe readcrval: the FrameSet read from the FitsChan, written back to it, keeps
        // CRVAL1 7.5 at CRPIX1 1.
        FitsChan fc = FitsChan.fromHeader(String.join(
                "\n", "NAXIS   = 1", "NAXIS1  = 100", "CTYPE1  = 'X'", "CRPIX1  = 1", "CRVAL1  = 7.5", "CDELT1  = 1"));
        FrameSet fs = (FrameSet) fc.read();
        FitsStore store = fsetToStore(fc, fs, 100).get(' ');
        assertArrayEquals(new Double[] {7.5}, store.crval);
        assertArrayEquals(new Double[] {1.0}, store.crpix);
        assertArrayEquals(new String[] {"X"}, store.ctype);
        assertUlp(1.0, store.cdelt[0]);
    }

    @Test
    void readCrvalGivesNullWithoutCrvalCards() {
        assertNull(FitsFsetToStore.readCrval(new FitsChan(), new Frame(1), ' ', "FitsChan", "astWrite"));
    }

    /** The FrameSet of the probe's {@code offset_sky}: a TAN header with an offset SkyFrame. */
    private static FrameSet offsetSky() {
        FitsChan rfc = FitsChan.fromHeader(String.join(
                "\n",
                "NAXIS   = 2",
                "NAXIS1  = 100",
                "NAXIS2  = 100",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "CRPIX1  = 50",
                "CRPIX2  = 50",
                "CRVAL1  = 30",
                "CRVAL2  = 40",
                "CDELT1  = -0.001",
                "CDELT2  = 0.001",
                "RADESYS = 'FK5'",
                "EQUINOX = 2000.0"));
        FrameSet fs = (FrameSet) rfc.read();
        fs.set("SkyRef(1)=0.5,SkyRef(2)=0.7,SkyRefIs=Origin");
        return fs;
    }

    @Test
    void anOffsetSkyFrameAlsoGetsAnAbsoluteDescription() {
        // C probe offset_sky.
        FrameSet fs = offsetSky();
        FitsPlaneStores stores = fsetToStore(new FitsChan(), fs, 100, 100);
        FitsStore p = stores.get(' ');
        assertArrayEquals(new Double[] {50.0, 50.0}, p.crpix);
        // The offset coordinates this FrameSet gives at the centre pixel differ from C's by up
        // to 20 units in the last place, before any FITS description is made (C probe: astTran2
        // of pixel 50, 50 gives 1.0357383636595399 degrees, this library 1.0357383636595365).
        assertEquals(1.0357383636595399, p.crval[0], 1.0e-14);
        assertEquals(-0.0991726866058769, p.crval[1], 1.0e-14);
        assertArrayEquals(new String[] {"OFLN-TAN", "OFLT-TAN"}, p.ctype);
        assertEquals("RA offset from 1:54:35.5, 40:06:25", p.ctypeCom[0]);
        assertEquals(-10.0e-4, p.cdelt[0], 1.0e-14);
        assertEquals(0.001, p.cdelt[1], 1.0e-14);
        assertEquals(0.9998844526457898, p.pc[0][0], 1.0e-14);
        assertEquals(0.01520136037429563, p.pc[0][1], 1.0e-14);
        assertEquals(-0.015201360374295859, p.pc[1][0], 1.0e-14);
        assertEquals(0.9998844526457898, p.pc[1][1], 1.0e-14);
        assertEquals("FK5", p.radesys);
        assertEquals(2000.0, p.equinox);

        FitsStore a = stores.get('A');
        assertArrayEquals(new Double[] {50.0, 50.0}, a.crpix);
        assertUlp(30.0, a.crval[0]);
        assertUlp(40.0, a.crval[1]);
        assertArrayEquals(new String[] {"RA---TAN", "DEC--TAN"}, a.ctype);
        assertUlp(-0.001, a.cdelt[0]);
        assertUlp(0.001, a.cdelt[1]);
        assertEquals("ORIGIN", a.skyrefis);
        assertEquals("FK5", a.radesys);
        assertNull(stores.get('B'));

        // SkyRefIs is restored on the caller's FrameSet.
        assertEquals("Origin", fs.getC("SkyRefIs"));
    }

    @Test
    void theAbsoluteDescriptionsLetterIsNotCheckedAgainstIdentLetters() {
        // C probe offset_sky_collide: the absolute description of the offset primary takes the
        // letter after the last one handed out, A, which frame 3 also has as its Ident; both
        // descriptions are stored as version A, the second overwriting what both store.
        FrameSet fs = offsetSky();
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 3.0), frame(2, "Domain=A3,Ident=A"));
        fs.setCurrent(2);
        FitsStore a = fsetToStore(new FitsChan(), fs, 100, 100).get('A');
        assertEquals("A3", a.wcsname);
        assertArrayEquals(new Double[] {50.0, 50.0}, a.crpix);
        assertUlp(new double[] {150.0, 150.0}, a.crval);
        assertArrayEquals(new String[] {"A31", "A32"}, a.ctype);
        assertUlp(new double[] {3.0, 3.0}, a.cdelt);
        // Left by the absolute sky description.
        assertEquals("ORIGIN", a.skyrefis);
        assertEquals("FK5", a.radesys);
        assertEquals(2000.0, a.equinox);
    }

    @Test
    void planeStoreIndexRejectsOtherCharacters() {
        FitsPlaneStores stores = new FitsPlaneStores(2);
        AstException e = assertThrows(AstException.class, () -> stores.get('a'));
        assertEquals(AstException.AST__INTER, e.getStatus());
    }

    @Test
    void aStoreUsedTwiceGrowsToTheLargerAxisCount() {
        FitsPlaneStores stores = new FitsPlaneStores(1);
        FitsStore s1 = stores.version('A', 1);
        s1.crval[0] = 1.0;
        FitsStore s2 = stores.version('A', 3);
        assertSame(s1, s2);
        assertEquals(3, s2.crval.length);
        assertEquals(1.0, s2.crval[0]);
        assertEquals(3, s2.pc[2].length);
    }
}
