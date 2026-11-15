package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_timemap.rs}, itself from C's {@code testtimemap.c}: the
 * time conversions, alone and chained, in both directions, bad values, the rate and native round
 * trips.
 *
 * <p>{@code cvt_type_from_code} is translated as the codes {@code astTimeAdd} accepts. Deferred:
 * {@code equality} ({@code astEqual}, an out-of-scope method); {@code cvt_type_codes_match_c},
 * {@code cvt_type_from_int} and {@code cvt_type_inverse} (Rust-only API); {@code
 * try_merge_cancellation}, the {@code timemap_merge_*} tests and {@code timemap_simplified_chain}
 * ({@code needs simplification}).
 */
class TimeMapTest {

    private static final double TOL = 1.0e-10;
    private static final double BAD = AstObject.AST__BAD;

    private static TimeMap time(String cvt, double... args) {
        TimeMap m = new TimeMap();
        m.timeAdd(cvt, args);
        return m;
    }

    private static double transformOne(Mapping m, double val, boolean forward) {
        return m.transform(new double[][] {{val}}, 1, forward)[0][0];
    }

    private static void assertClose(double actual, double expected, double tol, String label) {
        assertTrue(
                Math.abs(actual - expected) < tol,
                label + ": expected " + expected + ", got " + actual + " (diff " + Math.abs(actual - expected) + ")");
    }

    // ------------------------------------------------------------------
    // MJD, JD and epochs
    // ------------------------------------------------------------------

    @Test
    void mjdToJd() {
        assertClose(transformOne(time("MJDTOJD", 0.0, 0.0), 51544.5, true), 2451545.0, TOL, "MJD 51544.5 -> JD");
    }

    @Test
    void jdToMjd() {
        assertClose(transformOne(time("JDTOMJD", 0.0, 0.0), 2451545.0, true), 51544.5, TOL, "JD 2451545.0 -> MJD");
    }

    @Test
    void mjdJdRoundTrip() {
        TimeMap m = time("MJDTOJD", 0.0, 0.0);
        m.timeAdd("JDTOMJD", new double[] {0.0, 0.0});
        assertClose(transformOne(m, 51544.5, true), 51544.5, TOL, "MJD->JD->MJD round-trip");
    }

    @Test
    void mjdToJulianEpoch() {
        assertClose(transformOne(time("MJDTOJEP", 0.0, 0.0), 51544.5, true), 2000.0, TOL, "MJD -> Julian epoch");
    }

    @Test
    void julianEpochToMjd() {
        assertClose(transformOne(time("JEPTOMJD", 0.0, 0.0), 2000.0, true), 51544.5, TOL, "Julian epoch -> MJD");
    }

    @Test
    void mjdJepRoundTrip() {
        TimeMap m = time("MJDTOJEP", 0.0, 0.0);
        double jep = transformOne(m, 51544.5, true);
        assertClose(transformOne(m, jep, false), 51544.5, TOL, "MJD->JEP->MJD round-trip");
    }

    @Test
    void mjdToBesselianEpoch() {
        // B1950.0 is MJD 33281.92346, approximately.
        assertClose(transformOne(time("MJDTOBEP", 0.0, 0.0), 33281.92346, true), 1950.0, 1.0e-5, "MJD -> B1950");
    }

    @Test
    void mjdBepRoundTrip() {
        TimeMap m = time("MJDTOBEP", 0.0, 0.0);
        double bep = transformOne(m, 40000.0, true);
        assertClose(transformOne(m, bep, false), 40000.0, TOL, "MJD->BEP->MJD round-trip");
    }

    // ------------------------------------------------------------------
    // Time scales
    // ------------------------------------------------------------------

    @Test
    void utcToTaiAtJ2000() {
        // At J2000.0 TAI-UTC is 32 seconds; a bad DTAI selects the leap-second table.
        assertClose(
                transformOne(time("UTCTOTAI", 0.0, BAD), 51544.5, true),
                51544.5 + 32.0 / 86400.0,
                1.0e-12,
                "UTC->TAI at J2000");
    }

    @Test
    void taiToUtcAtJ2000() {
        assertClose(
                transformOne(time("TAITOUTC", 0.0, BAD), 51544.5 + 32.0 / 86400.0, true),
                51544.5,
                1.0e-12,
                "TAI->UTC at J2000");
    }

    @Test
    void utcTaiRoundTrip() {
        TimeMap m = time("UTCTOTAI", 0.0, BAD);
        double tai = transformOne(m, 51544.5, true);
        assertClose(transformOne(m, tai, false), 51544.5, 1.0e-12, "UTC->TAI->UTC round-trip");
    }

    @Test
    void taiToTt() {
        assertClose(transformOne(time("TAITOTT", 0.0), 51544.5, true), 51544.5 + 32.184 / 86400.0, 1.0e-15, "TAI->TT");
    }

    @Test
    void ttToTai() {
        assertClose(transformOne(time("TTTOTAI", 0.0), 51544.5 + 32.184 / 86400.0, true), 51544.5, 1.0e-15, "TT->TAI");
    }

    @Test
    void mjdToMjdOffset() {
        // 1544.5 days after MJD 50000 is 544.5 days after MJD 51000.
        assertClose(transformOne(time("MJDTOMJD", 50000.0, 51000.0), 1544.5, true), 544.5, TOL, "MJD offsets");
    }

    @Test
    void ttTcgRoundTrip() {
        TimeMap m = time("TTTOTCG", 51544.0);
        double tcg = transformOne(m, 0.5, true);
        assertClose(transformOne(m, tcg, false), 0.5, 1.0e-15, "TT->TCG->TT round-trip");
    }

    @Test
    void tdbTcbRoundTrip() {
        TimeMap m = time("TDBTOTCB", 51544.0);
        double tcb = transformOne(m, 0.5, true);
        assertClose(transformOne(m, tcb, false), 0.5, 1.0e-15, "TDB->TCB->TDB round-trip");
    }

    @Test
    void utGmstRoundTrip() {
        TimeMap m = time("UTTOGMST", 51544.0);
        double gmst = transformOne(m, 0.5, true);
        assertClose(transformOne(m, gmst, false), 0.5, 1.0e-10, "UT->GMST->UT round-trip");
    }

    @Test
    void utUtcDut1() {
        assertClose(transformOne(time("UTTOUTC", 0.3), 51544.5, true), 51544.5 - 0.3 / 86400.0, 1.0e-15, "UT1->UTC");
    }

    @Test
    void ltUtcTimezone() {
        assertClose(transformOne(time("LTTOUTC", 5.0), 51544.5, true), 51544.5 - 5.0 / 24.0, 1.0e-15, "LT->UTC");
    }

    // ------------------------------------------------------------------
    // Inversion, bad values and chains
    // ------------------------------------------------------------------

    @Test
    void inversion() {
        TimeMap m = time("MJDTOJEP", 0.0, 0.0);
        assertClose(transformOne(m, 51544.5, true), 2000.0, TOL, "forward");
        m.invert();
        assertClose(transformOne(m, 2000.0, true), 51544.5, TOL, "inverted TimeMap");
    }

    @Test
    void badValuePropagation() {
        assertEquals(BAD, transformOne(time("MJDTOJD", 0.0, 0.0), BAD, true));
    }

    @Test
    void chainUtcToTt() {
        TimeMap m = time("UTCTOTAI", 0.0, BAD);
        m.timeAdd("TAITOTT", new double[] {0.0});
        // At J2000.0 TT-UTC is 32 + 32.184 seconds.
        assertClose(transformOne(m, 51544.5, true), 51544.5 + 64.184 / 86400.0, 1.0e-12, "UTC->TAI->TT");
    }

    @Test
    void serialisationRoundTrip() {
        TimeMap m = time("MJDTOJD", 100.0, 200.0);
        m.timeAdd("UTCTOTAI", new double[] {51544.0, BAD});
        m.timeAdd("TAITOTT", new double[] {51544.0});
        Mapping m2 = (Mapping) ChannelText.read(ChannelText.dump(m));
        assertEquals("TimeMap", m2.astClass());
        assertEquals(transformOne(m, 51544.5, true), transformOne(m2, 51544.5, true));
    }

    @Test
    void ttTdbRoundTrip() {
        TimeMap m = time("TTTOTDB", 51544.0, 0.0, 0.0, 0.0, 32.0);
        double tdb = transformOne(m, 0.5, true);
        assertClose(transformOne(m, tdb, false), 0.5, 1.0e-10, "TT->TDB->TT round-trip");
        // TDB-TT is at most about 1.6 ms, 1.9e-8 days.
        assertTrue(Math.abs(tdb - 0.5) < 2.0e-8, "TDB-TT difference too large: " + Math.abs(tdb - 0.5));
    }

    @Test
    void gmstLmstRoundTrip() {
        TimeMap m = time("GMSTTOLMST", 51544.0, 1.0, 0.5);
        double lmst = transformOne(m, 0.5, true);
        assertClose(transformOne(m, lmst, false), 0.5, 1.0e-15, "GMST->LMST->GMST round-trip");
    }

    @Test
    void lmstLastRoundTrip() {
        TimeMap m = time("LMSTTOLAST", 51544.0, 0.5, 0.8);
        double last = transformOne(m, 0.5, true);
        // The equation of the equinoxes is found at an approximate TDB, so the round trip is
        // good to about 1e-10.
        assertClose(transformOne(m, last, false), 0.5, 1.0e-10, "LMST->LAST->LMST round-trip");
    }

    /** timemap.c CvtCode matches the names case-insensitively; any other is AST__TIMIN. */
    @Test
    void cvtTypeFromCode() {
        assertTrue(ChannelText.dump(time("MJDTOJD", 0.0, 0.0)).contains("\"MJDTOJD\""));
        assertTrue(ChannelText.dump(time("mjdtojd", 0.0, 0.0)).contains("\"MJDTOJD\""));
        AstException e = assertThrows(AstException.class, () -> time("BOGUS", 0.0, 0.0));
        assertEquals(AstException.AST__TIMIN, e.getStatus());
    }

    /**
     * From C's {@code testtime.c}: one UTC MJD in each of the 14 pre-1972 eras of drifting TAI-UTC.
     * TAI-UTC there matches the published value to 1e-5 seconds, and UTC to TAI to UTC returns to
     * the start to 1e-9 days.
     */
    @Test
    void utcTaiRoundtripPre1972Eras() {
        double[] eraUtc = {
            40000.0, 39500.0, 39060.0, 38970.0, 38880.0, 38790.0, 38700.0, 38560.0, 38440.0, 38360.0, 38000.0, 37590.0,
            37400.0, 37200.0
        };
        double[] eraDt = {
            6.478578, 5.282578, 4.227634, 4.010994, 3.794354, 3.577714, 3.361074, 3.079634, 2.824114, 2.726482,
            2.222130, 1.748658, 1.552418, 1.288218
        };
        TimeMap fwd = time("UTCTOTAI", 0.0, BAD);
        TimeMap inv = time("TAITOUTC", 0.0, BAD);
        for (int i = 0; i < eraUtc.length; i++) {
            double tai = transformOne(fwd, eraUtc[i], true);
            assertClose((tai - eraUtc[i]) * 86400.0, eraDt[i], 1e-5, "TAI-UTC at MJD " + eraUtc[i]);
            assertClose(transformOne(inv, tai, true), eraUtc[i], 1e-9, "UTC->TAI->UTC at MJD " + eraUtc[i]);
        }
    }

    /**
     * testtime.c errors 62 and 63: the rate is the slope of the conversions in the direction the
     * TimeMap applies, exactly as C's analytic Rate gives it.
     */
    @Test
    void rateIsTheAnalyticSlopeInEitherDirection() {
        TimeMap m = time("MJDTOJEP", 0.0, 0.0);
        assertEquals(1.0 / 365.25, m.rate(new double[] {51544.5}, 1, 1));
        m.invert();
        assertEquals(365.25, m.rate(new double[] {2000.0}, 1, 1));
    }

    // ------------------------------------------------------------------
    // Java API
    // ------------------------------------------------------------------

    /** timemap.c AddTimeCvt: the arguments must number exactly as the conversion needs. */
    @Test
    void theArgumentCountIsChecked() {
        TimeMap m = new TimeMap(0);
        AstException e = assertThrows(AstException.class, () -> m.timeAdd("TTTOTDB", new double[4]));
        assertEquals(AstException.AST__TIMIN, e.getStatus());
        e = assertThrows(AstException.class, () -> m.timeAdd("TAITOTT", null));
        assertEquals(AstException.AST__TIMIN, e.getStatus());
        m.timeAdd("TAITOTT", new double[] {0.0});
    }

    /** A copy has its own conversions. */
    @Test
    void aCopyHasItsOwnConversions() {
        TimeMap m = time("MJDTOJD", 0.0, 0.0);
        TimeMap c = (TimeMap) m.copy();
        c.timeAdd("JDTOMJD", new double[] {0.0, 0.0});
        assertArrayEquals(new double[] {51544.5}, c.tran1(1, new double[] {51544.5}, true));
        assertArrayEquals(new double[] {2451545.0}, m.tran1(1, new double[] {51544.5}, true));
    }

    /**
     * The rate of a TimeMap with a GMST conversion is found numerically (timemap.c Rate), and is
     * close to the ratio of the sidereal to the solar day.
     */
    @Test
    void aGmstRateIsFoundNumerically() {
        double rate = time("UTTOGMST", 0.0).rate(new double[] {51544.5}, 1, 1);
        assertClose(rate, 1.00273790935, 1e-7, "GMST rate");
    }
}
