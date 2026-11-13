package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of Rust {@code tests/test_timeframe.rs}, which follows C's {@code
 * ast_tester/testtime.c}: the defaults of a TimeFrame, conversions between time systems, time
 * scales, origins and units, formatting and reading dates, the default Title and Epoch, and the
 * re-expression of the TimeOrigin when the System or TimeScale changes.
 *
 * <p>Where a Rust test cites a check of {@code testtime.c}, the C test's tolerance is used. {@code
 * test_equal} (astEqual) and {@code test_map_from_timescale_change} (Rust's {@code
 * Frame::map_from}) are on the deferred list.
 */
class TimeFrameTest {

    /** A TimeFrame with its System set, as Rust's {@code TimeFrame::new(system)}. */
    private static TimeFrame frame(String system) {
        TimeFrame f = new TimeFrame();
        f.setSystem(system);
        return f;
    }

    private static FrameSet conversion(TimeFrame from, TimeFrame to) {
        FrameSet fs = ((TimeFrame) from.copy()).convert((TimeFrame) to.copy(), "");
        assertNotNull(fs, "a conversion");
        return fs;
    }

    private static double convertValue(TimeFrame from, TimeFrame to, double input) {
        return conversion(from, to).tran1(1, new double[] {input}, true)[0];
    }

    private static double roundTrip(TimeFrame from, TimeFrame to, double input) {
        FrameSet fs = conversion(from, to);
        double[] fwd = fs.tran1(1, new double[] {input}, true);
        return fs.tran1(1, fwd, false)[0];
    }

    private static void assertClose(double want, double got, double tol, String what) {
        assertTrue(Math.abs(got - want) <= tol, what + ": expected " + want + ", got " + got);
    }

    /** The value {@code unformat} reads and the number of characters it uses. */
    private static double[] unformat(TimeFrame f, String text) {
        double[] value = {AstObject.AST__BAD};
        int nc = f.unformat(1, text, value);
        return new double[] {value[0], nc};
    }

    // ------------------------------------------------------------------
    // Default attributes (C errors 1-14)
    // ------------------------------------------------------------------

    private static void assertDefaults(String system, String scale) {
        TimeFrame tf = frame(system);
        assertEquals(system, tf.getSystem());
        assertEquals(0.0, tf.getTimeOriginValue());
        assertEquals(scale, tf.getTimeScale());
        assertEquals("TAI", tf.getAlignTimeScale());
        assertEquals(1, tf.getNaxes());
    }

    @Test
    void testDefaultsMjd() {
        assertDefaults("MJD", "TAI");
    }

    @Test
    void testDefaultsJd() {
        assertDefaults("JD", "TAI");
    }

    @Test
    void testDefaultsJepoch() {
        assertDefaults("JEPOCH", "TAI");
    }

    /** A Besselian epoch is in TT; the AlignTimeScale of TT is TAI. */
    @Test
    void testDefaultsBepoch() {
        assertDefaults("BEPOCH", "TT");
    }

    /** The AlignTimeScale of a time scale defined by the Earth's rotation is UT1. */
    @Test
    void testAlignTimescaleEarthRotation() {
        TimeFrame tf = frame("MJD");
        for (String ts : new String[] {"UT1", "GMST", "LAST", "LMST"}) {
            tf.setTimeScale(ts);
            assertEquals("UT1", tf.getAlignTimeScale(), ts);
        }
    }

    // ------------------------------------------------------------------
    // System conversion: MJD <-> JD (C errors 18-19)
    // ------------------------------------------------------------------

    /** MJD with origin 53000 and JD with origin 2453000.5 describe the same instants. */
    @Test
    void testMjdToJdWithMatchingOffsets() {
        TimeFrame tf1 = frame("MJD");
        tf1.setTimeOriginValue(53000.0);
        TimeFrame tf2 = frame("JD");
        tf2.setTimeOriginValue(2_453_000.5);
        assertClose(100.0, convertValue(tf1, tf2, 100.0), 1.0e-10, "MJD+53000 to JD+2453000.5");
    }

    @Test
    void testMjdToJdOffset24000005() {
        assertClose(2_400_000.5, convertValue(frame("MJD"), frame("JD"), 0.0), 1.0e-10, "MJD 0 as a JD");
    }

    @Test
    void testJdToMjd() {
        assertClose(51544.5, convertValue(frame("JD"), frame("MJD"), 2_451_544.5 + 0.5), 1.0e-10, "JD 2451545");
    }

    // ------------------------------------------------------------------
    // System conversion: MJD <-> Julian epoch (J2000.0 = MJD 51544.5)
    // ------------------------------------------------------------------

    @Test
    void testMjdToJepochJ2000() {
        assertClose(2000.0, convertValue(frame("MJD"), frame("JEPOCH"), 51544.5), 1.0e-10, "MJD 51544.5");
    }

    @Test
    void testJepochToMjdJ2000() {
        assertClose(51544.5, convertValue(frame("JEPOCH"), frame("MJD"), 2000.0), 1.0e-10, "J2000.0");
    }

    @Test
    void testMjdJepochRoundTrip() {
        assertClose(51544.5, roundTrip(frame("MJD"), frame("JEPOCH"), 51544.5), 1.0e-10, "round trip");
    }

    // ------------------------------------------------------------------
    // TimeOrigin handling
    // ------------------------------------------------------------------

    @Test
    void testTimeOriginOffset() {
        TimeFrame tf1 = frame("MJD");
        tf1.setTimeOriginValue(53000.0);
        TimeFrame tf2 = frame("JD");
        tf2.setTimeOriginValue(2_453_000.5);
        assertClose(100.0, convertValue(tf1, tf2, 100.0), 1.0e-10, "TimeOrigin");
    }

    // ------------------------------------------------------------------
    // Time scale conversions (C errors 36-46)
    // ------------------------------------------------------------------

    /** TAI is ahead of UTC by the leap seconds: 37 at MJD 57844 (2017). */
    @Test
    void testTaiToUtc() {
        TimeFrame tf2 = frame("MJD");
        tf2.setTimeScale("UTC");
        double xin = 57844.0;
        double diffSeconds = (xin - convertValue(frame("MJD"), tf2, xin)) * 86400.0;
        assertClose(37.0, diffSeconds, 1.0, "TAI-UTC at MJD 57844");
    }

    /** TT is TAI + 32.184 seconds. */
    @Test
    void testTaiToTt() {
        TimeFrame tf2 = frame("MJD");
        tf2.setTimeScale("TT");
        double xin = 51544.5;
        double diffSeconds = (convertValue(frame("MJD"), tf2, xin) - xin) * 86400.0;
        assertClose(32.184, diffSeconds, 1.0e-6, "TT-TAI");
    }

    /** With Dtai set to 40, TAI-UTC is 40 seconds. */
    @Test
    void testTaiToUtcWithDtai() {
        TimeFrame tf2 = frame("MJD");
        tf2.setTimeScale("UTC");
        tf2.setDtai(40.0);
        double xin = 57844.0;
        double diffSeconds = (xin - convertValue(frame("MJD"), tf2, xin)) * 86400.0;
        assertClose(40.0, diffSeconds, 1.0e-3, "TAI-UTC with Dtai=40");
    }

    // ------------------------------------------------------------------
    // GMST -> LMST (C errors 47-48)
    // ------------------------------------------------------------------

    /** At 90 degrees east, LMST is GMST plus a quarter of a day. */
    @Test
    void testGmstToLmst() {
        TimeFrame tf1 = frame("MJD");
        tf1.setTimeScale("GMST");
        tf1.setObsLonValue(Math.toRadians(90.0));
        tf1.setTimeOriginValue(53000.0);
        TimeFrame tf2 = frame("MJD");
        tf2.setTimeScale("LMST");
        tf2.setObsLonValue(Math.toRadians(90.0));
        tf2.setTimeOriginValue(53000.0);
        assertClose(1.25, convertValue(tf1, tf2, 1.0), 1.0e-10, "GMST+1d at lon=90");
        assertClose(1.0, roundTrip(tf1, tf2, 1.0), 1.0e-10, "GMST->LMST round trip");
    }

    // ------------------------------------------------------------------
    // MJD to Besselian epoch (C errors 20-21)
    // ------------------------------------------------------------------

    @Test
    void testMjdUtcToBepoch() {
        TimeFrame tf1 = frame("MJD");
        tf1.setTimeScale("UTC");
        tf1.setTimeOriginValue(53000.0);
        TimeFrame tf2 = frame("BEPOCH");
        tf2.setTimeOriginValue(2004.0);
        // testtime.c error 20 allows 1e-10; error 21 allows 1e-6 for the round trip.
        assertClose(0.2600974092354136, convertValue(tf1, tf2, 100.0), 1.0e-10, "MJD(UTC) 53100");
        assertClose(100.0, roundTrip(tf1, tf2, 100.0), 1.0e-6, "MJD(UTC)->BEPOCH round trip");
    }

    // ------------------------------------------------------------------
    // Distance
    // ------------------------------------------------------------------

    @Test
    void testDistance() {
        TimeFrame tf = frame("MJD");
        assertClose(5.0, tf.distance(new double[] {10.0}, new double[] {15.0}), 1.0e-15, "distance");
        assertClose(5.0, tf.distance(new double[] {15.0}, new double[] {10.0}), 1.0e-15, "distance");
    }

    // ------------------------------------------------------------------
    // Serialisation round trip
    // ------------------------------------------------------------------

    @Test
    void testSerialisationRoundTrip() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("UTC");
        tf.setTimeOriginValue(53000.0);
        tf.setObsLonValue(0.5);
        tf.setObsLatValue(0.3);
        tf.setDtai(37.0);
        TimeFrame tf2 = assertInstanceOf(TimeFrame.class, ChannelText.read(ChannelText.dump(tf)));
        assertEquals("MJD", tf2.getSystem());
        assertEquals("UTC", tf2.getTimeScale());
        assertClose(53000.0, tf2.getTimeOriginValue(), 1.0e-15, "TimeOrigin");
        assertClose(37.0, tf2.getDtai(), 1.0e-15, "Dtai");
    }

    @Test
    void testSerialisationWithAlignTimescale() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeScale("TDB");
        tf.setAlignTimeScale("UT1");
        tf.setLtOffset(5.5);
        TimeFrame tf2 = assertInstanceOf(TimeFrame.class, ChannelText.read(ChannelText.dump(tf)));
        assertEquals("JEPOCH", tf2.getSystem());
        assertEquals("TDB", tf2.getTimeScale());
        assertEquals("UT1", tf2.getAlignTimeScale());
        assertClose(5.5, tf2.getLtOffset(), 1.0e-15, "LTOffset");
    }

    /** The Unit and Format are written in the Axis and come back; a date Format relabels the axis. */
    @Test
    void testSerialisationWithUnitAndFormat() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("TAI");
        tf.setUnit(1, "s");
        tf.setFormat(1, "iso.2");
        String text = ChannelText.dump(tf);
        assertTrue(text.contains("Unit = \"s\""), "missing Unit: " + text);
        assertTrue(text.contains("Format = \"iso.2\""), "missing Format: " + text);
        assertTrue(text.contains("Lbl1 = \"Date/Time\""), "missing label: " + text);
        TimeFrame tf2 = assertInstanceOf(TimeFrame.class, ChannelText.read(text));
        assertEquals("s", tf2.getUnit(1));
        assertTrue(tf2.testFormat(1));
        assertEquals("iso.2", tf2.getFormat(1));
    }

    // ------------------------------------------------------------------
    // Default Title (timeframe.c GetTitle)
    // ------------------------------------------------------------------

    /** The default Title, as the commented-out Title line of a dump gives it. */
    private static String defaultTitle(TimeFrame tf) {
        String text = ChannelText.dump(tf);
        String prefix = "#   Title = \"";
        for (String line : CValues.lines(text)) {
            String t = line.trim();
            if (t.startsWith(prefix)) {
                String rest = t.substring(prefix.length());
                return rest.substring(0, rest.indexOf('"'));
            }
        }
        fail("no default Title line in dump:\n" + text);
        return null;
    }

    /** With no TimeScale set, the Title is the System's label; a Besselian epoch never shows one. */
    @Test
    void testDefaultTitleSystemOnly() {
        assertEquals("Modified Julian Date", defaultTitle(frame("MJD")));
        assertEquals("Besselian Epoch", defaultTitle(frame("BEPOCH")));
    }

    @Test
    void testDefaultTitleWithTimescale() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("TAI");
        assertEquals("Modified Julian Date [TAI]", defaultTitle(tf));
    }

    @Test
    void testDefaultTitleOffsetFromDate() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);
        assertEquals("Julian Epoch offset from 2004-12-31 18:00:00", defaultTitle(tf));

        tf = frame("MJD");
        tf.setTimeScale("UTC");
        tf.setTimeOriginValue(55000.0);
        assertEquals("Modified Julian Date [UTC] offset from 2009-06-18 00:00:00", defaultTitle(tf));
    }

    @Test
    void testDefaultTitleLocalTimeOffset() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("LT");
        tf.setLtOffset(5.5);
        tf.setTimeOriginValue(55000.0);
        assertEquals("Modified Julian Date [LT (UTC+5.5)] offset from 2009-06-18 00:00:00", defaultTitle(tf));
    }

    /** A date Format is absolute, so the Title has no "offset from". */
    @Test
    void testDefaultTitleDateFormatSuppressesOffset() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("TAI");
        tf.setTimeOriginValue(55000.0);
        tf.setFormat(1, "iso.0");
        assertEquals("Modified Julian Date [TAI]", defaultTitle(tf));
    }

    // ------------------------------------------------------------------
    // System and time scale names
    // ------------------------------------------------------------------

    @Test
    void testTimeSystemParse() {
        TimeFrame tf = new TimeFrame();
        assertEquals(TimeFrame.AST__MJD, tf.systemCode("MJD"));
        assertEquals(TimeFrame.AST__JD, tf.systemCode("jd"));
        assertEquals(TimeFrame.AST__JEPOCH, tf.systemCode("JEPOCH"));
        assertEquals(TimeFrame.AST__BEPOCH, tf.systemCode("bepoch"));
        assertEquals(Frame.AST__BADSYSTEM, tf.systemCode("invalid"));
    }

    /** C's {@code SystemCode} also accepts each System's descriptive name, as a whole name. */
    @Test
    void timeSystemParseAcceptsCDescriptiveNames() {
        TimeFrame tf = new TimeFrame();
        assertEquals(TimeFrame.AST__MJD, tf.systemCode("Modified Julian Date"));
        assertEquals(TimeFrame.AST__JD, tf.systemCode("julian date"));
        assertEquals(TimeFrame.AST__BEPOCH, tf.systemCode("Besselian Epoch"));
        assertEquals(TimeFrame.AST__JEPOCH, tf.systemCode("JULIAN EPOCH"));
        assertEquals(Frame.AST__BADSYSTEM, tf.systemCode("Julian"));
    }

    /** "JEP" and "BEP" are only Symbols; trailing white space is ignored, as by astChrMatch. */
    @Test
    void timeSystemParseMatchesCNamesOnly() {
        TimeFrame tf = new TimeFrame();
        assertEquals(Frame.AST__BADSYSTEM, tf.systemCode("JEP"));
        assertEquals(Frame.AST__BADSYSTEM, tf.systemCode("bep"));
        assertEquals(TimeFrame.AST__MJD, tf.systemCode("MJD  "));
        assertEquals(Frame.AST__BADSYSTEM, tf.systemCode(" MJD"));
    }

    @Test
    void timeScaleParseIgnoresTrailingWhiteSpace() {
        assertEquals(TimeFrame.AST__TAI, TimeFrame.timeScaleCode("tai "));
        assertEquals(TimeFrame.AST__BADTS, TimeFrame.timeScaleCode(" TAI"));
    }

    @Test
    void testTimeScaleParse() {
        String[] names = {"TAI", "utc", "UT1", "GMST", "LAST", "LMST", "TT", "TDB", "TCB", "TCG", "LT"};
        int[] codes = {
            TimeFrame.AST__TAI,
            TimeFrame.AST__UTC,
            TimeFrame.AST__UT1,
            TimeFrame.AST__GMST,
            TimeFrame.AST__LAST,
            TimeFrame.AST__LMST,
            TimeFrame.AST__TT,
            TimeFrame.AST__TDB,
            TimeFrame.AST__TCB,
            TimeFrame.AST__TCG,
            TimeFrame.AST__LT
        };
        for (int i = 0; i < names.length; i++) {
            assertEquals(codes[i], TimeFrame.timeScaleCode(names[i]), names[i]);
        }
        assertEquals(TimeFrame.AST__BADTS, TimeFrame.timeScaleCode("invalid"));
    }

    // ------------------------------------------------------------------
    // Same System, different origin
    // ------------------------------------------------------------------

    @Test
    void testSameSystemDifferentOrigin() {
        TimeFrame tf1 = frame("MJD");
        tf1.setTimeOriginValue(50000.0);
        TimeFrame tf2 = frame("MJD");
        tf2.setTimeOriginValue(51000.0);
        assertClose(-900.0, convertValue(tf1, tf2, 100.0), 1.0e-10, "different origins");
    }

    // ------------------------------------------------------------------
    // Units
    // ------------------------------------------------------------------

    @Test
    void testUnitAccessors() {
        TimeFrame tf = frame("MJD");
        assertFalse(tf.testUnit(1));
        assertEquals("d", tf.getUnit(1));
        tf.setUnit(1, "s");
        assertTrue(tf.testUnit(1));
        assertEquals("s", tf.getUnit(1));
        tf.clearUnit(1);
        assertFalse(tf.testUnit(1));
        assertEquals("d", tf.getUnit(1));
    }

    /** 86400 s from the MJD origin is 1 d. */
    @Test
    void testConvertSecondsToDays() {
        TimeFrame tfS = frame("MJD");
        tfS.setUnit(1, "s");
        TimeFrame tfD = frame("MJD");
        assertClose(1.0, convertValue(tfS, tfD, 86400.0), 1.0e-9, "86400 s");
        assertClose(86400.0, roundTrip(tfS, tfD, 86400.0), 1.0e-6, "round trip");
    }

    @Test
    void testConvertSecondsDefaultUnitsUnchanged() {
        assertClose(12345.0, convertValue(frame("MJD"), frame("MJD"), 12345.0), 1.0e-12, "identity");
    }

    // ------------------------------------------------------------------
    // Date Formats (testtime.c errors 26-34)
    // ------------------------------------------------------------------

    @Test
    void testIsoFormatUnformatJepoch() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);

        assertEquals("100", tf.format(1, 100.0));
        double[] r = unformat(tf, "100");
        assertEquals(3, (int) r[1]);
        assertEquals(100.0, r[0]);

        tf.setFormat(1, "iso");
        assertEquals("2006-01-01", tf.format(1, 1.0));
        r = unformat(tf, "2006-01-01");
        assertEquals("2006-01-01".length(), (int) r[1]);
        assertEquals(1.0, r[0]);

        tf.setFormat(1, "iso.0");
        assertEquals("2006-01-01 00:00:00", tf.format(1, 1.0));
        r = unformat(tf, "2006-01-01 00:00:00");
        assertEquals("2006-01-01 00:00:00".length(), (int) r[1]);
        assertEquals(1.0, r[0]);
    }

    @Test
    void testIsoFormatUnformatSeconds() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);
        tf.setUnit(1, "s");
        tf.setFormat(1, "iso.2");

        assertEquals("2004-12-31 18:00:10.00", tf.format(1, 10.0));
        double[] r = unformat(tf, "2004-12-31 18:00:10.00");
        assertEquals("2004-12-31 18:00:10.00".length(), (int) r[1]);
        assertClose(10.0, r[0], 1.0e-3, "10 s");

        assertEquals("2004-12-31 18:00:10.12", tf.format(1, 10.12));
        r = unformat(tf, "2004-12-31 18:00:10.12");
        assertEquals("2004-12-31 18:00:10.12".length(), (int) r[1]);
        assertClose(10.12, r[0], 1.0e-3, "10.12 s");
    }

    @Test
    void testUnformatJPrefixEpoch() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);
        double[] r = unformat(tf, "J2005.0");
        assertEquals(7, (int) r[1]);
        assertEquals(0.0, r[0]);
        r = unformat(tf, "J2010.0");
        assertEquals(7, (int) r[1]);
        assertEquals(5.0, r[0]);
    }

    /** A date followed by other text: the space after the time is read too. */
    @Test
    void testUnformatTrailingText() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);
        double[] r = unformat(tf, "2005-jun-1 12:30 lunch time");
        assertEquals(17, (int) r[1]);
        assertClose(0.415525896, r[0], 1.0e-7, "2005-jun-1 12:30");
    }

    /**
     * A Besselian epoch is read in TT and converted to the frame's UTC; with the time scale set
     * before the origin, the origin is 2005 in UTC (C gives -3.5013115581077727).
     */
    @Test
    void testUnformatBPrefixUsesTt() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeScale("UTC");
        tf.setTimeOriginValue(2005.0);
        double[] r = unformat(tf, "B2001.5 lunch time");
        assertEquals(8, (int) r[1]);
        assertClose(-3.5013115581077727, r[0], 1.0e-10, "B2001.5");
    }

    /**
     * The same attributes set in the other order: setting the time scale re-expresses the origin
     * set in TAI as 2004.9999989859814 in UTC (timeframe.c {@code OriginScale}), and C gives
     * -3.501310544089165, reading 8 characters (testtime.c).
     */
    @Test
    void testUnformatBPrefixOriginThenTimescale() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);
        tf.setTimeScale("UTC");
        double[] r = unformat(tf, "B2001.5 lunch time");
        assertEquals(8, (int) r[1]);
        assertClose(-3.501310544089165, r[0], 1.0e-10, "B2001.5");
    }

    @Test
    void testIsoFormatMjdTaiIso6() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("TAI");
        tf.setFormat(1, "iso.6");
        assertEquals("1977-01-01 00:00:00.000000", tf.format(1, 43144.0));
    }

    @Test
    void testIsoFormatVariants() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("TAI");
        tf.setFormat(1, "iso");
        assertEquals("1977-01-01", tf.format(1, 43144.0));
        tf.setFormat(1, "iso.0T");
        assertEquals("1977-01-01T00:00:00", tf.format(1, 43144.0));
    }

    // ------------------------------------------------------------------
    // The Epoch from the TimeOrigin (timeframe.c GetEpoch)
    // ------------------------------------------------------------------

    @Test
    void testEpochDefaultIsJ2000() {
        TimeFrame tf = frame("MJD");
        assertClose(51544.5, tf.getEpochMjd(), 1.0e-9, "Epoch MJD");
        assertClose(2000.0, tf.getEpoch(), 1.0e-9, "Epoch year");
    }

    /** C gives astGetD(Epoch) 2005.0000010198 for a JEPOCH TimeFrame with origin 2005. */
    @Test
    void testEpochFromTimeOriginJepoch() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);
        assertClose(2005.0000010198, tf.getEpoch(), 1.0e-7, "Epoch");
    }

    /** C gives astGetD(Epoch) 2009.4606454922 for an MJD UT1 TimeFrame with origin 55000. */
    @Test
    void testEpochFromTimeOriginUt1() {
        TimeFrame tf = frame("MJD");
        tf.setTimeScale("UT1");
        tf.setTimeOriginValue(55000.0);
        assertClose(2009.4606454922, tf.getEpoch(), 1.0e-6, "Epoch");
    }

    @Test
    void testEpochExplicitOverridesTimeOrigin() {
        TimeFrame tf = frame("MJD");
        tf.setTimeOriginValue(55000.0);
        tf.setEpochMjd(50000.0);
        assertClose(50000.0, tf.getEpochMjd(), 1.0e-9, "Epoch");
    }

    // ------------------------------------------------------------------
    // TT -> TDB
    // ------------------------------------------------------------------

    /** TDB differs from TT by periodic terms of under 2 ms. */
    @Test
    void testTtToTdb() {
        TimeFrame tf1 = frame("MJD");
        tf1.setTimeScale("TT");
        TimeFrame tf2 = frame("MJD");
        tf2.setTimeScale("TDB");
        double xin = 51544.5;
        double diffMs = (convertValue(tf1, tf2, xin) - xin) * 86400.0 * 1000.0;
        assertTrue(Math.abs(diffMs) < 2.0, "TDB-TT at J2000.0: " + diffMs + " ms");
    }

    // ------------------------------------------------------------------
    // Local Time
    // ------------------------------------------------------------------

    @Test
    void testLtOffset() {
        TimeFrame tf1 = frame("MJD");
        tf1.setTimeScale("UTC");
        TimeFrame tf2 = frame("MJD");
        tf2.setTimeScale("LT");
        tf2.setLtOffset(5.0);
        double xin = 51544.5;
        double diffHours = (convertValue(tf1, tf2, xin) - xin) * 24.0;
        assertClose(5.0, diffHours, 1.0e-10, "LT-UTC");
    }

    /** The default Format is {@code %1.7G}: seven significant digits (values C gives). */
    @Test
    void formatDefaultIsSevenSignificantDigits() {
        TimeFrame tf = frame("MJD");
        assertEquals("12345.68", tf.format(1, 12345.6789));
        assertEquals("0.0001234568", tf.format(1, 0.000123456789));
        assertEquals("51544.5", tf.format(1, 51544.5));
    }

    // ------------------------------------------------------------------
    // TimeOrigin re-expressed when the System or TimeScale changes
    // ------------------------------------------------------------------

    /**
     * J2005.0 in TAI re-expressed in UTC is 2004.9999989859814, and back in TAI
     * 2004.9999999999998 (values C gives).
     */
    @Test
    void testSetTimescaleReexpressesTimeOrigin() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeOriginValue(2005.0);
        assertEquals("TAI", tf.getTimeScale());
        tf.setTimeScale("UTC");
        assertClose(2004.9999989859814, tf.getTimeOriginValue(), 1.0e-9, "TimeOrigin in UTC");
        tf.clearTimeScale();
        assertEquals("TAI", tf.getTimeScale());
        assertClose(2004.9999999999998, tf.getTimeOriginValue(), 1.0e-9, "TimeOrigin back in TAI");
    }

    /** J2005.0 in TAI is MJD 53370.75 in TAI (value C gives). */
    @Test
    void testSetSystemReexpressesTimeOrigin() {
        TimeFrame tf = frame("JEPOCH");
        tf.setTimeScale("TAI");
        tf.setTimeOriginValue(2005.0);
        tf.setSystem("MJD");
        assertClose(53370.75, tf.getTimeOriginValue(), 1.0e-6, "TimeOrigin in MJD");
    }
}
