package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_slamap.rs}: SlaMap's conversions, alone and chained, in
 * both directions, bad values and native round trips.
 *
 * <p>The Rust tests of {@code SlaCvtType}'s methods are translated as the behaviour C gives the
 * conversion codes through {@code astSlaAdd}, {@code Transform} and {@code Dump}, except {@code
 * cvt_from_int} (Rust-only API). The {@code try_merge} tests and {@code
 * simplify_inverted_map_step_swaps_date_and_equinox}, of SlaMap's merging when simplified, are
 * deferred ({@code needs simplification}).
 */
class SlaMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static void assertClose(double actual, double expected, double tol, String label) {
        assertTrue(
                Math.abs(actual - expected) < tol,
                label + ": expected " + expected + ", got " + actual + " (diff " + Math.abs(actual - expected) + ")");
    }

    private static double deg2rad(double d) {
        return d * Math.PI / 180.0;
    }

    private static double rad2deg(double r) {
        return r * 180.0 / Math.PI;
    }

    /** The angle in [0, 2 pi). */
    private static double normalizeAngle(double a) {
        double twopi = 2.0 * Math.PI;
        double r = a % twopi;
        if (r < 0.0) {
            r += twopi;
        }
        return r;
    }

    /** Asserts that two angles are within {@code tol} of each other, modulo 2 pi. */
    private static void assertAngleClose(double actual, double expected, double tol, String label) {
        double diff = normalizeAngle(actual - expected);
        if (diff > Math.PI) {
            diff = 2.0 * Math.PI - diff;
        }
        assertTrue(diff < tol, label + ": expected " + expected + ", got " + actual + " (angular diff " + diff + ")");
    }

    private static SlaMap sla(String cvt, double... args) {
        SlaMap m = new SlaMap();
        m.add(cvt, args);
        return m;
    }

    private static double[] fwd(Mapping m, double lon, double lat) {
        return point(transformRows(m, true, new double[] {lon, lat}), 0);
    }

    private static double[] inv(Mapping m, double lon, double lat) {
        return point(transformRows(m, false, new double[] {lon, lat}), 0);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @Test
    void newEmpty() {
        SlaMap m = new SlaMap();
        assertEquals(2, m.getNin());
        assertEquals(2, m.getNout());
        assertFalse(m.getInvert());
        // slamap.c Dump: Nsla is set only when there are conversion steps.
        assertFalse(ChannelText.dump(m).contains("Sla1"));
    }

    @Test
    void addSteps() {
        SlaMap m = new SlaMap();
        m.add("PREC", new double[] {2000.0, 2020.0});
        m.add("EQGAL", new double[0]);
        String text = ChannelText.dump(m);
        assertTrue(text.contains("Nsla = 2"), text);
        assertTrue(text.contains("Sla2 = \"EQGAL\""), text);
    }

    // ------------------------------------------------------------------
    // The conversion codes (slamap.c CvtCode and CvtString)
    // ------------------------------------------------------------------

    /** Each conversion takes the number of arguments slamap.c CvtString gives it. */
    @Test
    void cvtNargs() {
        String[] codes = {
            "GALEQ", "EQGAL", "GALSUP", "SUPGAL", "ADDET", "FK45Z", "ECLEQ", "E2H", "PREC", "MAP", "HPCEQ", "J2000H",
            "HEEQ"
        };
        int[] nargs = {0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 4, 0, 1};
        for (int i = 0; i < codes.length; i++) {
            String code = codes[i];
            int n = nargs[i];
            new SlaMap().add(code, new double[n]);
            AstException e = assertThrows(AstException.class, () -> new SlaMap().add(code, new double[n + 1]));
            assertEquals(AstException.AST__TIMIN, e.getStatus(), code);
        }
    }

    /** Each code is written to a dump as given, and reads back as the same conversion. */
    @Test
    void cvtCodeRoundTrip() {
        String[] codes = {
            "ADDET", "SUBET", "PREBN", "PREC", "FK45Z", "FK54Z", "AMP", "MAP", "ECLEQ", "EQECL", "GALEQ", "EQGAL",
            "GALSUP", "SUPGAL", "HFK5Z", "FK5HZ", "H2E", "E2H"
        };
        int[] nargs = {1, 1, 2, 2, 1, 1, 2, 2, 1, 1, 0, 0, 0, 0, 1, 1, 2, 2};
        for (int i = 0; i < codes.length; i++) {
            double[] args = new double[nargs[i]];
            for (int j = 0; j < args.length; j++) {
                args[j] = 2000.0 + j;
            }
            SlaMap m = sla(codes[i], args);
            String text = ChannelText.dump(m);
            assertTrue(text.contains("Sla1 = \"" + codes[i] + "\""), text);
            assertEquals(text, ChannelText.dump(ChannelText.read(text)), codes[i]);
        }
    }

    @Test
    void cvtFromCodeCaseInsensitive() {
        assertTrue(ChannelText.dump(sla("prec", 2000.0, 2010.0)).contains("\"PREC\""));
        assertTrue(ChannelText.dump(sla("Eqgal")).contains("\"EQGAL\""));
        assertTrue(ChannelText.dump(sla("GALEQ")).contains("\"GALEQ\""));
    }

    /**
     * Each conversion's inverse is its partner's forward conversion, as slamap.c Transform applies
     * them; PREC and PREBN are their own partners with the epochs exchanged.
     */
    @Test
    void cvtInversePairs() {
        String[][] pairs = {
            {"ADDET", "SUBET"},
            {"FK45Z", "FK54Z"},
            {"ECLEQ", "EQECL"},
            {"GALEQ", "EQGAL"},
            {"GALSUP", "SUPGAL"},
            {"HFK5Z", "FK5HZ"},
            {"H2E", "E2H"}
        };
        double[][] args = {{1984.0}, {1984.0}, {55197.0}, {}, {}, {2000.0}, {0.8, 0.0}};
        for (int i = 0; i < pairs.length; i++) {
            SlaMap a = sla(pairs[i][0], args[i]);
            SlaMap b = sla(pairs[i][1], args[i]);
            assertArrayEquals(inv(a, 1.0, 0.5), fwd(b, 1.0, 0.5), pairs[i][0]);
            assertArrayEquals(inv(b, 1.0, 0.5), fwd(a, 1.0, 0.5), pairs[i][1]);
        }
        assertArrayEquals(inv(sla("PREC", 2000.0, 2020.0), 1.0, 0.5), fwd(sla("PREC", 2020.0, 2000.0), 1.0, 0.5));
        assertArrayEquals(inv(sla("PREBN", 1950.0, 2000.0), 1.0, 0.5), fwd(sla("PREBN", 2000.0, 1950.0), 1.0, 0.5));
        assertArrayEquals(
                inv(sla("AMP", 55197.0, 2000.0), 1.0, 0.5), fwd(sla("MAP", 2000.0, 55197.0), 1.0, 0.5), "AMP");
    }

    /**
     * slamap.c CvtCode recognises only the documented names, matched as astChrMatch matches:
     * case-insensitively, ignoring trailing white space. Any other is AST__SLAIN (slamap.c SlaAdd).
     */
    @Test
    void cvtCodesMatchAsCMatchesThem() {
        assertTrue(ChannelText.dump(sla("h2e ", 0.8, 0.0)).contains("\"H2E\""));
        assertTrue(ChannelText.dump(sla("E2H\t", 0.8, 0.0)).contains("\"E2H\""));
        for (String code : new String[] {"DH2E", "DE2H", " H2E"}) {
            AstException e = assertThrows(AstException.class, () -> sla(code, 0.8, 0.0));
            assertEquals(AstException.AST__SLAIN, e.getStatus(), code);
        }
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    @Test
    void galacticToFk5() {
        double[] out = fwd(sla("GALEQ"), 0.0, 0.0);
        // The galactic centre: RA ~ 266.4 deg, Dec ~ -28.9 deg.
        assertClose(rad2deg(normalizeAngle(out[0])), 266.405, 0.01, "galactic centre RA");
        assertClose(rad2deg(out[1]), -28.936, 0.01, "galactic centre Dec");
    }

    @Test
    void fk5GalFk5RoundTrip() {
        SlaMap m = sla("EQGAL");
        double[] gal = fwd(m, 1.5, 0.3);
        double[] back = inv(m, gal[0], gal[1]);
        assertClose(back[0], 1.5, 1e-10, "ra round-trip");
        assertClose(back[1], 0.3, 1e-10, "dec round-trip");
    }

    @Test
    void precessionRoundTrip() {
        SlaMap m = sla("PREC", 2000.0, 2020.0);
        double[] afterPrecession = fwd(m, 1.0, 0.5);
        assertTrue(Math.abs(afterPrecession[0] - 1.0) > 1e-6, "precession should change RA");
        double[] back = inv(m, afterPrecession[0], afterPrecession[1]);
        assertClose(back[0], 1.0, 1e-10, "ra prec round-trip");
        assertClose(back[1], 0.5, 1e-10, "dec prec round-trip");
    }

    @Test
    void multiStepFk4Fk5Galactic() {
        SlaMap m = new SlaMap();
        m.add("FK45Z", new double[] {1950.0});
        m.add("EQGAL", null);
        double ra = deg2rad(180.0);
        double dec = deg2rad(45.0);
        double[] out = fwd(m, ra, dec);
        assertTrue(Double.isFinite(out[0]), "galactic longitude should be finite");
        assertTrue(Double.isFinite(out[1]), "galactic latitude should be finite");
        // The FK45Z/FK54Z leg leaves a ~5e-11 residual (fk4Fk5RoundTrip).
        double[] back = inv(m, out[0], out[1]);
        assertAngleClose(back[0], ra, 1e-9, "fk4 ra round-trip");
        assertClose(back[1], dec, 1e-9, "fk4 dec round-trip");
    }

    @Test
    void inversionIdentity() {
        SlaMap m = new SlaMap();
        m.add("PREC", new double[] {2000.0, 2020.0});
        m.add("EQGAL", new double[0]);
        double[] f = fwd(m, 2.0, -0.3);
        double[] back = inv(m, f[0], f[1]);
        assertClose(back[0], 2.0, 1e-10, "identity ra");
        assertClose(back[1], -0.3, 1e-10, "identity dec");
    }

    @Test
    void invertFlag() {
        SlaMap m = sla("EQGAL");
        double[] gal = fwd(m, 1.0, 0.5);
        m.invert();
        assertTrue(m.getInvert());
        double[] back = fwd(m, gal[0], gal[1]);
        assertClose(back[0], 1.0, 1e-10, "inverted ra");
        assertClose(back[1], 0.5, 1e-10, "inverted dec");
    }

    @Test
    void emptySlamapIsIdentity() {
        SlaMap m = new SlaMap();
        assertArrayEquals(new double[] {1.23, 0.45}, fwd(m, 1.23, 0.45));
        assertArrayEquals(new double[] {1.23, 0.45}, inv(m, 1.23, 0.45));
    }

    @Test
    void badValuePropagation() {
        SlaMap m = sla("GALEQ");
        assertArrayEquals(new double[] {BAD, BAD}, fwd(m, BAD, 0.5));
        assertArrayEquals(new double[] {BAD, BAD}, fwd(m, 1.0, BAD));
        assertArrayEquals(new double[] {BAD, BAD}, fwd(m, Double.NaN, 0.5));
    }

    // ------------------------------------------------------------------
    // Native dumps
    // ------------------------------------------------------------------

    @Test
    void serialisationRoundTrip() {
        SlaMap m = new SlaMap();
        m.add("PREC", new double[] {2000.0, 2020.0});
        m.add("EQGAL", new double[0]);
        Mapping m2 = (Mapping) ChannelText.read(ChannelText.dump(m));
        assertEquals("SlaMap", m2.astClass());
        assertArrayEquals(fwd(m, 1.5, 0.3), fwd(m2, 1.5, 0.3));
    }

    @Test
    void textStructure() {
        SlaMap m = new SlaMap();
        m.add("PREC", new double[] {2000.0, 2020.0});
        m.add("EQGAL", new double[0]);
        String text = ChannelText.dump(m);
        assertTrue(text.contains("Begin SlaMap"), text);
        assertTrue(text.contains("Nsla"), text);
        assertTrue(text.contains("PREC"), text);
        assertTrue(text.contains("EQGAL"), text);
        assertTrue(text.contains("End SlaMap"), text);
    }

    @Test
    void serialiseInverted() {
        SlaMap m = sla("EQGAL");
        m.invert();
        Mapping m2 = (Mapping) ChannelText.read(ChannelText.dump(m));
        assertTrue(m2.getInvert());
    }

    // ------------------------------------------------------------------
    // Further conversions
    // ------------------------------------------------------------------

    @Test
    void eclipticRoundTrip() {
        SlaMap m = sla("EQECL", 51544.5);
        double[] ecl = fwd(m, 1.0, 0.5);
        double[] back = inv(m, ecl[0], ecl[1]);
        assertClose(back[0], 1.0, 1e-10, "ecliptic ra round-trip");
        assertClose(back[1], 0.5, 1e-10, "ecliptic dec round-trip");
    }

    @Test
    void supergalacticRoundTrip() {
        SlaMap m = sla("GALSUP");
        double[] sup = fwd(m, 1.0, 0.3);
        double[] back = inv(m, sup[0], sup[1]);
        assertClose(back[0], 1.0, 1e-10, "supergal gl round-trip");
        assertClose(back[1], 0.3, 1e-10, "supergal gb round-trip");
    }

    @Test
    void fk5IcrsRoundTrip() {
        SlaMap m = sla("FK5HZ", 2000.0);
        double[] icrs = fwd(m, 2.0, 0.4);
        double[] back = inv(m, icrs[0], icrs[1]);
        assertClose(back[0], 2.0, 1e-9, "fk5-icrs ra round-trip");
        assertClose(back[1], 0.4, 1e-9, "fk5-icrs dec round-trip");
    }

    @Test
    void horizonRoundTrip() {
        SlaMap m = sla("E2H", 0.8, 0.0);
        double[] hor = fwd(m, 0.5, 0.4);
        double[] back = inv(m, hor[0], hor[1]);
        assertClose(back[0], 0.5, 1e-10, "horizon ha round-trip");
        assertClose(back[1], 0.4, 1e-10, "horizon dec round-trip");
    }

    @Test
    void aberrationRoundTrip() {
        SlaMap m = sla("ADDET", 1984.0);
        double[] apparent = fwd(m, 1.0, 0.5);
        double[] back = inv(m, apparent[0], apparent[1]);
        assertClose(back[0], 1.0, 1e-9, "aberration rm round-trip");
        assertClose(back[1], 0.5, 1e-9, "aberration dm round-trip");
    }

    @Test
    void meanApparentRoundTrip() {
        SlaMap m = sla("MAP", 2000.0, 51544.5);
        double[] apparent = fwd(m, 2.0, 0.3);
        double[] back = inv(m, apparent[0], apparent[1]);
        assertClose(back[0], 2.0, 1e-9, "map rm round-trip");
        assertClose(back[1], 0.3, 1e-9, "map dm round-trip");
    }

    @Test
    void besselNewcombPrecessionRoundTrip() {
        SlaMap m = sla("PREBN", 1950.0, 2000.0);
        double[] afterPrecession = fwd(m, 1.0, 0.5);
        double[] back = inv(m, afterPrecession[0], afterPrecession[1]);
        assertClose(back[0], 1.0, 1e-10, "prebn ra round-trip");
        assertClose(back[1], 0.5, 1e-10, "prebn dec round-trip");
    }

    /**
     * FK45Z then FK54Z is not an exact inverse: the E-terms and fictitious proper motion leave a
     * residual of about 6e-11, as in C.
     */
    @Test
    void fk4Fk5RoundTrip() {
        SlaMap m = sla("FK45Z", 1984.0);
        double[] fk5 = fwd(m, 1.0, 0.5);
        double[] back = inv(m, fk5[0], fk5[1]);
        assertClose(back[0], 1.0, 1e-9, "fk45z ra round-trip");
        assertClose(back[1], 0.5, 1e-9, "fk45z dec round-trip");
    }

    @Test
    void multiplePoints() {
        SlaMap m = sla("EQGAL");
        double[][] out = transformRows(
                m, true, new double[] {0.0, 0.0}, new double[] {1.0, 0.5}, new double[] {BAD, 0.3}, new double[] {
                    2.0, -0.4
                });
        assertEquals(4, out[0].length);
        assertTrue(Double.isFinite(out[0][0]));
        assertTrue(Double.isFinite(out[0][1]));
        assertTrue(Double.isFinite(out[0][3]));
        assertEquals(BAD, out[0][2]);
        assertEquals(BAD, out[1][2]);
    }

    /** {@link Mapping#simplify} returns a Mapping that transforms as the SlaMap does. */
    @Test
    void simplifiedReturnsClone() {
        SlaMap m = sla("EQGAL");
        Mapping s = m.simplify();
        assertEquals(2, s.getNin());
        assertEquals(2, s.getNout());
        assertArrayEquals(fwd(m, 1.0, 0.5), fwd(s, 1.0, 0.5));
    }

    // ------------------------------------------------------------------
    // The transformation as slamap.c Transform applies it
    // ------------------------------------------------------------------

    /**
     * R2H then H2R is the identity, and C carries the longitude through both steps without
     * normalising it, so a longitude far outside [0, 2 pi) survives.
     */
    @Test
    void r2hH2rPreservesRawLongitude() {
        SlaMap m = new SlaMap();
        m.add("R2H", new double[] {3.5});
        m.add("H2R", new double[] {3.5});
        double[] out = fwd(m, -1000.0, 0.3);
        assertClose(out[0], -1000.0, 1e-9, "raw longitude preserved");
        assertClose(out[1], 0.3, 1e-9, "latitude preserved");
    }

    /**
     * A matrix conversion gives a longitude in [0, 2 pi): C applies palDranrm after the matrix
     * product. PREC from J2000.0 to J2000.0 is not exactly the identity (the precession
     * polynomials are evaluated at t = 0), and C gives 5.2831853953259547 for this point.
     */
    @Test
    void matrixOpLongitudeIsNormalizedTo02pi() {
        double got = fwd(sla("PREC", 2000.0, 2000.0), -1.0, 0.2)[0];
        assertTrue(got >= 0.0 && got < 2.0 * Math.PI, "lon in [0,2pi): " + got);
        assertClose(got, 5.283185395325955, 1e-9, "PREC lon matches C golden");
    }

    /**
     * EQECL away from J2000.0, against a C probe of the SlaMap at MJD 55197.0 for (83, 22)
     * degrees: C's matrix is ecmat(date) . prec(2000, epj(date)).
     */
    @Test
    void eqeclMatchesCOffJ2000() {
        double[] out = fwd(sla("EQECL", 55197.0), deg2rad(83.0), deg2rad(22.0));
        assertAngleClose(out[0], 1.459968862726898, 1e-9, "ecl lon");
        assertClose(out[1], -0.022347584010998657, 1e-9, "ecl lat");
    }

    /** A conversion with a bad argument makes every point bad (slamap.c Transform). */
    @Test
    void badArgPropagatesToBadOutput() {
        double[][] out = transformRows(sla("EQECL", BAD), true, new double[] {0.5, 0.2}, new double[] {1.0, -0.3});
        for (int i = 0; i < 2; i++) {
            assertEquals(BAD, out[0][i], "point " + i + " lon bad");
            assertEquals(BAD, out[1][i], "point " + i + " lat bad");
        }
    }

    /**
     * AMP takes (DATE, EQ) and MAP (EQ, DATE), so palMappa is called with the same equinox and date
     * for both (slamap.c Transform), and AMP then MAP returns the input.
     */
    @Test
    void ampMapRoundTrip() {
        SlaMap m = new SlaMap();
        m.add("AMP", new double[] {55197.0, 2000.0});
        m.add("MAP", new double[] {2000.0, 55197.0});
        double ra = deg2rad(120.0);
        double dec = deg2rad(-15.0);
        double[] out = fwd(m, ra, dec);
        assertAngleClose(out[0], ra, 1e-9, "ra AMP-then-MAP round-trip");
        assertClose(out[1], dec, 1e-9, "dec AMP-then-MAP round-trip");
    }

    /** Either coordinate bad, or NaN, makes both outputs bad (slamap.c TRAN_ARRAY). */
    @Test
    void badAndNanInputProduceBadOutput() {
        double[][] out = transformRows(
                sla("PREC", 2000.0, 2010.0),
                true,
                new double[] {BAD, 0.2},
                new double[] {Double.NaN, 0.3},
                new double[] {0.5, BAD});
        for (int i = 0; i < 3; i++) {
            assertEquals(BAD, out[0][i], "point " + i + " lon bad");
            assertEquals(BAD, out[1][i], "point " + i + " lat bad");
        }
    }

    /**
     * FK5 to FK4 away from B1950, and FK4 to FK5, from C's {@code testslamap.c} with its wanted
     * values, which are ERFA's {@code eraFk54z} and {@code eraFk45z} (written here in their shortest
     * round-trip form).
     */
    @Test
    void fk4Fk5MatchErfa() {
        Object[][] cases = {
            {"FK54Z", 1980.0, 0.6, -1.55, 0.7496795275867678, -1.5538073132385521, 1.0e-12},
            {"FK54Z", 1950.0, 1.0, 0.5, 0.9866025011096419, 0.49734772100081265, 1.0e-14},
            {"FK45Z", 1980.0, 1.0, 0.5, 1.013430704045499, 0.5025971108191393, 1.0e-14},
            {"FK45Z", 1950.0, 1.0, 0.5, 1.0134306526650936, 0.5025974365562593, 1.0e-14}
        };
        for (Object[] c : cases) {
            String label = c[0] + " at B" + c[1];
            double[] out = fwd(sla((String) c[0], (Double) c[1]), (Double) c[2], (Double) c[3]);
            assertClose(out[0], (Double) c[4], (Double) c[6], label + " ra");
            assertClose(out[1], (Double) c[5], (Double) c[6], label + " dec");
        }
    }

    // ------------------------------------------------------------------
    // Java API
    // ------------------------------------------------------------------

    /** A copy keeps the conversions and, as C's Copy does, any palMappa parameters worked out. */
    @Test
    void aCopyTransformsAsTheOriginal() {
        SlaMap m = sla("MAP", 2000.0, 55197.0);
        double[] before = fwd(m, 1.0, 0.5);
        SlaMap c = (SlaMap) m.copy();
        assertArrayEquals(before, fwd(c, 1.0, 0.5));
        c.add("EQGAL", null);
        assertNotEquals(fwd(c, 1.0, 0.5)[0], fwd(m, 1.0, 0.5)[0]);
        assertArrayEquals(before, fwd(m, 1.0, 0.5));
    }

    /** The constructor's flags are reserved by C and have no effect. */
    @Test
    void theFlagsHaveNoEffect() {
        SlaMap m = new SlaMap(0);
        m.add("EQGAL", new double[0]);
        assertArrayEquals(fwd(sla("EQGAL"), 1.0, 0.5), fwd(m, 1.0, 0.5));
    }
}
