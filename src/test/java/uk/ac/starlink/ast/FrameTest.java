package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_frame.rs}: the NormUnit checks of C's {@code
 * ast_tester/testframe.c}, and a table of Unit and NormUnit pairs read back from a one-axis C
 * Frame.
 *
 * <p>The parts for a plain Frame, a SkyFrame, a TimeFrame and a FrameSet holding a SkyFrame are
 * translated here; of {@code spectral_time_and_flux_frames_normalise_the_unit_they_report}, the
 * TimeFrame part. The CmpFrame parts ({@code
 * cmp_frame_norm_unit_comes_from_the_component}, {@code cmp_frame_norm_unit_keeps_a_sky_description},
 * and the CmpFrame cases of {@code units_of_an_axis_out_of_range_are_an_error} and {@code
 * picked_sky_axis_has_an_internal_unit_of_radians}) are translated in Task 20. The SpecFrame, FluxFrame,
 * DSBSpecFrame and Circle parts need classes outside the first phase.
 */
class FrameTest {

    private static Frame oneAxisFrame(String unit) {
        Frame f = new Frame(1);
        f.setUnit(1, unit);
        return f;
    }

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    @Test
    void unitIsSimplified() {
        assertEquals("m", oneAxisFrame("s*(m/s)").getNormUnit(1));
    }

    /**
     * NormUnit equals Unit when no simplification can be made. A SkyFrame reports a Unit of its
     * own while leaving the axis Unit unset, which is where the two are easiest to get out of
     * step, so the relationship is asserted rather than the individual strings.
     */
    @Test
    void normUnitEqualsUnitWhenItCannotBeSimplified() {
        Frame frame = new Frame(2);
        for (int axis = 1; axis <= frame.getNaxes(); axis++) {
            assertEquals(frame.getUnit(axis), frame.getNormUnit(axis), "NormUnit(" + axis + ") of a default Frame");
        }
        SkyFrame skyFrame = sky("ICRS");
        for (int axis = 1; axis <= skyFrame.getNaxes(); axis++) {
            assertEquals(
                    skyFrame.getUnit(axis), skyFrame.getNormUnit(axis), "NormUnit(" + axis + ") of a default SkyFrame");
        }
        TimeFrame timeFrame = new TimeFrame();
        timeFrame.setSystem("MJD");
        assertEquals(timeFrame.getUnit(1), timeFrame.getNormUnit(1), "NormUnit(1) of a default TimeFrame");
    }

    /** A TimeFrame normalises the Unit it reports, its default included. */
    @Test
    void spectralTimeAndFluxFramesNormaliseTheUnitTheyReport() {
        TimeFrame time = new TimeFrame();
        time.setSystem("MJD");
        assertEquals("d", time.getNormUnit(1));
        time.setUnit(1, "1000*s");
        assertEquals("ks", time.getNormUnit(1));
    }

    @Test
    void normUnitFollowsAPermutation() {
        Frame frame = new Frame(2);
        frame.setUnit(1, "km");
        frame.setUnit(2, "s*(m/s)");
        frame.permAxes(new int[] {2, 1});
        assertEquals("m", frame.getNormUnit(1));
        assertEquals("km", frame.getNormUnit(2));
    }

    /**
     * A SkyFrame axis whose Unit describes its values ("hh:mm:ss.s") reports that description
     * unchanged; one whose Unit names a unit ("degrees") has it normalised. Values captured from
     * the C library.
     */
    @Test
    void skyFrameNormUnit() {
        String[][] cases = {
            {"ICRS", "hh:mm:ss.s", "ddd:mm:ss"},
            {"FK5", "hh:mm:ss.s", "ddd:mm:ss"},
            {"GALACTIC", "deg", "deg"},
            {"ECLIPTIC", "deg", "deg"},
            {"AZEL", "deg", "deg"},
        };
        for (String[] c : cases) {
            SkyFrame f = sky(c[0]);
            assertEquals(c[1], f.getNormUnit(1), c[0] + " axis 1");
            assertEquals(c[2], f.getNormUnit(2), c[0] + " axis 2");

            f.permAxes(new int[] {2, 1});
            assertEquals(c[2], f.getNormUnit(1), "permuted " + c[0] + " axis 1");
            assertEquals(c[1], f.getNormUnit(2), "permuted " + c[0] + " axis 2");
        }
    }

    /**
     * A SkyFrame axis reports a description of its values rather than a unit for them whenever
     * its Format calls for more than one sexagesimal field, or for a single field of a time. Those
     * descriptions contain spaces, which the units parser reads as multiplication, so they are
     * left alone. A Format that names a single field of an angle, and a Unit set explicitly, both
     * give units expressions, which are simplified.
     */
    @Test
    void skyFrameNormUnitFollowsTheFormat() {
        // {Format, Unit (null to leave it unset), expected Unit, expected NormUnit}
        String[][] cases = {
            {"bhms", null, "hh mm ss", "hh mm ss"},
            {"bdms.2", null, "ddd mm ss.ss", "ddd mm ss.ss"},
            {"btm", null, "minutes of time", "minutes of time"},
            {"bts", null, "seconds of time", "seconds of time"},
            {"bd", null, "degrees", "deg"},
            {"", "s*(m/s)", "s*(m/s)", "m"},
        };
        for (String[] c : cases) {
            SkyFrame f = sky("ICRS");
            if (!c[0].isEmpty()) {
                f.setFormat(1, c[0]);
            }
            if (c[1] != null) {
                f.setUnit(1, c[1]);
            }
            String what = " with Format \"" + c[0] + "\" and Unit " + c[1];
            assertEquals(c[2], f.getUnit(1), "Unit(1)" + what);
            assertEquals(c[3], f.getNormUnit(1), "NormUnit(1)" + what);
        }
    }

    /** {@code (Unit, NormUnit)} pairs read back from a one-axis C Frame. */
    private static final String[][] C_ORACLE = {
        {"", ""},
        {"s*(m/s)", "m"},
        {"km", "km"},
        {"0.01 m", "cm"},
        {"2*m", "2*m"},
        {"1000 m", "km"},
        {"100*m", "hm"},
        {"m/100", "cm"},
        {"1/m", "m**-1"},
        {"m/2", "0.5*m"},
        {"10/m", "1/dm"},
        {"1000/m", "1/mm"},
        {"2/m", "2/m"},
        {"0.5/m", "0.5/m"},
        {"3/s", "3/s"},
        {"2/km", "2/km"},
        {"1/(2*m)", "0.5/m"},
        {"Angstrom", "Angstrom"},
        {"log(Hz)", "ln(Hz**2.3025850929940459)"},
        {"m**0.5", "sqrt(m)"},
        {"sqrt(m)", "sqrt(m)"},
        {"Jy/beam", "Jy/beam"},
        {"ddd:mm:ss", "ddd:mm:ss"},
        {"hh mm ss", "hh*mm*s"},
        {"degrees", "deg"},
        {"deg", "deg"},
        {"arcsec", "arcsec"},
        {"km/s", "km/s"},
        {"erg/s/cm**2", "erg*cm**-2/s"},
        {"0.5", ""},
        {"2", ""},
        {"m*m", "m**2"},
        {"mJy", "mJy"},
        {"1000*Jy", "kJy"},
        {"MHz*1000", "GHz"},
        {"ln(m)", "ln(m)"},
        {"exp(m)", "exp(m)"},
        {"10**(m)", "10**(m)"},
        {"m**2*s**-1", "m**2/s"},
        {"(m/s)**2", "(m**2)*s**-2"},
        {"s**-1", "s**-1"},
        {"cm/(s*K)", "cm/K/s"},
        {"furlong", "furlong"},
        {"1.5 furlong", "1.5*furlong"},
        {"nano m", "N*m"},
        {"micron", "um"},
    };

    @Test
    void normUnitMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (String[] c : C_ORACLE) {
            String got = oneAxisFrame(c[0]).getNormUnit(1);
            if (!got.equals(c[1])) {
                failures.add("Unit \"" + c[0] + "\": got \"" + got + "\", C gives \"" + c[1] + "\"");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * An axis picked out of a SkyFrame keeps the SkyFrame's Unit and NormUnit. C sets the
     * SkyFrame's Unit explicitly on the picked axis, and SkyAxis leaves a set Unit that describes
     * the axis values unchanged, as it does a default one.
     */
    @Test
    void pickedSkyAxisKeepsItsNormUnit() {
        String[][] cases = {
            {"ICRS", "hh:mm:ss.s", "ddd:mm:ss"},
            {"GALACTIC", "deg", "deg"},
        };
        for (String[] c : cases) {
            SkyFrame f = sky(c[0]);
            for (int axis = 1; axis <= 2; axis++) {
                Frame picked = f.pickAxes(1, new int[] {axis}, null);
                assertEquals(f.getUnit(axis), picked.getUnit(1), c[0] + " Unit of axis " + axis);
                assertEquals(c[axis], picked.getNormUnit(1), c[0] + " axis " + axis);
            }
        }
    }

    /**
     * Setting Top, Bottom or Epoch to the bad value leaves it unset, since C tests each as {@code
     * != AST__BAD} (axis.c:2649, 2663, frame.c:12585).
     */
    @Test
    void badTopBottomAndEpochAreUnset() {
        Frame f = new Frame(1);
        f.setTop(1, AstObject.AST__BAD);
        f.setBottom(1, AstObject.AST__BAD);
        f.setEpochMjd(AstObject.AST__BAD);
        assertFalse(f.testTop(1));
        assertFalse(f.testBottom(1));
        assertFalse(f.testEpoch());

        SkyFrame s = new SkyFrame();
        s.setEpochMjd(55000.0);
        s.setEpochMjd(AstObject.AST__BAD);
        assertFalse(s.testEpoch());
    }

    /** AxAngle reports an invalid axis as an error (frame.c:1430 {@code astValidateAxis}). */
    @Test
    void axAngleRejectsAnInvalidAxis() {
        Frame f = new Frame(2);
        for (int axis : new int[] {0, 3}) {
            AstException e = assertThrows(
                    AstException.class, () -> f.axAngle(new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, axis));
            assertEquals(AstException.AST__AXIIN, e.getStatus());
        }
    }

    /**
     * NormUnit and InternalUnit reject an axis out of range with {@code AST__AXIIN}, as the C
     * library does for a Frame, a SkyFrame and a FrameSet; a FrameSet answers as its current
     * Frame does.
     */
    @Test
    void unitsOfAnAxisOutOfRangeAreAnError() {
        Frame basic = new Frame(2);
        for (int axis : new int[] {0, basic.getNaxes() + 1}) {
            AstException e = assertThrows(AstException.class, () -> basic.getNormUnit(axis));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "NormUnit(" + axis + ")");
            e = assertThrows(AstException.class, () -> basic.getInternalUnit(axis));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "InternalUnit(" + axis + ")");
        }

        SkyFrame skyFrame = sky("ICRS");
        FrameSet fs = new FrameSet((SkyFrame) skyFrame.copy());
        Frame[] frames = {skyFrame, fs};
        String[] names = {"sky", "frameset"};
        for (int i = 0; i < frames.length; i++) {
            Frame frame = frames[i];
            for (int axis : new int[] {0, frame.getNaxes() + 1}) {
                AstException e = assertThrows(AstException.class, () -> frame.getNormUnit(axis));
                assertEquals(AstException.AST__AXIIN, e.getStatus(), names[i] + " NormUnit(" + axis + ")");
                e = assertThrows(AstException.class, () -> frame.getInternalUnit(axis));
                assertEquals(AstException.AST__AXIIN, e.getStatus(), names[i] + " InternalUnit(" + axis + ")");
            }
        }
        // The values the C library gives for axis 1.
        assertEquals("rad", skyFrame.getInternalUnit(1));
        assertEquals("rad", fs.getInternalUnit(1));
        assertEquals("hh:mm:ss.s", fs.getNormUnit(1));
    }

    /**
     * An axis picked out of a SkyFrame is still a sky axis, whose values are in radians: the C
     * library gives an InternalUnit of {@code "rad"} for it.
     */
    @Test
    void pickedSkyAxisHasAnInternalUnitOfRadians() {
        SkyFrame f = sky("ICRS");
        for (int axis = 1; axis <= 2; axis++) {
            Frame picked = f.pickAxes(1, new int[] {axis}, null);
            assertEquals("rad", picked.getInternalUnit(1), "axis " + axis);
            AstException e = assertThrows(AstException.class, () -> picked.getInternalUnit(2));
            assertEquals(AstException.AST__AXIIN, e.getStatus());
        }
        Frame picked = f.pickAxes(1, new int[] {1}, null);
        assertEquals("hh:mm:ss.s", picked.getUnit(1));
    }

    /**
     * Names C matches with {@code astChrMatch} ignore white space after the name, whether given
     * to a parser or read from a dump.
     */
    @Test
    void namesIgnoreTrailingWhiteSpace() {
        Frame frame = new Frame(2);
        frame.setSystem("Cartesian");
        String text = ChannelText.dump(frame);
        assertTrue(text.contains("System = \"Cartesian\""), text);
        text = text.replace("System = \"Cartesian\"", "System = \"cartesian \"");
        Frame back = assertInstanceOf(Frame.class, ChannelText.read(text));
        assertTrue(back.testSystem());

        SkyFrame skyFrame = sky("ICRS");
        skyFrame.setSkyRefIs("Pole");
        String skyText = ChannelText.dump(skyFrame);
        assertTrue(skyText.contains("SRefIs = \"Pole\""), skyText);
        SkyFrame skyBack = assertInstanceOf(
                SkyFrame.class, ChannelText.read(skyText.replace("SRefIs = \"Pole\"", "SRefIs = \"pole  \"")));
        assertEquals("Pole", skyBack.getSkyRefIs());
        assertTrue(skyBack.testSkyRefIs());
        // C reports a value it does not know as a corrupt SkyFrame.
        AstException e = assertThrows(
                AstException.class, () -> ChannelText.read(skyText.replace("SRefIs = \"Pole\"", "SRefIs = \"Polar\"")));
        assertEquals(AstException.AST__INTER, e.getStatus());
    }
}
