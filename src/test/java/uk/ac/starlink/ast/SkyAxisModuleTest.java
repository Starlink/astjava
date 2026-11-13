package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.erfa.Anpm;

/**
 * The unit tests of Rust {@code src/frames/skyaxis.rs} ({@code mod tests}): the SkyAxis defaults,
 * C's {@code DHmsFormat} and the sexagesimal reading of C's {@code AxisUnformat}. The Rust helper
 * {@code normalise_dec} is ERFA's {@code eraAnpm}, so its tests check {@link Anpm#anpm}.
 *
 * <p>The tests after those check behaviour read from C {@code skyaxis.c} and {@code axis.c}: the
 * default Formats, Labels, Symbols and limits, the Units a Format implies, the {@code %} Format,
 * which formats with the Axis class's own Format, the attribute strings, Overlay, Dump and the
 * loader, and the angular geometry methods.
 */
class SkyAxisModuleTest {

    private static final double PI = Math.PI;

    private static final double ARCSEC = PI / 648000.0;

    /** Tolerance for floating-point comparisons (~1e-10 radians). */
    private static final double TOL = 1.0e-10;

    private static final double BAD = AstObject.AST__BAD;

    /** A value read as C's {@code AxisUnformat} reads it, and the number of characters read. */
    private static double[] unformat(String text, String fmt, int digits) {
        double[] value = {0.0};
        int nc = SkyAxis.dhmsUnformat(text, fmt, digits, value);
        return new double[] {value[0], nc};
    }

    // ----------------------------------------------------------------
    // SkyAxis defaults
    // ----------------------------------------------------------------

    @Test
    void normaliseDecPassesSmallAnglesThroughUnchanged() {
        for (double a : new double[] {-0.012636183784438945, 0.5, -0.5, 1.0, -1.0}) {
            assertEquals(a, Anpm.anpm(a), 0.0, "angle " + a + " should pass through");
        }
    }

    @Test
    void normaliseDecWrapsIntoPlusMinusPi() {
        double twoPi = 2.0 * PI;
        assertTrue(Math.abs(Anpm.anpm(1.5 * PI) - (-0.5 * PI)) < TOL);
        assertTrue(Math.abs(Anpm.anpm(-1.5 * PI) - (0.5 * PI)) < TOL);
        assertTrue(Math.abs(Anpm.anpm(twoPi + 0.3) - 0.3) < TOL);
    }

    @Test
    void defaultsFollowIsLatitude() {
        // skyaxis.c astMAKE_GET for AsTime, IsLatitude and CentreZero.
        SkyAxis axis = new SkyAxis();
        assertFalse(axis.getAxisAsTime());
        assertFalse(axis.getAxisCentreZero());
        SkyAxis lon = new SkyAxis();
        lon.setAxisIsLatitude(false);
        assertTrue(lon.getAxisAsTime());
        assertFalse(lon.getAxisCentreZero());
        SkyAxis lat = new SkyAxis();
        lat.setAxisIsLatitude(true);
        assertFalse(lat.getAxisAsTime());
        assertTrue(lat.getAxisCentreZero());
    }

    @Test
    void explicitOverrides() {
        SkyAxis axis = new SkyAxis();
        axis.setAxisAsTime(false);
        axis.setAxisCentreZero(true);
        assertFalse(axis.getAxisAsTime());
        assertTrue(axis.getAxisCentreZero());
    }

    // ----------------------------------------------------------------
    // HMS formatting
    // ----------------------------------------------------------------

    @Test
    void formatHmsZero() {
        assertEquals("0:00:00.0", SkyAxis.dhmsFormat("hms.1", 7, 0.0));
    }

    @Test
    void formatHms6h() {
        assertEquals("6:00:00.0", SkyAxis.dhmsFormat("hms.1", 7, PI / 2.0));
    }

    @Test
    void formatHms12h() {
        assertEquals("12:00:00.0", SkyAxis.dhmsFormat("hms.1", 7, PI));
    }

    @Test
    void formatHms18h() {
        assertEquals("18:00:00.0", SkyAxis.dhmsFormat("hms.1", 7, 3.0 * PI / 2.0));
    }

    @Test
    void formatHmsPrecision0() {
        assertEquals("0:00:00", SkyAxis.dhmsFormat("hms", 7, 0.0));
    }

    @Test
    void formatHmsPrecision3() {
        assertEquals("6:00:00.000", SkyAxis.dhmsFormat("hms.3", 7, PI / 2.0));
    }

    @Test
    void formatHmsFractional() {
        assertEquals("12:30:00.0", SkyAxis.dhmsFormat("hms.1", 7, 12.5 * PI / 12.0));
    }

    // ----------------------------------------------------------------
    // DMS formatting
    // ----------------------------------------------------------------

    @Test
    void formatDms45deg() {
        assertEquals("45:00:00", SkyAxis.dhmsFormat("dms", 7, PI / 4.0));
    }

    @Test
    void formatDmsNeg45deg() {
        assertEquals("-45:00:00", SkyAxis.dhmsFormat("dms", 7, -PI / 4.0));
    }

    @Test
    void formatDms90deg() {
        assertEquals("90:00:00", SkyAxis.dhmsFormat("dms", 7, PI / 2.0));
    }

    @Test
    void formatDmsZero() {
        assertEquals("0:00:00", SkyAxis.dhmsFormat("dms", 7, 0.0));
    }

    @Test
    void formatDms1arcsec() {
        assertEquals("0:00:01", SkyAxis.dhmsFormat("dms", 7, ARCSEC));
    }

    @Test
    void formatDmsNeg1arcsec() {
        assertEquals("-0:00:01", SkyAxis.dhmsFormat("dms", 7, -ARCSEC));
    }

    @Test
    void formatDmsPrecision3() {
        assertEquals("45:00:00.000", SkyAxis.dhmsFormat("dms.3", 7, PI / 4.0));
    }

    @Test
    void formatDms180deg() {
        assertEquals("180:00:00", SkyAxis.dhmsFormat("dms", 7, PI));
    }

    @Test
    void formatDmsFractional() {
        assertEquals("45:30:00", SkyAxis.dhmsFormat("dms", 7, 45.5 * PI / 180.0));
    }

    // ----------------------------------------------------------------
    // Bad values
    // ----------------------------------------------------------------

    @Test
    void formatBadValue() {
        assertEquals("<bad>", SkyAxis.dhmsFormat("hms.1", 7, BAD));
        assertEquals("<bad>", SkyAxis.dhmsFormat("dms", 7, BAD));
    }

    // ----------------------------------------------------------------
    // Unformat HMS
    // ----------------------------------------------------------------

    @Test
    void unformatHms12h30m() {
        double expected = 12.5 * PI / 12.0;
        double[] r = unformat("12:30:00", "hms", 7);
        assertTrue(Math.abs(r[0] - expected) < TOL, "got " + r[0] + ", expected " + expected);
        assertEquals(8, (int) r[1]);
    }

    @Test
    void unformatHms6h() {
        double[] r = unformat("6:00:00.0", "hms", 7);
        assertTrue(Math.abs(r[0] - PI / 2.0) < TOL);
    }

    @Test
    void unformatHms0h() {
        double[] r = unformat("0:00:00", "hms", 7);
        assertTrue(Math.abs(r[0]) < TOL);
    }

    // ----------------------------------------------------------------
    // Unformat DMS
    // ----------------------------------------------------------------

    @Test
    void unformatDms4530155() {
        double expected = (45.0 + 30.0 / 60.0 + 15.5 / 3600.0) * PI / 180.0;
        double[] r = unformat("+45:30:15.5", "dms", 7);
        assertTrue(Math.abs(r[0] - expected) < TOL, "got " + r[0] + ", expected " + expected);
        assertEquals(11, (int) r[1]);
    }

    @Test
    void unformatDmsNeg1arcsec() {
        double[] r = unformat("-0:00:01", "dms", 7);
        assertTrue(Math.abs(r[0] - -ARCSEC) < TOL, "got " + r[0]);
        assertEquals(8, (int) r[1]);
    }

    @Test
    void unformatDms90deg() {
        double[] r = unformat("+90:00:00", "dms", 7);
        assertTrue(Math.abs(r[0] - PI / 2.0) < TOL);
    }

    @Test
    void unformatDecimalDegrees() {
        double[] r = unformat("180.0", "dms", 7);
        assertTrue(Math.abs(r[0] - PI) < TOL);
        assertEquals(5, (int) r[1]);
    }

    @Test
    void unformatDecimalHours() {
        double[] r = unformat("6.0", "hms", 7);
        assertTrue(Math.abs(r[0] - PI / 2.0) < TOL);
    }

    @Test
    void unformatWithLeadingWhitespace() {
        double[] r = unformat("  6:00:00", "hms", 7);
        assertTrue(Math.abs(r[0] - PI / 2.0) < TOL);
        assertEquals(9, (int) r[1]);
    }

    @Test
    void unformatSpaceSeparated() {
        double expected = 12.5 * PI / 12.0;
        double[] r = unformat("12 30 00", "hms", 7);
        assertTrue(Math.abs(r[0] - expected) < TOL, "got " + r[0] + ", expected " + expected);
        assertEquals(8, (int) r[1]);
    }

    @Test
    void unformatEmptyString() {
        // skyaxis.c AxisUnformat: nothing read is not an error, and the value is left alone.
        assertArrayEquals(new double[] {0.0, 0.0}, unformat("", "hms", 7), 0.0);
        assertArrayEquals(new double[] {0.0, 0.0}, unformat("   ", "dms", 7), 0.0);
    }

    @Test
    void unformatNoSeconds() {
        double expected = 12.5 * PI / 12.0;
        double[] r = unformat("12:30", "hms", 7);
        assertTrue(Math.abs(r[0] - expected) < TOL);
        assertEquals(5, (int) r[1]);
    }

    // ----------------------------------------------------------------
    // Round trips
    // ----------------------------------------------------------------

    private static double positiveRemainder(double a, double m) {
        double r = a % m;
        return r < 0 ? r + m : r;
    }

    @Test
    void roundtripHms() {
        double[] values = {0.0, PI / 6.0, PI / 4.0, PI / 2.0, PI, 3.0 * PI / 2.0};
        for (double orig : values) {
            String formatted = SkyAxis.dhmsFormat("hms.3", 7, orig);
            double parsed = unformat(formatted, "hms", 7)[0];
            double origNorm = positiveRemainder(orig, 2.0 * PI);
            double parsedNorm = positiveRemainder(parsed, 2.0 * PI);
            assertTrue(
                    Math.abs(origNorm - parsedNorm) < 1.0e-6,
                    "HMS round trip failed for " + orig + ": formatted='" + formatted + "', parsed=" + parsed);
        }
    }

    @Test
    void roundtripDms() {
        double[] values = {0.0, PI / 6.0, PI / 4.0, PI / 2.0, -PI / 6.0, -PI / 4.0, -PI / 2.0};
        for (double orig : values) {
            String formatted = SkyAxis.dhmsFormat("dms.3", 7, orig);
            double parsed = unformat(formatted, "dms", 7)[0];
            assertTrue(
                    Math.abs(orig - parsed) < 1.0e-6,
                    "DMS round trip failed for " + orig + ": formatted='" + formatted + "', parsed=" + parsed);
        }
    }

    // ----------------------------------------------------------------
    // Rounding overflow
    // ----------------------------------------------------------------

    @Test
    void formatHmsRoundingOverflow() {
        double hours = 5.0 + 59.0 / 60.0 + 59.999 / 3600.0;
        assertEquals("6:00:00.0", SkyAxis.dhmsFormat("hms.1", 7, hours * PI / 12.0));
    }

    @Test
    void formatDmsRoundingOverflow() {
        double deg = 44.0 + 59.0 / 60.0 + 59.8 / 3600.0;
        assertEquals("45:00:00", SkyAxis.dhmsFormat("dms", 7, deg * PI / 180.0));
    }

    @Test
    void formatHms24hNoWrap() {
        // DHmsFormat carries the rounding into the hours field and does not reduce the range.
        double hours = 23.0 + 59.0 / 60.0 + 59.96 / 3600.0;
        assertEquals("24:00:00.0", SkyAxis.dhmsFormat("hms.1", 7, hours * PI / 12.0));
    }

    @Test
    void formatHmsOver24hVerbatim() {
        double hours = 458.0 + 21.0 / 60.0 + 58.4 / 3600.0;
        assertEquals("458:21:58.4", SkyAxis.dhmsFormat("hms.1", 7, hours * PI / 12.0));
    }

    @Test
    void formatHmsNegativeSigned() {
        assertEquals("-6:00:00.0", SkyAxis.dhmsFormat("hms.1", 7, -PI / 2.0));
    }

    // ----------------------------------------------------------------
    // Behaviour read from skyaxis.c and axis.c
    // ----------------------------------------------------------------

    /** skyaxis.c {@code GetAxisFormat}: the default Format chosen by Digits and AsTime. */
    @Test
    void defaultFormatFollowsDigitsAndAsTime() {
        SkyAxis axis = new SkyAxis();
        String[] angle = {"d", "d", "d", "dm", "dm", "dms", "dms", "dms.1", "dms.2"};
        String[] time = {"h", "h", "hm", "hm", "hms", "hms", "hms.1", "hms.2", "hms.3"};
        for (int digits = 1; digits <= 9; digits++) {
            axis.setAxisDigits(digits);
            axis.setAxisAsTime(false);
            assertEquals(angle[digits - 1], axis.getAxisFormat(), "angle digits " + digits);
            axis.setAxisAsTime(true);
            assertEquals(time[digits - 1], axis.getAxisFormat(), "time digits " + digits);
        }
        assertFalse(axis.testAxisFormat());
        axis.setAxisFormat("bdms");
        assertTrue(axis.testAxisFormat());
        assertEquals("bdms", axis.getAxisFormat());
        assertEquals("bdms", axis.getAttrib("format"));
        axis.clearAxisFormat();
        assertFalse(axis.testAxisFormat());
    }

    /** skyaxis.c {@code ParseDHmsFormat}: the fields, and the decimal places a {@code .*} gives. */
    @Test
    void parseDHmsFormatReadsTheFields() {
        SkyAxis.DHmsFields f = SkyAxis.parseDHmsFormat("+zlhms.*", 9);
        assertTrue(f.plus && f.leadZero && f.asTime && f.dh && f.min && f.sec);
        assertEquals('l', f.sep);
        assertEquals(3, f.ndp);
        f = SkyAxis.parseDHmsFormat("gd", 7);
        assertEquals('l', f.sep);
        assertFalse(f.asTime);
        f = SkyAxis.parseDHmsFormat("ds", 7);
        assertTrue(f.dh && !f.min && !f.sec);
        f = SkyAxis.parseDHmsFormat("bms.4", 7);
        assertEquals(' ', f.sep);
        assertTrue(!f.dh && f.min && f.sec && !f.asTime);
        assertEquals(4, f.ndp);
        f = SkyAxis.parseDHmsFormat("t.*", 1);
        assertTrue(f.asTime && f.dh);
        assertEquals(0, f.ndp);
    }

    /** skyaxis.c {@code DHmsFormat}: separators, signs and padding. */
    @Test
    void formatSeparatorsAndPadding() {
        double v = (1.0 + 2.0 / 60.0 + 3.0 / 3600.0) * PI / 12.0;
        assertEquals("1h02m03s", SkyAxis.dhmsFormat("lhms", 7, v));
        assertEquals("1 02 03", SkyAxis.dhmsFormat("bhms", 7, v));
        assertEquals("+01:02:03", SkyAxis.dhmsFormat("+zhms", 7, v));
        double a = (1.0 + 2.0 / 60.0 + 3.0 / 3600.0) * PI / 180.0;
        assertEquals("1:02:03", SkyAxis.dhmsFormat("dms", 7, a));
        assertEquals("62:03", SkyAxis.dhmsFormat("ms", 7, a));
        assertEquals("62m03s", SkyAxis.dhmsFormat("lms", 7, a));
        assertEquals("3723", SkyAxis.dhmsFormat("s", 7, a));
        assertEquals("001.03", SkyAxis.dhmsFormat("zd.2", 7, a));
        assertEquals("-1d", SkyAxis.dhmsFormat("ld", 7, -a));
        assertEquals("0:00", SkyAxis.dhmsFormat("dm", 7, -1e-9));
        assertEquals("<bad>", SkyAxis.dhmsFormat("dms", 7, Double.NaN));
    }

    /** skyaxis.c {@code GetAxisUnit} and {@code DHmsUnit}: the Unit a Format implies. */
    @Test
    void unitFollowsTheFormat() {
        SkyAxis axis = new SkyAxis();
        String[][] cases = {
            {"dms", "ddd:mm:ss"},
            {"bhms.3", "hh mm ss.sss"},
            {"hms.8", "hh:mm:ss.sss..."},
            {"d", "degrees"},
            {"h", "hours"},
            {"m", "arcminutes"},
            {"tm", "minutes of time"},
            {"s", "arcseconds"},
            {"ts", "seconds of time"},
            {"lhms", ""},
            {"%g", "rad"},
        };
        for (String[] c : cases) {
            axis.setAxisFormat(c[0]);
            assertEquals(c[1], axis.getAxisUnit(), c[0]);
        }
        boolean[] descr = {false};
        assertEquals("minutes of time", SkyAxis.dhmsUnit("tm", 7, descr));
        assertTrue(descr[0]);
        assertEquals("arcminutes", SkyAxis.dhmsUnit("m", 7, descr));
        assertFalse(descr[0]);
        assertEquals("ddd mm", SkyAxis.dhmsUnit("bdm", 7, descr));
        assertTrue(descr[0]);
        axis.setAxisUnit("deg");
        assertEquals("deg", axis.getAxisUnit());
        assertEquals("rad", axis.getAxisInternalUnit());
        assertTrue(axis.testAxisInternalUnit());
    }

    /**
     * skyaxis.c {@code GetAxisNormUnit}: a Unit describing the axis values is left alone, whether
     * it is the default or set to the same description; anything else is normalised.
     */
    @Test
    void normUnitLeavesADescriptionAlone() {
        SkyAxis axis = new SkyAxis();
        axis.setAxisFormat("bhms");
        assertEquals("hh mm ss", axis.getAxisNormUnit());
        axis.setAxisUnit("hh mm ss");
        assertEquals("hh mm ss", axis.getAxisNormUnit());
        axis.setAxisFormat("btm");
        axis.clearAxisUnit();
        assertEquals("minutes of time", axis.getAxisNormUnit());
        axis.setAxisFormat("d");
        assertEquals(Units.normUnit("degrees"), axis.getAxisNormUnit());
        axis.setAxisFormat("%g");
        assertEquals("rad", axis.getAxisNormUnit());
    }

    /**
     * skyaxis.c {@code AxisFormat}: a {@code %} Format goes to the Axis class's formatter, which
     * reads the Axis class's own Format, so the default {@code %1.<Digits>G} is used.
     */
    @Test
    void percentFormatUsesTheAxisClassFormat() {
        SkyAxis axis = new SkyAxis();
        axis.setAxisFormat("%10.4f");
        assertEquals("1", axis.axisFormat(1.0));
        assertEquals("%10.4f", axis.getAxisFormat());
        assertEquals("<bad>", axis.axisFormat(BAD));
        double[] value = {0.0};
        assertEquals(4, axis.axisUnformat("1.25", value));
        assertEquals(1.25, value[0], 0.0);
        axis.clearAxisFormat();
        assertEquals("57:17:45", axis.axisFormat(1.0));
    }

    /** skyaxis.c {@code GetAxisLabel}, {@code GetAxisSymbol} and the other default attributes. */
    @Test
    void defaultsOfTheAxisAttributes() {
        SkyAxis axis = new SkyAxis();
        assertEquals("Angle on sky", axis.getAxisLabel());
        assertEquals("delta", axis.getAxisSymbol());
        assertTrue(axis.getAxisDirection());
        assertEquals(Double.MAX_VALUE, axis.getAxisTop(), 0.0);
        assertEquals(-Double.MAX_VALUE, axis.getAxisBottom(), 0.0);
        axis.setAxisAsTime(true);
        assertEquals("Angle on sky expressed as time", axis.getAxisLabel());
        assertEquals("alpha", axis.getAxisSymbol());
        assertFalse(axis.getAxisDirection());
        axis.clearAxisAsTime();
        axis.setAxisIsLatitude(true);
        assertEquals("Sky latitude", axis.getAxisLabel());
        assertEquals("delta", axis.getAxisSymbol());
        assertEquals(PI / 2, axis.getAxisTop(), 0.0);
        assertEquals(-PI / 2, axis.getAxisBottom(), 0.0);
        axis.setAxisIsLatitude(false);
        assertEquals("Sky longitude expressed as time", axis.getAxisLabel());
        assertEquals("alpha", axis.getAxisSymbol());
        axis.setAxisAsTime(false);
        assertEquals("Sky longitude", axis.getAxisLabel());
        axis.setAxisLabel("RA");
        axis.setAxisSymbol("ra");
        axis.setAxisDirection(true);
        axis.setAxisTop(1.0);
        assertEquals("RA", axis.getAxisLabel());
        assertEquals("ra", axis.getAxisSymbol());
        assertTrue(axis.getAxisDirection());
        assertEquals(1.0, axis.getAxisTop(), 0.0);
    }

    /** skyaxis.c {@code GetAttrib}, {@code SetAttrib}, {@code TestAttrib} and {@code ClearAttrib}. */
    @Test
    void attributeStrings() {
        SkyAxis axis = new SkyAxis();
        for (String name : new String[] {"astime", "islatitude", "centrezero"}) {
            assertFalse(axis.testAttrib(name), name);
            assertEquals("0", axis.getAttrib(name), name);
            axis.setAttrib(name, " 7 ");
            assertTrue(axis.testAttrib(name), name);
            assertEquals("1", axis.getAttrib(name), name);
            axis.clearAttrib(name);
            assertFalse(axis.testAttrib(name), name);
        }
        axis.setAttrib("label", "Dec");
        assertEquals("Dec", axis.getAttrib("label"));
        axis.setAttrib("format", "hms");
        assertTrue(axis.testAttrib("format"));
        assertEquals("rad", axis.getAttrib("internalunit"));
        AstException e = assertThrows(AstException.class, () -> axis.setAttrib("astime", "yes"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
    }

    /** skyaxis.c {@code AxisOverlay} and axis.c {@code AxisOverlay}. */
    @Test
    void overlayCopiesTheSetAttributes() {
        SkyAxis template = new SkyAxis();
        template.setAxisFormat("hms");
        template.setAxisAsTime(true);
        template.setAxisIsLatitude(false);
        template.setAxisLabel("RA");
        SkyAxis result = new SkyAxis();
        result.setAxisCentreZero(true);
        template.axisOverlay(result);
        assertEquals("hms", result.getAxisFormat());
        assertTrue(result.testAxisAsTime() && result.getAxisAsTime());
        assertTrue(result.testAxisIsLatitude() && !result.getAxisIsLatitude());
        assertTrue(result.getAxisCentreZero());
        assertEquals("RA", result.getAxisLabel());

        // A SkyAxis template gives a plain Axis its Axis-class attributes only.
        Axis plain = new Axis();
        template.axisOverlay(plain);
        assertEquals("RA", plain.getAxisLabel());
        assertFalse(plain.testAxisFormat());

        // A plain Axis template sets the Axis class's own Format of a SkyAxis, which leaves the
        // SkyAxis Format alone and is what a % SkyAxis Format formats with.
        Axis axTemplate = new Axis();
        axTemplate.setAxisFormat("%6.3f");
        SkyAxis sky = new SkyAxis();
        sky.setAxisFormat("%g");
        axTemplate.axisOverlay(sky);
        assertEquals("%g", sky.getAxisFormat());
        assertEquals(" 1.000", sky.axisFormat(1.0));
    }

    /** skyaxis.c {@code Dump} and {@code astLoadSkyAxis}. */
    @Test
    void dumpAndLoad() {
        SkyAxis axis = new SkyAxis();
        assertEquals("Celestial coordinate axis", axis.dumpComment());
        axis.setAxisFormat("bhms.2");
        axis.setAxisIsLatitude(false);
        axis.setAxisAsTime(true);
        axis.setAxisLabel("RA");
        NativeNode node = NativeNode.of(axis);
        NativeNode axisPart = node.section("Axis");
        assertFalse(axisPart.item("Format").set);
        assertEquals("bhms.2", axisPart.getAny("Format").stringValue);
        assertEquals("hh mm ss.ss", axisPart.getAny("Unit").stringValue);
        NativeNode skyPart = node.section("SkyAxis");
        assertEquals("bhms.2", skyPart.getStringIfSet("Format"));
        assertEquals(Long.valueOf(0), skyPart.getIntIfSet("IsLat"));
        assertNull(skyPart.getIntIfSet("CnZer"));
        assertEquals(Long.valueOf(0), skyPart.getIntAny("CnZer"));
        assertEquals(Long.valueOf(1), skyPart.getIntIfSet("AsTime"));

        SkyAxis loaded = new SkyAxis(node);
        assertEquals("bhms.2", loaded.getAxisFormat());
        assertTrue(loaded.testAxisIsLatitude() && !loaded.getAxisIsLatitude());
        assertFalse(loaded.testAxisCentreZero());
        assertTrue(loaded.testAxisAsTime() && loaded.getAxisAsTime());
        assertEquals("RA", loaded.getAxisLabel());
        assertEquals(node, NativeNode.of(loaded));

        SkyAxis copy = (SkyAxis) axis.copy();
        copy.setAxisFormat("dms");
        assertEquals("bhms.2", axis.getAxisFormat());
    }

    /** skyaxis.c {@code AxisDistance}, {@code AxisOffset}, {@code AxisNorm} and {@code AxisIn}. */
    @Test
    void angularGeometry() {
        SkyAxis axis = new SkyAxis();
        assertEquals(-0.2, axis.axisDistance(0.1, 2 * PI - 0.1), 1e-14);
        assertEquals(BAD, axis.axisDistance(BAD, 1.0), 0.0);
        assertEquals(BAD, axis.axisDistance(1.0, Double.NaN), 0.0);
        assertEquals(2 * PI - 0.1, axis.axisOffset(0.0, -0.1), 1e-14);
        assertEquals(BAD, axis.axisOffset(Double.POSITIVE_INFINITY, 1.0), 0.0);
        assertEquals(2 * PI - 0.1, axis.axisNorm(-0.1), 1e-14);
        assertEquals(BAD, axis.axisNorm(BAD), 0.0);
        axis.setAxisCentreZero(true);
        assertEquals(-0.1, axis.axisOffset(0.0, -0.1), 1e-14);
        assertEquals(-0.1, axis.axisNorm(2 * PI - 0.1), 1e-14);
        assertTrue(axis.axisIn(-0.1, 0.1, 2 * PI, true));
        assertFalse(axis.axisIn(-0.1, 0.1, PI, true));
        assertTrue(axis.axisIn(0.1, 0.2, 0.1, true));
        assertFalse(axis.axisIn(0.1, 0.2, 0.1, false));
    }

    /** skyaxis.c {@code AxisNormValues}: oper 1 chooses the range with the smaller spread. */
    @Test
    void normValuesChoosesTheRange() {
        SkyAxis axis = new SkyAxis();
        double[] v = {-0.1, 0.1, BAD, 2e6};
        axis.axisNormValues(0, v);
        assertArrayEquals(new double[] {2 * PI - 0.1, 0.1, BAD, 2e6}, v, 1e-14);
        axis.axisNormValues(1, v);
        assertArrayEquals(new double[] {-0.1, 0.1, BAD, 2e6}, v, 1e-14);
        double[] w = {3.0, 3.3};
        axis.axisNormValues(1, w);
        assertArrayEquals(new double[] {3.0, 3.3}, w, 0.0);
        axis.setAxisIsLatitude(true);
        double[] x = {3.3};
        axis.axisNormValues(1, x);
        assertEquals(3.3 - 2 * PI, x[0], 1e-14);
        AstException e = assertThrows(AstException.class, () -> axis.axisNormValues(2, x));
        assertEquals(AstException.AST__INTER, e.getStatus());
    }

    /** skyaxis.c {@code AxisUnformat}: separators, {@code <bad>} and an invalid field. */
    @Test
    void unformatSeparatorsBadAndErrors() {
        double[] r = unformat("12h30m", "dms", 7);
        assertEquals(12.5 * PI / 12.0, r[0], TOL);
        assertEquals(6, (int) r[1]);
        r = unformat("30m 15s", "hms", 7);
        assertEquals((30.0 / 60.0 + 15.0 / 3600.0) * PI / 12.0, r[0], TOL);
        r = unformat(" < bad > x", "dms", 7);
        assertEquals(BAD, r[0], 0.0);
        assertEquals(9, (int) r[1]);
        r = unformat("1.5e1", "dms", 7);
        assertEquals(15.0 * PI / 180.0, r[0], TOL);
        assertEquals(5, (int) r[1]);
        AstException e = assertThrows(AstException.class, () -> unformat("12:60:00 ", "hms", 7));
        assertEquals(AstException.AST__UNFER, e.getStatus());
        assertTrue(e.getMessage().startsWith("Invalid minutes value in sky coordinate \"12:60:00\"."), e.getMessage());
        e = assertThrows(AstException.class, () -> unformat("1:2:60.5", "dms", 7));
        assertTrue(e.getMessage().startsWith("Invalid arcseconds value in sky coordinate \"1:2:60.5\"."));
        r = unformat("1:59.99999", "dms", 7);
        assertEquals((1.0 + 59.99999 / 60.0) * PI / 180.0, r[0], TOL);
    }
}
