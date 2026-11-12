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
 * <p>The parts for a plain Frame are translated here. The SkyFrame parts ({@code
 * sky_frame_norm_unit}, {@code sky_frame_norm_unit_follows_the_format}, {@code
 * picked_sky_axis_keeps_its_norm_unit}, {@code picked_sky_axis_has_an_internal_unit_of_radians},
 * the SkyFrame cases of {@code norm_unit_equals_unit_when_it_cannot_be_simplified}, {@code
 * bad_top_bottom_and_epoch_are_unset} and {@code units_of_an_axis_out_of_range_are_an_error}, and
 * the SkyFrame part of {@code names_ignore_trailing_white_space}) are translated in Task 18, the
 * TimeFrame parts in Task 19, and the CmpFrame and FrameSet parts ({@code
 * cmp_frame_norm_unit_comes_from_the_component}, {@code cmp_frame_norm_unit_keeps_a_sky_description})
 * in Task 20. The SpecFrame, FluxFrame, DSBSpecFrame and Circle parts need classes outside the
 * first phase.
 */
class FrameTest {

    private static Frame oneAxisFrame(String unit) {
        Frame f = new Frame(1);
        f.setUnit(1, unit);
        return f;
    }

    @Test
    void unitIsSimplified() {
        assertEquals("m", oneAxisFrame("s*(m/s)").getNormUnit(1));
    }

    /** NormUnit equals Unit when no simplification can be made. */
    @Test
    void normUnitEqualsUnitWhenItCannotBeSimplified() {
        Frame frame = new Frame(2);
        for (int axis = 1; axis <= frame.getNaxes(); axis++) {
            assertEquals(frame.getUnit(axis), frame.getNormUnit(axis), "NormUnit(" + axis + ") of a default Frame");
        }
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

    /** NormUnit and InternalUnit reject an axis out of range with {@code AST__AXIIN}. */
    @Test
    void unitsOfAnAxisOutOfRangeAreAnError() {
        Frame basic = new Frame(2);
        for (int axis : new int[] {0, basic.getNaxes() + 1}) {
            AstException e = assertThrows(AstException.class, () -> basic.getNormUnit(axis));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "NormUnit(" + axis + ")");
            e = assertThrows(AstException.class, () -> basic.getInternalUnit(axis));
            assertEquals(AstException.AST__AXIIN, e.getStatus(), "InternalUnit(" + axis + ")");
        }
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
    }
}
