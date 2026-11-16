package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of the tests of Rust {@code tests/test_specframe.rs} and {@code
 * frames/specframe.rs}, and of the checks of C's {@code ast_tester/testspecframe.c}: construction
 * and defaults, the System and StdOfRest names, the attributes and the units they are given in, the
 * SpecOrigin, SourceVel, SourceVRF and SourceSys, formatting and reading values, Overlay, the
 * reference position, native dumps through a Channel and a FitsChan, and conversions between
 * SpecFrames (specframe.c {@code Match}, {@code SubFrame} and {@code MakeSpecMapping}).
 *
 * <p>The expected values of the conversions are C's, from a probe run against the pinned C library
 * (each case is a pair of attribute settings, applied as the probe applies them), where the Rust
 * tests compare with formulae. Further conversion tests cover a Unit that differs together with
 * the System, RestFreq or StdOfRest, FrameSet integrity, each attribute a standard of rest needs
 * left unset with and without UseDefs, and conversions that leave their Frames unchanged and give
 * the same Mapping in two threads.
 *
 * <p>{@code test_equal_same}, {@code test_not_equal_different_system} and {@code
 * test_not_equal_different_sor} test {@code astEqual}, which this library does not have.
 */
class SpecFrameTest {

    /** A SpecFrame with its System set, as Rust's {@code SpecFrame::new(system)}. */
    private static SpecFrame frame(String system) {
        SpecFrame f = new SpecFrame();
        f.setSystem(system);
        return f;
    }

    private static void assertClose(double want, double got, double tol, String what) {
        assertTrue(Math.abs(got - want) <= tol, what + ": expected " + want + ", got " + got);
    }

    /** The value {@code unformat} reads and the number of characters it uses. */
    private static double[] unformat(SpecFrame f, String text) {
        double[] value = {AstObject.AST__BAD};
        int nc = f.unformat(1, text, value);
        return new double[] {value[0], nc};
    }

    /** {@code obj} written to a FitsChan with the NATIVE encoding and read back. */
    private static AstObject fitsRoundTrip(AstObject obj) {
        FitsChan fc = new FitsChan();
        fc.setEncoding("NATIVE");
        assertEquals(1, fc.write(obj));
        fc.clear("Card");
        AstObject back = fc.read();
        assertNotNull(back, "the FitsChan holds an Object");
        return back;
    }

    /** testspecframe.c {@code checkdump}: a Channel dump read back keeps the SpecOrigin. */
    private static SpecFrame checkDump(SpecFrame sf) {
        AstObject back = ChannelText.read(ChannelText.dump(sf));
        SpecFrame result = assertInstanceOf(SpecFrame.class, back);
        assertEquals(sf.getD("SpecOrigin"), result.getD("SpecOrigin"));
        return result;
    }

    // ------------------------------------------------------------------
    // Construction and attributes
    // ------------------------------------------------------------------

    @Test
    void construction() {
        SpecFrame sf = frame("FREQ");
        assertEquals(1, sf.getNaxes());
        assertEquals("FREQ", sf.getSystem());
        assertEquals(SpecFrame.AST__FREQ, sf.systemValue());
        assertEquals("Heliocentric", sf.getStdOfRest());
        assertEquals(SpecFrame.AST__HLSOR, sf.stdOfRestValue());
    }

    @Test
    void defaults() {
        SpecFrame sf = new SpecFrame();
        assertEquals("WAVE", sf.getSystem());
        assertFalse(sf.test("System"));
        assertEquals("WAVE", sf.getAlignSystem());
        assertEquals("SPECTRUM", sf.getDomain());
        assertEquals("Angstrom", sf.getUnit(1));
        assertEquals("Wavelength", sf.getLabel(1));
        assertEquals("WAVE", sf.getSymbol(1));
        assertEquals("Wavelength", sf.getTitle());
        assertEquals("Heliocentric", sf.getAlignStdOfRest());
        assertEquals("Heliocentric", sf.getC("SourceVRF"));
        assertEquals("VELO", sf.getSourceSys());
        assertEquals(1.0e5, sf.getRestFreq(), 1.0e-9);
        assertEquals(0.0, sf.getSourceVel());
        assertEquals(0.0, sf.getD("SpecOrigin"));
        assertEquals(0, sf.getI("AlignSpecOffset"));
        assertEquals("0:00:00.0", sf.getRefRA());
        assertEquals("0:00:00", sf.getRefDec());
        assertTrue(sf.getActiveUnit());
    }

    @Test
    void attributes() {
        SpecFrame sf = frame("WAVE");
        sf.setRestFreqValue(1.42040575e9);
        sf.setRefRaValue(1.234);
        sf.setRefDecValue(0.567);
        sf.setStdOfRest("LSRK");
        sf.setEpochMjd(51544.5);

        assertEquals("WAVE", sf.getSystem());
        assertEquals(SpecFrame.AST__LKSOR, sf.stdOfRestValue());
        assertEquals("LSRK", sf.getStdOfRest());
        assertClose(1.42040575e9, sf.restFreqValue(), 1.0, "rest_freq");
        assertClose(1.42040575, sf.getRestFreq(), 1.0e-12, "RestFreq in GHz");
        assertClose(1.234, sf.refRaValue(), 1e-15, "ref_ra");
        assertClose(0.567, sf.refDecValue(), 1e-15, "ref_dec");
        assertClose(51544.5, sf.getEpochMjd(), 1e-15, "epoch");
    }

    /** Only the names C accepts are System names: the short names and the long forms. */
    @Test
    void systemNames() {
        SpecFrame sf = new SpecFrame();
        assertEquals(SpecFrame.AST__FREQ, sf.systemCode("FREQ"));
        assertEquals(SpecFrame.AST__FREQ, sf.systemCode("freq"));
        assertEquals(SpecFrame.AST__WAVELEN, sf.systemCode("WAVE"));
        assertEquals(SpecFrame.AST__VREL, sf.systemCode("VELO"));
        assertEquals(SpecFrame.AST__VRADIO, sf.systemCode("VRAD"));
        assertEquals(SpecFrame.AST__REDSHIFT, sf.systemCode("ZOPT"));
        assertEquals(SpecFrame.AST__BETA, sf.systemCode("BETA"));
        assertEquals(Frame.AST__BADSYSTEM, sf.systemCode("junk"));
        String[] names = {"ENERGY", "WAVENUM", "WAVELEN", "AIRWAVE", "VRADIO", "VOPTICAL", "REDSHIFT", "VREL"};
        int[] codes = {
            SpecFrame.AST__ENERGY,
            SpecFrame.AST__WAVENUM,
            SpecFrame.AST__WAVELEN,
            SpecFrame.AST__AIRWAVE,
            SpecFrame.AST__VRADIO,
            SpecFrame.AST__VOPTICAL,
            SpecFrame.AST__REDSHIFT,
            SpecFrame.AST__VREL
        };
        for (int i = 0; i < names.length; i++) {
            assertEquals(codes[i], sf.systemCode(names[i]), names[i]);
        }
        for (String name : new String[] {"FREQUENCY", "WAVENUMBER", "WAVELENGTH", "AIRWAV", "RELATIVISTIC"}) {
            assertEquals(Frame.AST__BADSYSTEM, sf.systemCode(name), name);
        }
    }

    @Test
    void stdOfRestNames() {
        assertEquals(SpecFrame.AST__HLSOR, SpecFrame.stdOfRestCode("Heliocentric"));
        assertEquals(SpecFrame.AST__HLSOR, SpecFrame.stdOfRestCode("HELIO"));
        assertEquals(SpecFrame.AST__LKSOR, SpecFrame.stdOfRestCode("LSRK"));
        assertEquals(SpecFrame.AST__LKSOR, SpecFrame.stdOfRestCode("LSR"));
        assertEquals(SpecFrame.AST__BYSOR, SpecFrame.stdOfRestCode("BARY"));
        assertEquals(SpecFrame.AST__TPSOR, SpecFrame.stdOfRestCode("TOPO"));
        assertEquals(SpecFrame.AST__SCSOR, SpecFrame.stdOfRestCode("Source"));
        assertEquals(SpecFrame.AST__BADSOR, SpecFrame.stdOfRestCode("garbage"));
    }

    /** Rust's {@code is_velocity}: the systems C's {@code ABS_SYSTEM} calls relative. */
    @Test
    void relativeSystems() {
        assertTrue(SpecFrame.absSystem(SpecFrame.AST__FREQ));
        assertTrue(SpecFrame.absSystem(SpecFrame.AST__ENERGY));
        assertTrue(SpecFrame.absSystem(SpecFrame.AST__WAVENUM));
        assertTrue(SpecFrame.absSystem(SpecFrame.AST__WAVELEN));
        assertTrue(SpecFrame.absSystem(SpecFrame.AST__AIRWAVE));
        assertFalse(SpecFrame.absSystem(SpecFrame.AST__VRADIO));
        assertFalse(SpecFrame.absSystem(SpecFrame.AST__VOPTICAL));
        assertFalse(SpecFrame.absSystem(SpecFrame.AST__REDSHIFT));
        assertFalse(SpecFrame.absSystem(SpecFrame.AST__BETA));
        assertFalse(SpecFrame.absSystem(SpecFrame.AST__VREL));
    }

    /** An unknown standard of rest or System is refused with AST__ATTIN and changes nothing. */
    @Test
    void badNamesAreRefused() {
        SpecFrame sf = frame("FREQ");
        AstException e = assertThrows(AstException.class, () -> sf.setStdOfRest("nowhere"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertFalse(sf.test("StdOfRest"));
        e = assertThrows(AstException.class, () -> sf.setAlignStdOfRest("nowhere"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        e = assertThrows(AstException.class, () -> sf.setC("SourceVRF", "Source"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertFalse(sf.test("SourceVRF"));
        e = assertThrows(AstException.class, () -> sf.setSourceSys("FREQ"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertFalse(sf.test("SourceSys"));
        e = assertThrows(AstException.class, () -> sf.setSystem("JUNK"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        assertEquals("FREQ", sf.getSystem());
    }

    @Test
    void domainIsCleaned() {
        SpecFrame sf = frame("FREQ");
        sf.setDomain("my spec");
        assertEquals("MYSPEC", sf.getDomain());
    }

    // ------------------------------------------------------------------
    // RestFreq, SourceVel and SpecOrigin in their units
    // ------------------------------------------------------------------

    /** RestFreq is given in GHz, or as a frequency, wavelength or energy with units. */
    @Test
    void restFreqTakesUnits() {
        SpecFrame sf = new SpecFrame();
        sf.setRestFreq("230");
        assertEquals(230.0e9, sf.restFreqValue(), 1.0e-3);
        sf.setRestFreq("1.4E14 Hz");
        assertEquals(1.4e14, sf.restFreqValue(), 1.0e-3);
        sf.setRestFreq("2.0 m");
        assertEquals(SpecMap.AST__C / 2.0, sf.restFreqValue(), 1.0e-6);
        sf.setRestFreq("1.0 J");
        assertEquals(1.0 * (1.0 / SpecMap.AST__H), sf.restFreqValue());
        AstException e = assertThrows(AstException.class, () -> sf.setRestFreq("1.0 km/s"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
        e = assertThrows(AstException.class, () -> sf.setRestFreq("0.0 m"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
    }

    /** SourceVel is given in km/s for a velocity SourceSys, and stored in m/s. */
    @Test
    void sourceVelUnits() {
        SpecFrame sf = new SpecFrame();
        sf.setSourceVel(10.0);
        assertEquals(10000.0, sf.sourceVelValue());
        assertEquals(10.0, sf.getSourceVel());
        sf.clear("SourceVel");
        sf.setSourceSys("ZOPT");
        sf.setSourceVel(0.1);
        assertEquals(0.1, sf.sourceVelValue());
        assertEquals(0.1, sf.getSourceVel());
    }

    /**
     * testspecframe.c Errors 0 to 3: the SpecOrigin is given in the units of the axis, kept in the
     * default units of the System, and survives a dump.
     */
    @Test
    void specOriginFollowsTheUnit() {
        SpecFrame sf = new SpecFrame();
        sf.set("system=freq,unit=Hz");
        assertEquals(0.0, sf.getD("SpecOrigin"), "Error 0");
        double rf = sf.getD("RestFreq");
        sf.setD("SpecOrigin", rf * 1.0e9);
        assertTrue(Math.abs(sf.getD("SpecOrigin") - rf * 1.0e9) <= 0.1, "Error 1");
        sf.setC("Unit(1)", "GHz");
        assertEquals(rf, sf.getD("SpecOrigin"), "Error 2");
        checkDump(sf);
    }

    /** A SpecOrigin with units is converted to the default units of the System. */
    @Test
    void specOriginWithUnits() {
        SpecFrame sf = frame("FREQ");
        sf.set("SpecOrigin=1.5 MHz");
        assertEquals(1.5e-3, sf.specOriginValue(), 1.0e-15);
        assertEquals("Frequency offset from 0.0015", sf.getLabel(1));
    }

    /**
     * testspecframe.c Errors 10 to 12: SourceVel, in C's units, is re-expressed when SourceVRF and
     * SourceSys change, and survives a dump. 1000 km/s relative to the LSRK, as an optical
     * redshift relative to the barycentre, is 0.00334028336870307, and back.
     */
    @Test
    void sourceVelFollowsSourceVrfAndSourceSys() {
        SpecFrame sf = new SpecFrame();
        sf.set("system=freq,unit=Hz");
        sf.setC("SourceVRF", "LSRK");
        sf.setD("SourceVel", 1000.0);
        sf.setC("SourceVRF", "BARY");
        sf.setC("SourceSys", "ZOPT");
        assertClose(0.00334028336870307, sf.getD("SourceVel"), 1.0e-10, "Error 11");
        checkDump(sf);
        sf.setC("SourceVRF", "LSRK");
        sf.setC("SourceSys", "VREL");
        assertClose(1000.0, sf.getD("SourceVel"), 1.0e-6, "Error 12");
    }

    // ------------------------------------------------------------------
    // Units of another kind
    // ------------------------------------------------------------------

    /**
     * A Unit of another kind (Angstrom on a frequency axis, km/s on a wavelength axis) is stored
     * as given and leaves the System as it is (specframe.c {@code SetUnit}); the Label and Symbol
     * keep their defaults, as {@code astUnitMapper} finds no Mapping to relabel them with.
     */
    @Test
    void aUnitOfAnotherKindLeavesTheSystem() {
        SpecFrame sf = frame("FREQ");
        sf.setUnit(1, "Angstrom");
        assertEquals("FREQ", sf.getSystem());
        assertEquals("Angstrom", sf.getUnit(1));
        assertEquals("Frequency", sf.getLabel(1));
        assertEquals("FREQ", sf.getSymbol(1));

        SpecFrame wave = frame("WAVE");
        wave.setUnit(1, "km/s");
        assertEquals("WAVE", wave.getSystem());
        assertEquals("km/s", wave.getUnit(1));
        assertEquals("Wavelength", wave.getLabel(1));
    }

    /**
     * A SpecOrigin cannot be given or read in units that do not convert to the System's default
     * units: AST__BADUN from specframe.c {@code ToUnits} and {@code GetSpecOriginCur}.
     */
    @Test
    void anImpossibleUnitIsBadun() {
        SpecFrame sf = frame("FREQ");
        sf.setUnit(1, "Angstrom");
        AstException e = assertThrows(AstException.class, () -> sf.setD("SpecOrigin", 5.0));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        assertEquals(
                "astSetSpecOrigin(SpecFrame): Cannot convert the supplied attribute value from units of Angstrom"
                        + " to GHz.",
                e.getMsgText());
        assertFalse(sf.test("SpecOrigin"));

        SpecFrame withOrigin = frame("FREQ");
        withOrigin.setD("SpecOrigin", 5.0);
        withOrigin.setUnit(1, "Angstrom");
        e = assertThrows(AstException.class, () -> withOrigin.getD("SpecOrigin"));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        assertEquals(
                "astGetSpecOrigin(SpecFrame): The current units (Angstrom) are not suitable for a SpecFrame.",
                e.getMsgText());
        e = assertThrows(AstException.class, () -> withOrigin.getLabel(1));
        assertEquals(AstException.AST__BADUN, e.getStatus());
    }

    /** The Unit used with each System comes back when the System does. */
    @Test
    void theUnitOfEachSystemIsRemembered() {
        SpecFrame sf = frame("FREQ");
        sf.setUnit(1, "MHz");
        sf.setSystem("VRAD");
        assertEquals("km/s", sf.getUnit(1));
        assertFalse(sf.test("Unit(1)"));
        sf.setUnit(1, "m/s");
        sf.setSystem("FREQ");
        assertEquals("MHz", sf.getUnit(1));
        sf.setSystem("VRAD");
        assertEquals("m/s", sf.getUnit(1));
        sf.clear("Unit(1)");
        sf.setSystem("FREQ");
        sf.setSystem("VRAD");
        assertEquals("km/s", sf.getUnit(1));
    }

    // ------------------------------------------------------------------
    // Formatting and reading values
    // ------------------------------------------------------------------

    @Test
    void distance() {
        SpecFrame sf = frame("FREQ");
        assertClose(1e9, sf.distance(new double[] {1e9}, new double[] {2e9}), 1.0, "distance");
        assertEquals(AstObject.AST__BAD, sf.distance(new double[] {AstObject.AST__BAD}, new double[] {2e9}));
    }

    /** SpecFrame does not override Format in C; it formats as a Frame does, without units. */
    @Test
    void formatFreq() {
        assertEquals("1.42E+09", frame("FREQ").format(1, 1.42e9));
        assertFalse(frame("ZOPT").format(1, 0.5).contains("Hz"));
    }

    /** The base {@code %1.7G} contract, with Digits 7. */
    @Test
    void formatBaseContract() {
        SpecFrame sf = frame("FREQ");
        assertEquals("12.5", sf.format(1, 12.5));
        assertEquals("1.5E+09", sf.format(1, 1.5e9));
        assertEquals("0.0001234568", sf.format(1, 0.000123456789));
        assertEquals("1.234568E+08", sf.format(1, 123456789.0));
        assertEquals("0.3333333", sf.format(1, 1.0 / 3.0));
        assertEquals("<bad>", sf.format(1, AstObject.AST__BAD));
    }

    @Test
    void unformatReadsANumber() {
        double[] r = unformat(frame("FREQ"), "1.5e9 Hz");
        assertClose(1.5e9, r[0], 1.0, "unformat value");
        assertTrue(r[1] > 0, "unformat uses characters");
    }

    /** SpecFrame does not override Unformat in C; it reads as a Frame does (axis.c). */
    @Test
    void unformatBaseContract() {
        SpecFrame sf = frame("FREQ");
        assertEquals(12.5, unformat(sf, "12.5")[0]);
        assertEquals(4, (int) unformat(sf, "12.5")[1]);
        assertEquals(12.5, unformat(sf, "  12.5  ")[0]);
        assertEquals(8, (int) unformat(sf, "  12.5  ")[1]);
        assertEquals(12.5, unformat(sf, "12.5xyz")[0]);
        assertEquals(4, (int) unformat(sf, "12.5xyz")[1]);
        assertEquals(AstObject.AST__BAD, unformat(sf, "<bad>")[0]);
        assertEquals(5, (int) unformat(sf, "<bad>")[1]);
        assertEquals(AstObject.AST__BAD, unformat(sf, " < b a d > ")[0]);
        assertEquals(11, (int) unformat(sf, " < b a d > ")[1]);
        assertEquals(0, (int) unformat(sf, "xyz")[1]);
        assertEquals(0, (int) unformat(sf, "")[1]);
        assertEquals(0, (int) unformat(sf, "  ")[1]);
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------

    @Test
    void overlayCopiesASetSystem() {
        SpecFrame template = frame("VRAD");
        SpecFrame result = frame("FREQ");
        result.setTitle("Mine");
        template.overlay(new int[] {0}, result);
        assertEquals("VRAD", result.getSystem());
        assertFalse(result.test("Title"));
    }

    @Test
    void overlaySkipsUnsetAttributes() {
        SpecFrame template = frame("FREQ");
        template.clear("System");
        assertFalse(template.test("System"));
        SpecFrame result = frame("VRAD");
        template.overlay(new int[] {0}, result);
        assertEquals("VRAD", result.getSystem());
        assertFalse(result.test("RestFreq"));
        assertFalse(result.test("StdOfRest"));
    }

    @Test
    void overlayCopiesASetRestFreq() {
        SpecFrame template = frame("FREQ");
        template.setRestFreqValue(1.420405751e9);
        SpecFrame result = frame("FREQ");
        template.overlay(new int[] {0}, result);
        assertEquals(1.420405751e9, result.restFreqValue());
    }

    /**
     * Overlaid onto a plain Frame, a SpecFrame with a System set gives none of its System and
     * AlignSystem, and itself keeps them: specframe.c {@code Overlay} clears them on a copy. Clearing
     * the copy's System changes it, which clears the copy's Title, so the Title is not overlaid
     * either (a C quirk, followed here); the Domain is.
     */
    @Test
    void overlayOntoAPlainFrameLeavesTheSystem() {
        SpecFrame template = frame("VRAD");
        template.setAlignSystem("FREQ");
        template.setTitle("Spec");
        template.setDomain("MYSPEC");
        Frame result = new Frame(1);
        template.overlay(new int[] {0}, result);
        assertEquals("Cartesian", result.getSystem());
        assertFalse(result.test("Title"));
        assertEquals("MYSPEC", result.getDomain());
        assertEquals("VRAD", template.getSystem());
        assertEquals("FREQ", template.getAlignSystem());
        assertEquals("Spec", template.getTitle());
    }

    // ------------------------------------------------------------------
    // Reference position
    // ------------------------------------------------------------------

    /**
     * RefRA and RefDec are stored in FK5 J2000, so a position given in FK4 B1950 is converted to it.
     * The values are the REFRA_B and REFDEC_B cards of the C library's {@code fits-pc-alt} wcsconv
     * reference, whose alternate plane describes RA, Dec = 180, 45 degrees in FK4 B1950.
     */
    @Test
    void setRefPosConvertsFromFk4B1950() {
        SkyFrame fk4 = new SkyFrame();
        fk4.set("System=FK4,Equinox=1950,Epoch=1950");
        SpecFrame spec = frame("WAVE");
        spec.setRefPos(fk4, Math.toRadians(180.0), Math.toRadians(45.0));
        assertClose(3.152747005821885, spec.refRaValue(), 1.0e-12, "RefRA");
        assertClose(0.7805403873335395, spec.refDecValue(), 1.0e-12, "RefDec");
    }

    /**
     * Rust {@code tests/test_frame_api.rs} {@code ref_pos_follows_the_c_library}: the reference
     * position read back with no SkyFrame, in Galactic coordinates, and in FK4 B1950 with the
     * axes swapped, as C's {@code astGetRefPos} gives them ({@code oracle/frame_api.json}).
     */
    @Test
    void refPosFollowsTheCLibrary() {
        SpecFrame sf = frame("WAVE");
        sf.setRefPos(null, 1.0, 0.3);
        SkyFrame gal = new SkyFrame();
        gal.setSystem("GALACTIC");
        SkyFrame fk4 = new SkyFrame();
        fk4.set("System=FK4,Equinox=1950");
        fk4.permAxes(new int[] {2, 1});
        String[] names = {"none", "galactic", "fk4_swapped"};
        double[][] got = {sf.getRefPos(null), sf.getRefPos(gal), sf.getRefPos(fk4)};
        JsonNode cases = ProbeData.load("frame_api").get("REF_POS");
        assertEquals(names.length, cases.size(), "the test runs every case the C probe recorded");
        for (int i = 0; i < names.length; i++) {
            JsonNode c = cases.get(i);
            assertEquals(names[i], c.get(0).asText());
            double[] want = {
                ProbeData.toDouble(c.get(1).get(0)), ProbeData.toDouble(c.get(1).get(1))
            };
            CValues.assertCValues(got[i], want, names[i]);
        }
    }

    @Test
    void refPosWithoutASkyFrameIsTheStoredPosition() {
        SpecFrame spec = new SpecFrame();
        spec.setRefPos(null, 1.0, 0.3);
        double[] pos = spec.getRefPos(null);
        assertEquals(1.0, pos[0]);
        assertEquals(0.3, pos[1]);
        assertTrue(spec.test("RefRA"));
        assertTrue(spec.test("RefDec"));
    }

    @Test
    void refRaAndRefDecAreSexagesimal() {
        SpecFrame sf = new SpecFrame();
        sf.setRefRA("1:00:00");
        sf.setRefDec("-30:00:00");
        assertEquals(Math.PI / 12.0, sf.refRaValue(), 1.0e-15);
        assertEquals(-Math.PI / 6.0, sf.refDecValue(), 1.0e-15);
        assertEquals("1:00:00.0", sf.getRefRA());
        assertEquals("-30:00:00", sf.getRefDec());
        AstException e = assertThrows(AstException.class, () -> sf.setRefRA("1:00:00 junk"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
    }

    /** GeoLat and GeoLon are the ObsLat and ObsLon. */
    @Test
    void geoLatAndGeoLonAreTheObserverPosition() {
        SpecFrame sf = new SpecFrame();
        sf.setGeoLat("N10");
        sf.setGeoLon("W20");
        assertEquals(sf.getObsLat(), sf.getGeoLat());
        assertEquals(sf.getObsLon(), sf.getGeoLon());
        assertTrue(sf.test("GeoLat"));
        assertEquals(Math.toRadians(10.0), sf.getObsLatValue(), 1.0e-15);
        sf.clear("GeoLon");
        assertFalse(sf.test("ObsLon"));
    }

    // ------------------------------------------------------------------
    // Dumps
    // ------------------------------------------------------------------

    @Test
    void serialiseRoundTrip() {
        SpecFrame sf = frame("WAVE");
        sf.setRestFreqValue(1.42040575e9);
        sf.setRefRaValue(1.5);
        sf.setRefDecValue(-0.3);
        sf.setStdOfRest("LSRK");
        sf.setSourceVelValue(25000.0);
        sf.setSourceVrfValue(SpecFrame.AST__HLSOR);
        sf.setSourceSysValue(SpecFrame.AST__VREL);
        sf.setEpochMjd(51544.5);
        sf.setObsLatValue(0.5);
        sf.setObsLonValue(-1.0);
        sf.setObsAlt(100.0);
        sf.setSpecOriginValue(5.0e-7);
        sf.setAlignStdOfRestValue(SpecFrame.AST__BYSOR);

        String text = ChannelText.dump(sf);
        for (String key : new String[] {"SpecFrame", "SoR", "RstFrq", "RefRA", "SrcVel", "SpOrg", "AlSoR"}) {
            assertTrue(text.contains(key), "missing " + key);
        }
        SpecFrame sf2 = assertInstanceOf(SpecFrame.class, ChannelText.read(text));
        assertEquals("WAVE", sf2.getSystem());
        assertEquals(SpecFrame.AST__LKSOR, sf2.stdOfRestValue());
        assertClose(1.42040575e9, sf2.restFreqValue(), 1.0, "rf");
        assertClose(1.5, sf2.refRaValue(), 1e-14, "refra");
        assertClose(-0.3, sf2.refDecValue(), 1e-14, "refdec");
        assertClose(25000.0, sf2.sourceVelValue(), 1e-10, "srcvel");
        assertEquals(SpecFrame.AST__HLSOR, sf2.sourceVrfValue());
        assertEquals(SpecFrame.AST__VREL, sf2.sourceSysValue());
        assertClose(51544.5, sf2.getEpochMjd(), 1e-14, "epoch");
        assertClose(5.0e-7, sf2.specOriginValue(), 1e-20, "sporg");
        assertEquals(SpecFrame.AST__BYSOR, sf2.alignStdOfRestValue());
    }

    @Test
    void serialiseMinimal() {
        SpecFrame sf2 = assertInstanceOf(SpecFrame.class, ChannelText.read(ChannelText.dump(frame("FREQ"))));
        assertEquals("FREQ", sf2.getSystem());
        assertEquals(SpecFrame.AST__HLSOR, sf2.stdOfRestValue());
        assertFalse(sf2.test("StdOfRest"));
    }

    /** The C library's dump of a SpecFrame with a Unit used with its System, item for item. */
    @Test
    void theUnitUsedWithASystemIsDumped() {
        String c = " Begin SpecFrame \t# Description of spectral coordinate system\n"
                + "#   Title = \"Frequency (Heliocentric)\" \t# Title of coordinate system\n"
                + "    Naxes = 1 \t# Number of coordinate axes\n"
                + "#   Domain = \"SPECTRUM\" \t# Coordinate system domain\n"
                + "#   Epoch = 2000 \t# Julian epoch of observation\n"
                + "#   Lbl1 = \"Frequency\" \t# Label for axis 1\n"
                + "    System = \"FREQ\" \t# Coordinate system type\n"
                + "#   Uni1 = \"GHz\" \t# Units for axis 1\n"
                + "    Ax1 = \t# Axis number 1\n"
                + "       Begin Axis \t# Coordinate axis\n"
                + "          Unit = \"GHz\" \t# Axis units\n"
                + "       End Axis\n"
                + " IsA Frame \t# Coordinate system description\n"
                + "    SoR = \"Heliocentric\" \t# Standard of rest\n"
                + "    UFreq = \"GHz\" \t# Preferred units for frequency\n"
                + " End SpecFrame\n";
        SpecFrame sf = frame("FREQ");
        sf.setStdOfRest("Heliocentric");
        sf.setUnit(1, "GHz");
        assertEquals(c, ChannelText.dump(sf));
        SpecFrame back = assertInstanceOf(SpecFrame.class, ChannelText.read(c));
        assertEquals(c, ChannelText.dump(back));
        back.setSystem("VRAD");
        back.setSystem("FREQ");
        assertTrue(back.test("Unit(1)"));
    }

    /** A SpecFrame with no attribute set, and with every one set, survives a Channel and a FitsChan. */
    @Test
    void everyAttributeSurvivesChannelAndFitsChan() {
        SpecFrame unset = new SpecFrame();
        SpecFrame set = frame("VRAD");
        set.setUnit(1, "m/s");
        set.setSystem("FREQ");
        set.setUnit(1, "MHz");
        set.set("AlignSystem=WAVN,StdOfRest=LSRD,AlignStdOfRest=Bary,RefRA=10:00:00,RefDec=-20:00:00");
        set.set("RestFreq=230,SourceVRF=LSRK,SourceSys=ZOPT,SourceVel=0.01,SpecOrigin=200,AlignSpecOffset=1");
        set.set("Epoch=2005.5,ObsLat=N30,ObsLon=W60,ObsAlt=1000,Title=Spectrum,Label(1)=Nu,Symbol(1)=nu");
        for (SpecFrame sf : new SpecFrame[] {unset, set}) {
            String text = ChannelText.dump(sf);
            SpecFrame back = assertInstanceOf(SpecFrame.class, ChannelText.read(text));
            assertEquals(text, ChannelText.dump(back), "Channel round trip");
            SpecFrame viaFits = assertInstanceOf(SpecFrame.class, fitsRoundTrip(sf));
            assertEquals(text, ChannelText.dump(viaFits), "FitsChan round trip");
        }
        SpecFrame back = assertInstanceOf(SpecFrame.class, ChannelText.read(ChannelText.dump(set)));
        assertEquals("MHz", back.getUnit(1));
        assertEquals(0.2, back.specOriginValue(), 1.0e-15);
        assertEquals("0.01", back.getC("SourceVel"));
        assertEquals("Barycentric", back.getAlignStdOfRest());
        assertEquals("LSRD", back.getStdOfRest());
        assertEquals(1, back.getI("AlignSpecOffset"));
    }

    /** A dump with an unknown standard of rest is refused with AST__ATTIN, as C's loader does. */
    @Test
    void anUnknownStandardOfRestInADumpIsRefused() {
        String text = ChannelText.dump(frame("FREQ")).replace("#   SoR = \"Heliocentric\"", "    SoR = \"Nowhere\"");
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
    }

    // ------------------------------------------------------------------
    // The jniast API
    // ------------------------------------------------------------------

    @Test
    void jniastAccessors() {
        SpecFrame sf = new SpecFrame();
        sf.setStdOfRest("Topo");
        assertEquals("Topocentric", sf.getStdOfRest());
        sf.setAlignStdOfRest("LG");
        assertEquals("Local_group", sf.getAlignStdOfRest());
        sf.setRestFreq(345.0);
        assertEquals(345.0, sf.getRestFreq(), 1.0e-12);
        sf.setSourceSys("VRAD");
        assertEquals("VRAD", sf.getSourceSys());
        sf.setSourceVel(-5.0);
        assertEquals(-5.0, sf.getSourceVel(), 1.0e-12);
        // jniast's SourceVRF accessors pass the value as a number, which is no standard of rest.
        assertEquals(
                AstException.AST__ATGER,
                assertThrows(AstException.class, sf::getSourceVRF).getStatus());
        AstException e = assertThrows(AstException.class, () -> sf.setSourceVRF(1.0));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
    }

    /** UseDefs false leaves an unset RestFreq out of the default Title (specframe.c {@code GetTitle}). */
    @Test
    void useDefsLeavesAnUnsetRestFreqOutOfTheTitle() {
        SpecFrame sf = frame("VRAD");
        sf.set("UseDefs=0");
        assertEquals("Radio velocity", sf.getTitle());
        sf.setRestFreq(230.0);
        assertEquals("Radio velocity, rest frequency = 230 GHz", sf.getTitle());
    }

    /** A copy has its own record of the Unit used with each System. */
    @Test
    void aCopyIsIndependent() {
        SpecFrame sf = frame("FREQ");
        sf.setUnit(1, "MHz");
        SpecFrame copy = (SpecFrame) sf.copy();
        copy.setUnit(1, "kHz");
        sf.setSystem("VRAD");
        sf.setSystem("FREQ");
        assertEquals("MHz", sf.getUnit(1));
        copy.setSystem("VRAD");
        copy.setSystem("FREQ");
        assertEquals("kHz", copy.getUnit(1));
    }

    // ------------------------------------------------------------------
    // Conversions between SpecFrames
    //
    // Expected values are C's, from the probe spec_convert_cases.c run against the pinned C
    // library; each case is a pair of attribute settings as the probe's make() applies them.
    // ------------------------------------------------------------------

    /** The probe's {@code make}: a SpecFrame with the comma-separated settings applied in order. */
    private static SpecFrame make(String settings) {
        SpecFrame f = new SpecFrame();
        if (!settings.isEmpty()) {
            f.set(settings);
        }
        return f;
    }

    /** The FrameSet {@code convert} finds from {@code from} to {@code to}, which must exist. */
    private static FrameSet converted(Frame from, Frame to) {
        FrameSet fs = from.convert(to, "");
        assertNotNull(fs, "a conversion");
        return fs;
    }

    /** The class of the simplified Mapping from the base to the current Frame. */
    private static String mappingClass(FrameSet fs) {
        return fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                .simplify()
                .astClass();
    }

    /**
     * Converts {@code x} from a SpecFrame with settings {@code a} to one with {@code b}, and checks
     * the value and the class of the simplified Mapping against C's.
     */
    private static void assertConverts(String a, String b, double x, boolean forward, double want, String wantClass) {
        FrameSet fs = converted(make(a), make(b));
        double got = fs.tran1(1, new double[] {x}, forward)[0];
        assertTrue(CValues.cValueEq(got, want), "[" + a + "] -> [" + b + "]: got " + got + ", C gives " + want);
        assertEquals(wantClass, mappingClass(fs), "the Mapping");
    }

    /**
     * Checks that converting from {@code a} to {@code b} fails with {@code AST__NOVAL} and C's
     * message, naming the attribute that is not set.
     */
    private static void assertNoValue(String a, String b, String message) {
        SpecFrame from = make(a);
        SpecFrame to = make(b);
        AstException e = assertThrows(AstException.class, () -> from.convert(to, ""));
        assertEquals(AstException.AST__NOVAL, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains(message), "C's message " + message + ", got: " + e.getMessage());
    }

    @Test
    void freqToWavelengthConvert() {
        assertConverts("System=FREQ,Unit=Hz", "System=WAVE,Unit=m", 5.0e14, true, 5.99584916e-07, "SpecMap");
        assertConverts(
                "System=FREQ,Unit=Hz",
                "System=WAVE,Unit=m",
                2.99792458e8 / 5.0e14,
                false,
                500000000000000.0,
                "SpecMap");
    }

    @Test
    void freqToEnergy() {
        assertConverts("System=FREQ,Unit=Hz", "System=ENER", 1.0e15, true, 6.6260755e-19, "SpecMap");
    }

    @Test
    void freqToWavenum() {
        assertConverts("System=FREQ,Unit=Hz", "System=WAVN", 3.0e14, true, 1000692.2855944561, "SpecMap");
    }

    @Test
    void freqToVrel() {
        String a = "System=FREQ,Unit=Hz,RestFreq=1.42040575E9 Hz";
        String b = "System=VELO,Unit=m/s,RestFreq=1.42040575E9 Hz";
        assertConverts(a, b, 1.42040575e9, true, 0.0, "SpecMap");
        assertConverts(a, b, 1.42040575e9 * 1.001, true, -299642.561845836, "SpecMap");
    }

    @Test
    void vradioToFreq() {
        assertConverts(
                "System=VRAD,Unit=m/s,RestFreq=1.42040575E9 Hz",
                "System=FREQ,Unit=Hz,RestFreq=1.42040575E9 Hz",
                100000.0,
                true,
                1419931953.641187,
                "SpecMap");
    }

    /** A heliocentric to LSRK change is a small Doppler shift, and the inverse undoes it. */
    @Test
    void sorHelioToLsrk() {
        String a = "System=FREQ,StdOfRest=Helio,RefRA=0:00:00,RefDec=0:00:00";
        String b = "System=FREQ,StdOfRest=LSRK,RefRA=0:00:00,RefDec=0:00:00";
        assertConverts(a, b, 1.42040575e9, true, 1420404375.9912238, "CmpMap");
        FrameSet fs = converted(make(a), make(b));
        double y = fs.tran1(1, new double[] {1.42040575e9}, true)[0];
        double back = fs.tran1(1, new double[] {y}, false)[0];
        assertTrue(CValues.cValueEq(back, 1420405749.9999995), "round trip: " + back);
    }

    @Test
    void sameSystemSameSorIsUnitMap() {
        assertConverts("System=FREQ", "System=FREQ", 1.234e10, true, 1.234e10, "UnitMap");
    }

    /**
     * FrameSet integrity: a change of System and Unit on the current SpecFrame of a FrameSet remaps
     * it by the conversion from the old Frame to the new one.
     */
    @Test
    void mapFromSystemChange() {
        FrameSet fs = new FrameSet(new Frame(1));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(1), make("System=FREQ,Unit=Hz"));
        fs.set("System=WAVE,Unit=m");
        assertEquals("WAVE", fs.getC("System"));
        double got = fs.tran1(1, new double[] {5.0e14}, true)[0];
        assertTrue(CValues.cValueEq(got, 5.99584916e-07), "got " + got);
        assertEquals("SpecMap", mappingClass(fs));
    }

    @Test
    void wavelenToVoptical() {
        String a = "System=WAVE,Unit=m,RestFreq=1.42040575E9 Hz";
        String b = "System=VOPT,Unit=m/s,RestFreq=1.42040575E9 Hz";
        double lambda0 = 2.99792458e8 / 1.42040575e9;
        assertConverts(a, b, lambda0, true, 0.0, "SpecMap");
        assertConverts(a, b, lambda0 * 1.1, true, 29979245.800000027, "SpecMap");
    }

    @Test
    void unitMhzToHzWithinFreq() {
        assertConverts("System=FREQ,Unit=MHz", "System=FREQ,Unit=Hz", 1.0, true, 1.0e6, "ZoomMap");
    }

    @Test
    void unitKmsToMsWithinVrad() {
        assertConverts("System=VRAD,Unit=km/s", "System=VRAD,Unit=m/s", 1.0, true, 1000.0, "ZoomMap");
    }

    @Test
    void unitUnchangedReturnsUnitMap() {
        assertConverts("System=FREQ", "System=FREQ", 42.0, true, 42.0, "UnitMap");
    }

    @Test
    void unitRoundTripMhzToGhz() {
        assertConverts("System=FREQ,Unit=MHz", "System=FREQ,Unit=GHz", 1000.0, true, 1.0, "ZoomMap");
    }

    @Test
    void badValueTransform() {
        FrameSet fs = converted(make("System=FREQ"), make("System=WAVE"));
        assertEquals(AstObject.AST__BAD, fs.tran1(1, new double[] {AstObject.AST__BAD}, true)[0]);
    }

    /** RestFreq 1.420405752 GHz, LSRK, radio velocity in km/s. */
    private static final String VRAD_LSRK = "System=VRAD,StdOfRest=LSRK,RestFreq=1.420405752E9 Hz";

    /** Offset to offset: the value plus the first origin less the second. */
    @Test
    void convertAppliesSpecOrigins() {
        assertConverts(VRAD_LSRK + ",SpecOrigin=10", VRAD_LSRK + ",SpecOrigin=25", 5.0, true, -10.0, "ShiftMap");
    }

    @Test
    void convertOffsetToAbsoluteAppliesOrigin() {
        assertConverts(VRAD_LSRK + ",SpecOrigin=10", VRAD_LSRK, 5.0, true, 15.0, "ShiftMap");
    }

    /** With AlignSpecOffset set on both Frames the offset values align directly. */
    @Test
    void alignSpecOffsetAlignsOffsetValues() {
        assertConverts(
                VRAD_LSRK + ",SpecOrigin=10,AlignSpecOffset=1",
                VRAD_LSRK + ",SpecOrigin=25,AlignSpecOffset=1",
                5.0,
                true,
                5.0,
                "UnitMap");
    }

    /** A SpecOrigin is re-expressed in a new System, and back again (with C's rounding). */
    @Test
    void setSystemRescalesSpecOrigin() {
        SpecFrame sf = make(VRAD_LSRK + ",SpecOrigin=10");
        sf.setSystem("FREQ");
        assertEquals("GHz", sf.getUnit(1));
        assertTrue(CValues.cValueEq(sf.getD("SpecOrigin"), 1.4203583723640518), "FREQ: " + sf.getD("SpecOrigin"));
        sf.setSystem("VRAD");
        assertEquals("km/s", sf.getUnit(1));
        assertTrue(CValues.cValueEq(sf.getD("SpecOrigin"), 10.000000000076058), "VRAD: " + sf.getD("SpecOrigin"));
    }

    /**
     * Rust {@code frames/specframe.rs} {@code sub_frame_specframe_to_specframe}: selecting the axis
     * of a SpecFrame with a SpecFrame template gives a SpecFrame with the template's System and
     * StdOfRest, and a Mapping with one input and one output.
     */
    @Test
    void subFrameSpecFrameToSpecFrame() {
        SpecFrame target = make("System=FREQ,StdOfRest=Helio,RestFreq=1.420405751E9 Hz");
        SpecFrame template = make("System=VRAD,StdOfRest=LSRK,RestFreq=1.420405751E9 Hz");
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        assertTrue(target.subFrame(template, 1, new int[] {0}, new int[] {0}, map, result));
        assertEquals(1, map[0].getNin());
        assertEquals(1, map[0].getNout());
        SpecFrame r = assertInstanceOf(SpecFrame.class, result[0]);
        assertEquals(SpecFrame.AST__VRADIO, r.systemValue());
        assertEquals(SpecFrame.AST__LKSOR, r.stdOfRestValue());
    }

    @Test
    void matchTemplateSpecFrameSelf() {
        FrameFinder.MatchResult m = make("System=VRAD").match(make("System=FREQ"), false);
        assertNotNull(m);
        assertEquals(1, m.templateAxes.length);
        assertEquals(1, m.targetAxes.length);
        assertEquals(0, m.templateAxes[0]);
        assertEquals(0, m.targetAxes[0]);
        assertEquals(
                SpecFrame.AST__VRADIO,
                assertInstanceOf(SpecFrame.class, m.result).systemValue());
    }

    /** A target with no SpecFrame axis is not matched (specframe.c {@code Match}). */
    @Test
    void matchTemplateSpecFrameAgainstBasicFrameFails() {
        assertNull(make("System=FREQ").match(new Frame(1), false));
    }

    @Test
    void matchTemplateSpecFrameSupportsMaxAxesOversized() {
        SpecFrame template = make("System=FREQ");
        template.setMaxAxes(2);
        assertNull(template.match(new Frame(2), false));
    }

    /** Picking the axis of a SpecFrame gives a SpecFrame. */
    @Test
    void pickAxesSpecFrameFull() {
        Frame f = make("System=VRAD").pickAxes(1, new int[] {1}, null);
        assertEquals(SpecFrame.AST__VRADIO, assertInstanceOf(SpecFrame.class, f).systemValue());
    }

    /** Picking no axis of a SpecFrame gives a plain one-axis Frame. */
    @Test
    void pickAxesSpecFramePartial() {
        Frame f = make("System=VRAD").pickAxes(1, new int[] {0}, null);
        assertEquals(1, f.getNaxes());
        assertFalse(f instanceof SpecFrame);
    }

    /** A SpecFrame's set System is overlaid onto the alignment Frame of a conversion. */
    @Test
    void convertThroughGenericDriverTransfersSystem() {
        assertConverts(
                "System=VRAD,Unit=m/s,RestFreq=1.0E9 Hz,StdOfRest=Topo",
                "System=FREQ,Unit=Hz,RestFreq=1.0E9 Hz,StdOfRest=Topo",
                1000.0,
                true,
                999996664.3590481,
                "SpecMap");
    }

    /**
     * testspecframe.c Errors 4 to 9 and 13 to 15: a SpecOrigin is re-expressed when the System and
     * StdOfRest change; SpecOrigins shift conversions unless AlignSpecOffset aligns the offsets; and
     * with AlignSpecOffset set in a wavelength System, whose zero point has no frequency, a change
     * of rest frame converts as it does without it, and a SpecOrigin is still re-expressed. The
     * values asserted exactly are C's; the tolerances are testspecframe.c's.
     */
    @Test
    void testspecframeConversions() {
        SpecFrame sf = make("system=freq,unit=Hz");
        double rf = sf.getD("RestFreq");
        sf.setD("SpecOrigin", rf * 1.0e9);
        sf.setC("Unit(1)", "GHz");
        sf.setC("System", "vrad");
        assertTrue(Math.abs(sf.getD("SpecOrigin")) <= 1.0e-8, "Error 4");
        assertTrue(CValues.cValueEq(sf.getD("SpecOrigin"), 6.656729789611404e-11), "Error 4: " + sf.getD("SpecOrigin"));
        sf.setC("System", "freq");
        sf.setC("Unit(1)", "Hz");
        assertTrue(Math.abs(sf.getD("SpecOrigin") - rf * 1.0e9) <= 0.1, "Error 5");
        assertTrue(CValues.cValueEq(sf.getD("SpecOrigin"), 99999999999999.97), "Error 5: " + sf.getD("SpecOrigin"));
        sf.setC("StdOfRest", "LSRD");
        assertTrue(Math.abs(sf.getD("SpecOrigin") - rf * 1.00000212890848e9) <= 10.0, "Error 6");
        assertTrue(CValues.cValueEq(sf.getD("SpecOrigin"), 100000212890839.06), "Error 6: " + sf.getD("SpecOrigin"));

        SpecFrame sf1 = make("system=freq,unit=Hz");
        sf1.setD("SpecOrigin", 1.0e20);
        SpecFrame sf2 = make("system=freq,unit=Hz");
        sf2.setD("SpecOrigin", 1.01e20);
        FrameSet fs = converted(sf1, sf2);
        double x = 0.03e20;
        assertEquals(0.02e20, fs.tran1(1, new double[] {x}, true)[0], "Error 7");
        assertEquals(0, sf1.getI("AlignSpecOffset"), "Error 8");
        sf1.setL("AlignSpecOffset", 1);
        sf2.setL("AlignSpecOffset", 1);
        fs = converted(sf1, sf2);
        assertEquals(x, fs.tran1(1, new double[] {x}, true)[0], "Error 9");

        sf1 = make("system=wave,unit=Angstrom,StdOfRest=LSRK,Epoch=2005.0,RefRA=10:00:00,RefDec=20:00:00");
        sf2 = (SpecFrame) sf1.copy();
        sf2.setC("StdOfRest", "HELIO");
        fs = converted(sf1, sf2);
        x = 1.3e7;
        double plain = fs.tran1(1, new double[] {x}, true)[0];
        assertTrue(CValues.cValueEq(plain, 13000214.726950744), "without AlignSpecOffset: " + plain);
        sf1.setL("AlignSpecOffset", 1);
        sf2.setL("AlignSpecOffset", 1);
        fs = converted(sf1, sf2);
        double y = fs.tran1(1, new double[] {x}, true)[0];
        assertTrue(y != AstObject.AST__BAD && Math.abs(y - plain) <= 1.0e-6, "Error 13: " + y);
        sf1.setD("SpecOrigin", 1.299e7);
        sf1.setC("StdOfRest", "HELIO");
        assertTrue(Math.abs(sf1.getD("SpecOrigin") - 1.299e7) <= 1.0e3, "Error 15");
        assertTrue(CValues.cValueEq(sf1.getD("SpecOrigin"), 12990214.56177617), "Error 15: " + sf1.getD("SpecOrigin"));
    }

    // Regressions: the Unit differs between the two SpecFrames together with the System, the
    // RestFreq or the StdOfRest, so the conversion is more than a change of units.

    @Test
    void unitsAndSystemDifferTogether() {
        assertConverts(
                "System=VRAD,Unit=km/s,RestFreq=230",
                "System=VOPT,Unit=m/s,RestFreq=230",
                10.0,
                true,
                10000.333575262843,
                "CmpMap");
    }

    @Test
    void unitsRestFreqAndStdOfRestDifferTogether() {
        assertConverts(
                "System=VRAD,Unit=km/s,RestFreq=230,StdOfRest=Helio",
                "System=VRAD,Unit=m/s,RestFreq=345,StdOfRest=LSRK",
                10.0,
                true,
                99937679.32679091,
                "CmpMap");
    }

    @Test
    void identicalSpecFramesConvertWithAUnitMap() {
        String a = "System=VRAD,RestFreq=230,StdOfRest=LSRK";
        assertConverts(a, a, 10.0, true, 10.0, "UnitMap");
        assertConverts("", "", 5000.0, true, 5000.0, "UnitMap");
        SpecFrame f = make(a);
        assertEquals("UnitMap", mappingClass(converted(f, f)));
    }

    /**
     * A FrameSet whose current Frame is a SpecFrame keeps its integrity when the System, StdOfRest,
     * RestFreq, Unit or SourceVel of that Frame is set through it: the current Frame is remapped by
     * the conversion from the Frame before the change to the Frame after it, aligned as the old
     * Frame's AlignSystem and AlignStdOfRest say (with C's {@code AST__INTFLAG}, the alignment Frame
     * keeps its own rest frame properties). Changing only how two SpecFrames are aligned changes no
     * Mapping.
     */
    @Test
    void framesetIntegrityFollowsTheCLibrary() {
        Object[][] cases = {
            {"System=VRAD,RestFreq=230", "System=FREQ", 10.0, 229.99232802581045, "CmpMap", "FREQ"},
            {"System=VRAD,RestFreq=230", "StdOfRest=LSRK", 10.0, 10.289990186390046, "CmpMap", "VRAD"},
            {"System=VRAD,RestFreq=230", "RestFreq=345", 10.0, 99937.48600000002, "CmpMap", "VRAD"},
            {"System=VRAD,RestFreq=230", "Unit(1)=m/s", 10.0, 10000.0, "ZoomMap", "VRAD"},
            {"System=FREQ,StdOfRest=Source,RestFreq=230", "SourceVel=100", 230.0, 230.07673254164038, "CmpMap", "FREQ"},
            {"System=VRAD,RestFreq=230", "AlignStdOfRest=LSRK", 10.0, 10.0, "UnitMap", "VRAD"},
            {"System=VRAD,RestFreq=230", "AlignSystem=VRAD,RestFreq=345", 10.0, 10.0, "UnitMap", "VRAD"},
        };
        for (Object[] c : cases) {
            String what = c[0] + " then " + c[1];
            FrameSet fs = new FrameSet(new Frame(1));
            fs.addFrame(FrameSet.AST__BASE, new UnitMap(1), make((String) c[0]));
            fs.set((String) c[1]);
            double got = fs.tran1(1, new double[] {(Double) c[2]}, true)[0];
            assertTrue(CValues.cValueEq(got, (Double) c[3]), what + ": got " + got + ", C gives " + c[3]);
            assertEquals(c[4], mappingClass(fs), what);
            assertEquals(c[5], fs.getC("System"), what);
        }
    }

    /** A set SpecOrigin is re-expressed when the System or StdOfRest changes. */
    @Test
    void specOriginFollowsSystemAndStdOfRest() {
        String base = "System=VRAD,StdOfRest=LSRK,RestFreq=230,SpecOrigin=10";
        SpecFrame f = make(base);
        f.set("System=WAVE");
        assertEquals("Angstrom", f.getUnit(1));
        assertTrue(CValues.cValueEq(f.getD("SpecOrigin"), 13034889.492764141), "WAVE: " + f.getD("SpecOrigin"));
        f = make(base);
        f.set("StdOfRest=Helio");
        assertTrue(CValues.cValueEq(f.getD("SpecOrigin"), 9.710009533114343), "Helio: " + f.getD("SpecOrigin"));
    }

    // Review Focus 1: a standard of rest needs a reference position, an Epoch or an observer
    // position. With UseDefs true (the default) an unset one takes its default, as C's does; with
    // UseDefs false the conversion fails with AST__NOVAL, naming the first one C checks.

    private static final String REF = "RefRA=10:00:00,RefDec=20:00:00";

    private static final String SOR_MESSAGE =
            "astMatch(SpecFrame): Cannot convert between different standards of rest.";

    /** Each case converts 230 GHz, or 10 km/s from a radio velocity. */
    @Test
    void unsetAttributesTakeTheirDefaults() {
        String helio = "System=FREQ,StdOfRest=Helio,";
        Object[][] cases = {
            {"System=FREQ,StdOfRest=Topo", "System=FREQ,StdOfRest=Helio", 230.02250828722228},
            {"System=FREQ,StdOfRest=Topo," + REF + ",Epoch=2005.0", helio + REF + ",Epoch=2005.0", 229.9835593392818},
            {"System=FREQ,StdOfRest=Topo," + REF + ",ObsLon=W30,ObsLat=N20,ObsAlt=1000", helio + REF, 229.98408837024638
            },
            {"System=FREQ,StdOfRest=Geo," + REF, helio + REF, 229.98377836897598},
            {"System=FREQ,StdOfRest=Bary," + REF, helio + REF, 229.9999885899968},
            {"System=FREQ,StdOfRest=LSRK", "System=FREQ,StdOfRest=Helio", 230.00022248735908},
            {"System=FREQ,StdOfRest=Source," + REF + ",RestFreq=230", helio + REF, 229.99999999999997},
            {"System=FREQ,StdOfRest=Source," + REF + ",RestFreq=230,SourceVel=100", helio + REF, 229.923293049313},
            {"System=VRAD", "System=FREQ", 99996.66435904801},
            {
                "UseDefs=0,System=FREQ,StdOfRest=Topo," + REF + ",ObsLon=W30,ObsLat=N20,ObsAlt=1000,Epoch=2005.0",
                "UseDefs=0," + helio + REF + ",Epoch=2005.0",
                229.98371973623955
            },
            {
                "UseDefs=0,System=VRAD,StdOfRest=Helio,RestFreq=230",
                "UseDefs=0,System=FREQ,StdOfRest=Helio",
                229.99232802581045
            },
        };
        for (Object[] c : cases) {
            assertConverts(
                    (String) c[0],
                    (String) c[1],
                    c[0].toString().contains("System=VRAD") ? 10.0 : 230.0,
                    true,
                    (Double) c[2],
                    "CmpMap");
        }
    }

    @Test
    void unsetAttributesAreRefusedWithoutUseDefs() {
        String noDefs = "UseDefs=0,";
        String helio = noDefs + "System=FREQ,StdOfRest=Helio";
        assertNoValue(
                noDefs + "System=FREQ,StdOfRest=Topo",
                helio,
                SOR_MESSAGE + "\nNo value has been set for the AST" + " \"RefRA\" attribute (source RA).");
        assertNoValue(
                noDefs + "System=FREQ,StdOfRest=Topo," + REF + ",Epoch=2005.0",
                helio + "," + REF + ",Epoch=2005.0",
                SOR_MESSAGE + "\nNo value has been set for the AST \"ObsLon\" attribute (observer's longitude).");
        String epoch = SOR_MESSAGE + "\nNo value has been set for the AST \"Epoch\" attribute (epoch of observation).";
        assertNoValue(
                noDefs + "System=FREQ,StdOfRest=Topo," + REF + ",ObsLon=W30,ObsLat=N20,ObsAlt=1000",
                helio + "," + REF,
                epoch);
        assertNoValue(noDefs + "System=FREQ,StdOfRest=Geo," + REF, helio + "," + REF, epoch);
        assertNoValue(noDefs + "System=FREQ,StdOfRest=Bary," + REF, helio + "," + REF, epoch);
        assertNoValue(
                noDefs + "System=FREQ,StdOfRest=LSRK",
                helio,
                SOR_MESSAGE + "\nNo value has been set for the AST" + " \"RefRA\" attribute (source RA).");
        assertNoValue(
                noDefs + "System=FREQ,StdOfRest=LSRK,RefRA=10:00:00",
                helio + "," + REF,
                SOR_MESSAGE + "\nNo value has been set for the AST \"RefDec\" attribute (source Dec).");
        assertNoValue(
                helio + "," + REF,
                noDefs + "System=FREQ,StdOfRest=LSRK",
                SOR_MESSAGE + "\nNo value has been set for the AST \"RefRA\" attribute (source RA).");
        assertNoValue(
                noDefs + "System=FREQ,StdOfRest=Source," + REF + ",RestFreq=230",
                helio + "," + REF,
                "astMatch(SpecFrame): Cannot convert source velocity to a new standard of rest.\nNo value has been"
                        + " set for the AST \"SourceVel\" attribute (source velocity).");
        String spectral = "astMatch(SpecFrame): Cannot convert between spectral systems.\nNo value has been set for"
                + " the AST ";
        assertNoValue(noDefs + "System=FREQ", helio, spectral + "\"StdOfRest\" attribute (spectral standard of rest).");
        assertNoValue(
                noDefs + "System=VRAD,StdOfRest=Helio", helio, spectral + "\"RestFreq\" attribute (rest frequency).");
        assertNoValue(
                helio, noDefs + "System=VRAD,StdOfRest=Helio", spectral + "\"RestFreq\" attribute (rest frequency).");
    }

    // Review Focus 3: converting and finding Frames, SpecFrames or FrameSets holding one, leave the
    // Frames given as they were, and conversions in two threads, each between its own copies, give
    // the same Mapping.

    @Test
    void convertLeavesBothFramesAsTheyWere() {
        SpecFrame a = make("System=VRAD,Unit=m/s,RestFreq=230,StdOfRest=Topo,ObsLon=W30,ObsLat=N20,Epoch=2005.0," + REF
                + ",SpecOrigin=12,AlignStdOfRest=LSRK");
        SpecFrame b = make("System=WAVE,RestFreq=345,StdOfRest=LSRK," + REF + ",AlignSpecOffset=1");
        SpecFrame c = make("UseDefs=0,System=FREQ,StdOfRest=Geo");
        String da = ChannelText.dump(a);
        String db = ChannelText.dump(b);
        String dc = ChannelText.dump(c);
        assertNotNull(a.convert(b, ""));
        assertNotNull(b.convert(a, ""));
        assertNotNull(a.findFrame(b, ""));
        assertNotNull(b.findFrame(a, ""));
        assertThrows(AstException.class, () -> c.convert(a, ""));
        assertThrows(AstException.class, () -> a.convert(c, ""));
        assertEquals(da, ChannelText.dump(a));
        assertEquals(db, ChannelText.dump(b));
        assertEquals(dc, ChannelText.dump(c));

        // astConvert sets the Base of a FrameSet it is given to the Frame it used (frameset.c
        // ConvertX), as AST documents; the Frames the FrameSet holds are left as they were.
        FrameSet fs = new FrameSet(new Frame(1));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(1), make("System=FREQ,RestFreq=230,StdOfRest=Bary," + REF));
        String dbase = ChannelText.dump(fs.getFrame(1));
        String dcur = ChannelText.dump(fs.getFrame(2));
        assertNotNull(fs.findFrame(b, ""));
        assertNotNull(fs.convert(a, ""));
        assertNotNull(a.convert(fs, ""));
        assertEquals(dbase, ChannelText.dump(fs.getFrame(1)));
        assertEquals(dcur, ChannelText.dump(fs.getFrame(2)));
        assertEquals(2, fs.getCurrent());
        assertEquals(da, ChannelText.dump(a));
        assertEquals(db, ChannelText.dump(b));
    }

    // ExecutorService is not AutoCloseable before Java 19; the pool is shut down in the finally block.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void twoThreadsConvertingCopiesGetIdenticalMappings() throws Exception {
        SpecFrame a = make("System=VRAD,Unit=m/s,RestFreq=230,StdOfRest=Topo,ObsLon=W30,ObsLat=N20,Epoch=2005.0," + REF
                + ",SpecOrigin=12");
        SpecFrame b = make("System=WAVE,RestFreq=345,StdOfRest=LSRK," + REF);
        String want = ChannelText.dump(a.convert(b, "").getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT));
        int nthread = 2;
        SpecFrame[][] copies = new SpecFrame[nthread][];
        for (int i = 0; i < nthread; i++) {
            copies[i] = new SpecFrame[] {(SpecFrame) a.copy(), (SpecFrame) b.copy()};
        }
        // MODERN-JAVA(19): try-with-resources on the ExecutorService
        ExecutorService pool = Executors.newFixedThreadPool(nthread);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < nthread; i++) {
                SpecFrame[] pair = copies[i];
                results.add(pool.submit(() -> {
                    String last = null;
                    for (int r = 0; r < 50; r++) {
                        String d = ChannelText.dump(
                                pair[0].convert(pair[1], "").getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT));
                        if (last != null && !last.equals(d)) {
                            return "changed between repeats";
                        }
                        last = d;
                    }
                    return last;
                }));
            }
            for (Future<String> f : results) {
                assertEquals(want, f.get());
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES), "threads finished");
        }
    }
}
