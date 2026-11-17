package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link FitsWcsSpectral}, C's {@code WcsSpectral}: the unit tests of Rust {@code
 * fitschan/wcs_spectral.rs} ({@code mod tests}), which build FitsStores directly, and reads of
 * spectral headers through a FitsChan compared with what C's {@code astRead} gives for the same
 * cards (the SpecFrame's attributes as {@code astGetC} formats them, the pixel to world transform,
 * and the error status and messages), including the read half of the plan's Review Focus 5: a
 * spectral axis whose Mapping needs a class that is not available ({@code -GRI} through
 * GrismMap) fails with {@code needs <Class>}. A {@code -TAB} axis with TabOK
 * set fails as in C, since the FitsChan holds no table.
 */
class FitsWcsSpectralTest {

    // ------------------------------------------------------------------
    // fitschan/wcs_spectral.rs
    // ------------------------------------------------------------------

    /** Rust's {@code build_spectral} on {@code store}, with no celestial axes. */
    private static FitsWcsSpectral.Result spectral(FitsStore store) {
        return FitsWcsSpectral.wcsSpectral(
                store, ' ', new Frame(store.naxis), new String[store.naxis], null, new FitsWarnings());
    }

    /** The SpecFrame the spectral stage put on {@code axis} (from 1) of its Frame. */
    private static SpecFrame specFrameOn(FitsWcsSpectral.Result result, int axis) {
        Frame primary = result.frame.primaryFrame(axis, new int[1]);
        assertInstanceOf(SpecFrame.class, primary, "axis " + axis + " is in a " + primary.astClass());
        return (SpecFrame) primary;
    }

    @Test
    void detectsFreqAxis() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.ctype[2] = "FREQ";
        store.crval[2] = 1.4e9;
        FitsWcsSpectral.Result result = spectral(store);
        assertEquals("FREQ", specFrameOn(result, 3).getC("System"));
        assertEquals(3, result.map.getNin());
        assertEquals(3, result.map.getNout());
        assertFalse(result.frame.primaryFrame(1, new int[1]) instanceof SpecFrame);
    }

    @Test
    void detectsWaveAxis() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "WAVE";
        store.crval[0] = 5.0e-7;
        assertEquals("WAVE", specFrameOn(spectral(store), 1).getC("System"));
    }

    @Test
    void detectsVradAxis() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "VRAD";
        store.crval[0] = 0.0;
        assertEquals("VRAD", specFrameOn(spectral(store), 1).getC("System"));
    }

    @Test
    void setsStdOfRest() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "FREQ";
        store.crval[0] = 1.0e9;
        store.specsys = "LSRK";
        assertEquals("LSRK", specFrameOn(spectral(store), 1).getStdOfRest());
    }

    @Test
    void setsRestFreq() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "VRAD";
        store.crval[0] = 0.0;
        store.restfrq = 1.420405751786e9;
        assertEquals(1.420405751786e9, specFrameOn(spectral(store), 1).restFreqValue(), 1.0);
    }

    @Test
    void noSpectralReturnsUnitMapsAndTheFrame() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        Frame frame = new Frame(2);
        FitsWcsSpectral.Result result =
                FitsWcsSpectral.wcsSpectral(store, ' ', frame, new String[2], null, new FitsWarnings());
        assertSame(frame, result.frame);
        double[] out = FitsHeaders.tran(result.map, new double[] {3.0, 4.0}, true);
        assertEquals(3.0, out[0]);
        assertEquals(4.0, out[1]);
    }

    @Test
    void crvalShiftMapping() {
        FitsStore store = new FitsStore(1);
        store.ctype[0] = "FREQ";
        store.crval[0] = 1.4e9;
        Mapping map = spectral(store).map;
        assertEquals(1.4e9, FitsHeaders.tran(map, new double[] {0.0}, true)[0], 1.0);
        assertEquals(1.4e9 + 1.0e6, FitsHeaders.tran(map, new double[] {1.0e6}, true)[0], 1.0);
    }

    @Test
    void everySpectralAxisInAxisOrder() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "WAVE";
        store.ctype[1] = "RA---TAN";
        store.ctype[2] = "FREQ";
        store.crval[0] = 1.0e-6;
        store.crval[2] = 1.4e9;
        String[] iwcUnits = new String[3];
        FitsWcsSpectral.Result result =
                FitsWcsSpectral.wcsSpectral(store, ' ', new Frame(3), iwcUnits, null, new FitsWarnings());
        assertEquals("WAVE", specFrameOn(result, 1).getC("System"));
        assertEquals("FREQ", specFrameOn(result, 3).getC("System"));
        assertFalse(result.frame.primaryFrame(2, new int[1]) instanceof SpecFrame);
        // Each spectral axis's IWC unit is the default unit of its system.
        assertEquals(Arrays.asList("m", null, "Hz"), Arrays.asList(iwcUnits));
    }

    @Test
    void algorithmCodeIsAtMostFourCharactersWithoutTrailingSpaces() {
        assertEquals("", FitsWcsSpectral.algorithmCode("FREQ"));
        assertEquals("", FitsWcsSpectral.algorithmCode("FREQ    "));
        assertEquals("-F2W", FitsWcsSpectral.algorithmCode("FREQ-F2W"));
        assertEquals("-F2W", FitsWcsSpectral.algorithmCode("FREQ-F2WXYZ"));
        assertEquals("-LOG", FitsWcsSpectral.algorithmCode("FREQ-LOG  "));
    }

    // ------------------------------------------------------------------
    // Headers read through a FitsChan, against C's astRead
    // ------------------------------------------------------------------

    /** The cards every one-axis header below starts with. */
    private static final String[] COMMON = {"NAXIS   = 1", "NAXIS1  = 10", "CRPIX1  = 1", "CDELT1  = 1.0E6"};

    /** The one-axis header of {@link #COMMON} followed by {@code cards}. */
    private static FitsChan oneAxis(String... cards) {
        String[] all = Arrays.copyOf(COMMON, COMMON.length + cards.length);
        System.arraycopy(cards, 0, all, COMMON.length, cards.length);
        return FitsHeaders.chan(all);
    }

    /** The attributes C's probe prints for the Frame of a spectral axis, in its order. */
    private static final String[] ATTRS = {
        "Class",
        "System",
        "Unit(1)",
        "Label(1)",
        "Domain",
        "StdOfRest",
        "SourceVRF",
        "SourceSys",
        "SourceVel",
        "RestFreq",
        "RefRA",
        "RefDec",
        "Epoch",
        "ObsLon",
        "ObsLat",
        "ObsAlt"
    };

    /**
     * Checks the Frame of axis {@code axis} (from 1) of {@code fs}, picked from its current Frame,
     * against C's attribute values, {@code want} holding one value for each of {@link #ATTRS}
     * (the first five for a Frame that is not a SpecFrame) and then, for a DSBSpecFrame, the
     * DSBCentre, IF, SideBand and ImagFreq.
     */
    private static void assertAttributes(FrameSet fs, int axis, String... want) {
        Frame f = fs.pickAxes(1, new int[] {axis}, null);
        int n = 0;
        for (int i = 0; i < ATTRS.length && n < want.length; i++) {
            if (i >= 5 && !(f instanceof SpecFrame)) {
                break;
            }
            String attr = ATTRS[i];
            String got = "Class".equals(attr) ? f.astClass() : f.getC(attr);
            assertEquals(want[n++], got, attr);
        }
        if (f instanceof DSBSpecFrame) {
            for (String attr : new String[] {"DSBCentre", "IF", "SideBand", "ImagFreq"}) {
                assertEquals(want[n++], f.getC(attr), attr);
            }
        }
        assertEquals(want.length, n, "attributes compared");
    }

    /**
     * Checks the transform of the pixel positions (1, ...) and (7.5, ...) by {@code fs} against
     * C's, given as one row per position.
     */
    private static void assertForward(FrameSet fs, double[]... want) {
        int naxes = fs.getNin();
        for (int p = 0; p < want.length; p++) {
            double[] in = new double[naxes];
            Arrays.fill(in, p == 0 ? 1.0 : 7.5);
            double[] out = FitsHeaders.tran(fs, in, true);
            for (int k = 0; k < naxes; k++) {
                assertEquals(want[p][k], out[k], Math.abs(want[p][k]) * 1e-13, "point " + p + " axis " + (k + 1));
            }
        }
    }

    /** The current Frame's class, Domain and Title. */
    private static void assertCurrent(FrameSet fs, String cls, String domain, String title) {
        Frame cur = fs.getFrame(FrameSet.AST__CURRENT);
        assertEquals(cls, cur.astClass());
        assertEquals(domain, cur.getDomain());
        assertEquals(title, cur.getTitle());
    }

    /**
     * A DSBSpecFrame, from IMAGFREQ, with every keyword WcsSpectral sets on it: the reference
     * position from the celestial axes, the Epoch from MJD-OBS, the observer from OBSGEO, the
     * source velocity from SSYSSRC and ZSOURCE, the standard of rest, the rest frequency and the
     * Label from CNAME; DSBSetUp then sets the DSBCentre, IF and SideBand.
     */
    @Test
    void dsbSpecFrameTakesEveryKeywordAsC() {
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.chan(
                "NAXIS   = 3",
                "NAXIS1  = 10",
                "NAXIS2  = 10",
                "NAXIS3  = 10",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "CTYPE3  = 'FREQ'",
                "CRPIX1  = 5",
                "CRPIX2  = 5",
                "CRPIX3  = 3",
                "CRVAL1  = 83.6",
                "CRVAL2  = 22.0",
                "CRVAL3  = 2.30E11",
                "CDELT1  = -0.001",
                "CDELT2  = 0.001",
                "CDELT3  = 1.0E6",
                "CNAME3  = 'Sky frequency'",
                "RADESYS = 'FK5'",
                "EQUINOX = 2000.0",
                "MJD-OBS = 53000.25",
                "SPECSYS = 'LSRK'",
                "SSYSSRC = 'BARYCENT'",
                "ZSOURCE = 0.01",
                "RESTFRQ = 2.31E11",
                "IMAGFREQ= 2.40E11",
                "OBSGEO-X= -5464588.868",
                "OBSGEO-Y= -2493001.1635",
                "OBSGEO-Z= 2150654.3527"));
        assertAttributes(
                fs,
                3,
                "DSBSpecFrame",
                "FREQ",
                "Hz",
                "Sky frequency",
                "DSBSPECTRUM",
                "LSRK",
                "Barycentric",
                "VELO",
                "2982.9356991238064",
                "231.00000000000003",
                "5:34:24.0",
                "22:00:00",
                "2003.9856283172294",
                "W155:28:37.36",
                "N19:49:22.19",
                "4123.7639304737932",
                "230000000000.00003",
                "3.177611669601685",
                "LSB",
                "240.00000000000006");
        assertCurrent(fs, "CmpFrame", "SKY-DSBSPECTRUM", "3-d compound coordinate system");
        assertForward(fs, new double[] {1.4591705484727806, 0.3839026212843969, 229998000000.0}, new double[] {
            1.4590481938817685, 0.384016068285372, 230004500000.0
        });
    }

    /** The rest frequency from RESTWAV, and a CUNIT that is not the default unit. */
    @Test
    void restWavelengthGivesTheRestFrequency() {
        FrameSet fs = FitsHeaders.readFrameSet(oneAxis(
                "CTYPE1  = 'VOPT'", "CRVAL1  = 1.5E6", "CUNIT1  = 'km/s'", "RESTWAV = 2.1E-1", "SPECSYS = 'BARYCENT'"));
        assertAttributes(
                fs,
                1,
                "SpecFrame",
                "VOPT",
                "km/s",
                "Optical velocity",
                "SPECTRUM",
                "Barycentric",
                "Heliocentric",
                "VELO",
                "0",
                "1.4275831333333335",
                "0:00:00.0",
                "0:00:00",
                "2000.0",
                "E0:00:00.00",
                "N0:00:00.00",
                "0");
        assertCurrent(fs, "SpecFrame", "SPECTRUM", "Optical velocity (Barycentric), rest frequency = 1.42758 GHz");
        assertForward(fs, new double[] {1500000.0}, new double[] {8000000.0});
    }

    /** A {@code -F2W} axis: wavelength, sampled linearly in frequency (C's NonLinSpecWcs). */
    @Test
    void wavelengthSampledInFrequency() {
        FrameSet fs = FitsHeaders.readFrameSet(
                oneAxis("CTYPE1  = 'WAVE-F2W'", "CRVAL1  = 0.21", "CDELT1  = 1.0E-5", "RESTFRQ = 1.42E9"));
        assertEquals("WAVE", fs.getC("System"));
        assertEquals("1.4200000000000002", fs.getC("RestFreq"));
        assertCurrent(fs, "SpecFrame", "SPECTRUM", "Wavelength");
        assertForward(fs, new double[] {0.20999999999999996}, new double[] {0.21006502012527684});
    }

    /** A {@code -V2F} axis: radio velocity, sampled linearly in apparent radial velocity. */
    @Test
    void radioVelocitySampledInApparentVelocity() {
        FrameSet fs = FitsHeaders.readFrameSet(oneAxis("CTYPE1  = 'VRAD-V2F'", "CRVAL1  = 1.0E5", "RESTFRQ = 1.42E9"));
        assertCurrent(fs, "SpecFrame", "SPECTRUM", "Radio velocity, rest frequency = 1.42 GHz");
        assertForward(fs, new double[] {99999.99999999505}, new double[] {6531061.606396163});
    }

    /** ZOPT's default unit is blank, and C sets that blank Unit. */
    @Test
    void redshiftHasABlankDefaultUnit() {
        FrameSet fs = FitsHeaders.readFrameSet(oneAxis("CTYPE1  = 'ZOPT'", "CRVAL1  = 0.5", "CDELT1  = 0.01"));
        assertAttributes(fs, 1, "SpecFrame", "ZOPT", "", "Redshift", "SPECTRUM");
        assertCurrent(fs, "SpecFrame", "SPECTRUM", "Redshift, rest frequency = 100000 GHz");
        assertForward(fs, new double[] {0.5}, new double[] {0.565});
    }

    /** C's IsSpectral compares the system code case by case, so a lower-case CTYPE is a linear axis. */
    @Test
    void lowerCaseSystemIsALinearAxis() {
        FrameSet fs = FitsHeaders.readFrameSet(oneAxis("CTYPE1  = 'freq'", "CRVAL1  = 1.4E9"));
        assertAttributes(fs, 1, "Frame", "Cartesian", "", "freq", "FREQ");
        assertCurrent(fs, "Frame", "FREQ", "1-d coordinate system");
        assertForward(fs, new double[] {1400000000.0}, new double[] {1406500000.0});
    }

    /** Two spectral axes, each its own SpecFrame; with no other axes the WCSNAME is not used. */
    @Test
    void twoSpectralAxesIgnoreTheWcsname() {
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.chan(
                "NAXIS   = 2",
                "NAXIS1  = 10",
                "NAXIS2  = 10",
                "CRPIX1  = 1",
                "CRPIX2  = 1",
                "CTYPE1  = 'WAVE'",
                "CTYPE2  = 'FREQ'",
                "CRVAL1  = 1.0E-6",
                "CRVAL2  = 1.4E9",
                "CDELT1  = 1.0E-9",
                "CDELT2  = 1.0E6",
                "WCSNAME = 'TWO'"));
        assertAttributes(fs, 2, "SpecFrame", "FREQ", "Hz", "Frequency", "SPECTRUM");
        assertAttributes(fs, 1, "SpecFrame", "WAVE", "m", "Wavelength", "SPECTRUM");
        assertCurrent(fs, "CmpFrame", "SPECTRUM-SPECTRUM", "2-d compound coordinate system");
        assertForward(fs, new double[] {1.0e-6, 1400000000.0}, new double[] {1.0064999999999998e-06, 1406500000.0});
    }

    /** A spectral axis and another axis: the WCSNAME names the CmpFrame. */
    @Test
    void wcsnameNamesTheFrameWithAnotherAxis() {
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.chan(
                "NAXIS   = 2",
                "NAXIS1  = 10",
                "NAXIS2  = 4",
                "CRPIX1  = 1",
                "CRPIX2  = 1",
                "CTYPE1  = 'FREQ'",
                "CTYPE2  = 'STOKES'",
                "CRVAL1  = 1.4E9",
                "CRVAL2  = 1",
                "CDELT1  = 1.0E6",
                "CDELT2  = 1",
                "WCSNAME = 'NAMED'"));
        assertCurrent(fs, "CmpFrame", "NAMED", "NAMED coordinates");
        assertForward(fs, new double[] {1400000000.0, 1.0}, new double[] {1406500000.0, 7.5});
    }

    /** The error C's read reports for an algorithm code it cannot implement on axis 1. */
    private static void assertCannotImplement(String code, FitsChan fc) {
        AstException e = assertThrows(AstException.class, fc::read);
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertEquals(
                "astRead(FitsChan): Cannot implement spectral algorithm code '" + code
                        + "' specified in FITS keyword 'CTYPE1'.\nastRead(FitsChan): Unknown algorithm code or"
                        + " unusable parameter values.",
                e.getMsgText());
    }

    @Test
    void unknownAlgorithmCodeIsAnError() {
        assertCannotImplement("-XYZ", oneAxis("CTYPE1  = 'FREQ-XYZ'", "CRVAL1  = 1.4E9"));
    }

    @Test
    void unknownLinearSystemOfAnX2pCodeIsAnError() {
        assertCannotImplement("-Q2F", oneAxis("CTYPE1  = 'FREQ-Q2F'", "CRVAL1  = 1.4E9"));
    }

    /** A {@code -LOG} spectral axis with a zero CRVAL has no logarithmic form, and C fails. */
    @Test
    void logAxisWithZeroCrvalIsAnError() {
        assertCannotImplement("-LOG", oneAxis("CTYPE1  = 'FREQ-LOG'", "CRVAL1  = 0.0"));
    }

    /** With TabOK not above zero C reads no table, and the spectral stage fails at {@code -TAB}. */
    @Test
    void tabAxisWithoutTabOkIsAnError() {
        assertCannotImplement("-TAB", oneAxis("CTYPE1  = 'WAVE-TAB'", "CRVAL1  = 1.4E9"));
    }

    /** C sets SPECSYS as the StdOfRest attribute, which refuses a value it does not know. */
    @Test
    void unknownSpecsysIsAnError() {
        AstException e = assertThrows(
                AstException.class,
                () -> oneAxis("CTYPE1  = 'FREQ'", "CRVAL1  = 1.4E9", "SPECSYS = 'BOGUS'")
                        .read());
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertEquals("astSetAttrib(SpecFrame): Invalid standard of rest description \"BOGUS\".", e.getMsgText());
    }

    /** C sets SSYSSRC as the SourceVRF attribute, which refuses a value it does not know. */
    @Test
    void unknownSsyssrcIsAnError() {
        AstException e = assertThrows(
                AstException.class,
                () -> oneAxis("CTYPE1  = 'FREQ'", "CRVAL1  = 1.4E9", "SSYSSRC = 'BOGUS'")
                        .read());
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertEquals("astSetAttrib(SpecFrame): Invalid standard of rest description \"BOGUS\".", e.getMsgText());
    }

    // ------------------------------------------------------------------
    // Review Focus 5, read half: spectral axes needing absent classes
    // ------------------------------------------------------------------

    /**
     * C maps a {@code -LOG} spectral axis with a non-zero CRVAL with LogWcs's MathMap {@code
     * s=Sr*exp(w/Sr)}, Sr the CRVAL, written with C's {@code %.17g}.
     */
    @Test
    void logSpectralAxisIsAMathMap() {
        FrameSet fs =
                (FrameSet) oneAxis("CTYPE1  = 'FREQ-LOG'", "CRVAL1  = 1.4E9").read();
        String dump = ChannelText.dump(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT));
        assertTrue(dump.contains("Fwd1 = \"s=1400000000*exp(w/1400000000)\""), dump);
        assertTrue(dump.contains("Inv1 = \"w=1400000000*log(s/1400000000)\""), dump);
        assertTrue(dump.contains("SimpFI = 1") && dump.contains("SimpIF = 1"), dump);
    }

    /** C maps a {@code -GRI} axis with a GrismMap once the SpecFrame converts to wavelength. */
    @Test
    void grismAxisNeedsGrismMap() {
        for (String ctype : new String[] {"WAVE-GRI", "WAVE-GRA"}) {
            UnsupportedOperationException e = assertThrows(
                    UnsupportedOperationException.class,
                    () -> oneAxis(
                                    "CTYPE1  = '" + ctype + "'",
                                    "CRVAL1  = 5.0E-7",
                                    "CDELT1  = 1.0E-10",
                                    "PV1_0   = 3.0E5",
                                    "PV1_1   = 1")
                            .read());
            assertTrue(e.getMessage().endsWith(" needs GrismMap"), e.getMessage());
        }
    }

    /**
     * With TabOK above zero C reads a {@code -TAB} spectral axis through a LutMap made from the
     * table the FitsChan holds; it holds none, so the read fails as C's does (probe {@code
     * probes/lutmap_probe.c}, case {@code tab_spectral}).
     */
    @Test
    void tabSpectralAxisWithTabOkNeedsItsTable() {
        FitsChan fc = oneAxis("CTYPE1  = 'WAVE-TAB'", "CRVAL1  = 1.4E9", "PS1_0   = 'WCS-TAB'");
        fc.setI("TabOK", 1);
        AstException e = assertThrows(AstException.class, fc::read);
        assertEquals(AstException.AST__NOTAB, e.getStatus());
        assertEquals(
                "astRead(FitsChan): Failed to read FITS binary table from extension 'WCS-TAB' (extver=1,"
                        + " extlevel=1).",
                e.getMsgText());
    }

    /**
     * A header with CRVAL1 but no CRVAL2: C's {@code WcsCelestial} returns the reference longitude
     * and latitude separately, and {@code WcsSpectral} passes both to {@code astSetRefPos}, so the
     * SpecFrame takes RefRA from CRVAL1 and leaves RefDec unset. C's values: RefRA 10:00:00.0,
     * RefDec 0:00:00 (unset), the barycentric velocity 0 m/s converts to -8899.1984878203566 m/s
     * LSRK, and a FITS-WCS write gives VELOSYS 28372.571560485387 m/s.
     */
    @Test
    void oneCelestialReferenceValueSetsOneReferenceCoordinate() {
        FitsChan fc = FitsHeaders.chan(
                "NAXIS1  =                  100",
                "NAXIS2  =                  100",
                "NAXIS3  =                 1024",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "CTYPE3  = 'VRAD    '",
                "CRVAL1  =              150.000",
                "CRVAL3  =                  0.0",
                "CRPIX1  =               50.500",
                "CRPIX2  =               50.500",
                "CRPIX3  =              512.500",
                "CDELT1  =        -0.00083333",
                "CDELT2  =         0.00083333",
                "CDELT3  =              1000.0",
                "CUNIT1  = 'deg     '",
                "CUNIT2  = 'deg     '",
                "CUNIT3  = 'm/s     '",
                "RADESYS = 'FK5     '",
                "EQUINOX =               2000.0",
                "RESTFRQ =       1.420405752E9",
                "MJD-OBS =            52413.59",
                "SSYSSRC =           'BARYCENT'",
                "SPECSYS =           'BARYCENT'");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        Frame spec = fs.pickAxes(1, new int[] {3}, null);
        assertInstanceOf(SpecFrame.class, spec);
        assertEquals("10:00:00.0", spec.getC("RefRA"));
        assertEquals("0:00:00", spec.getC("RefDec"));
        assertTrue(spec.test("RefRA"));
        assertFalse(spec.test("RefDec"));

        Frame lsrk = (Frame) spec.copy();
        lsrk.set("StdOfRest=LSRK");
        double v = FitsHeaders.tran(spec.convert(lsrk, ""), new double[] {0.0}, true)[0];
        assertEquals(-8899.198487820357, v, 8899.2 * 1e-12);

        FitsChan out = new FitsChan();
        out.setEncoding("FITS-WCS");
        out.putFits("NAXIS1  = 100", false);
        out.putFits("NAXIS2  = 100", false);
        out.putFits("NAXIS3  = 1024", false);
        assertEquals(1, out.write(fs));
        out.clear("Card");
        String velosys = out.findFits("VELOSYS", false);
        assertTrue(
                FitsHeaders.cardsMatchC(
                        Arrays.asList(velosys),
                        FitsHeaders.padded(
                                "VELOSYS =   28372.571560485387 / [m/s] Topo. apparent velocity of rest frame")),
                velosys);
    }
}
