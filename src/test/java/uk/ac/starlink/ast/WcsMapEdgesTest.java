package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * The tests of Rust {@code tests/test_wcsmap_edges.rs}: WcsMap projections at face centres, poles,
 * longitude edges, non-default projection parameters and native coordinates a little past a
 * limit, the points where the wcslib code AST carries has differed from upstream.
 *
 * <p>C ancestor: {@code reference/starlink-ast/ast_tester/testwcsmap.c}. Every case is that file's,
 * with its wanted values and tolerances, which are upstream wcslib 8.10's output for the same
 * projection and parameters unless a comment says otherwise. As there, cases are written in
 * degrees and a WcsMap works in radians on both sides. A failing case fails the test with its
 * label, where the C records the failure and carries on to the next case.
 */
class WcsMapEdgesTest {

    private static final double BAD = AstObject.AST__BAD;

    /** C's {@code AST__DD2R}: degrees to radians. */
    private static final double DD2R = Math.PI / 180.0;

    /** C's {@code AST__DR2D}: radians to degrees. */
    private static final double DR2D = 180.0 / Math.PI;

    /** A WcsMap of the projection with longitude axis 1 and latitude axis 2, and the settings {@code pv}. */
    private static WcsMap wcsmap(int type, String pv) {
        WcsMap map = new WcsMap(2, type, 1, 2);
        if (!pv.isEmpty()) {
            map.set(pv);
        }
        return map;
    }

    /** Transforms one point, forward when {@code forward} is true. */
    private static double[] tran(WcsMap map, boolean forward, double a, double b) {
        double[][] out = map.tran2(1, new double[] {a}, new double[] {b}, forward);
        return new double[] {out[0][0], out[1][0]};
    }

    /**
     * Compares one output (radians, or {@code AST__BAD}) with a wanted value (degrees, or {@code
     * AST__BAD} to require a bad output). A zero tolerance asks for the converted value to equal the
     * wanted one.
     */
    private static void checkValue(String label, String which, double got, double want, double tol) {
        if (want == BAD) {
            if (got != BAD) {
                fail(label + ": " + which + " is " + got * DR2D + ", wanted bad");
            }
            return;
        }
        if (got == BAD) {
            fail(label + ": " + which + " is bad, wanted " + want);
        }
        double deg = got * DR2D;
        boolean bad = tol == 0.0 ? deg != want : Math.abs(deg - want) > tol;
        if (bad) {
            fail(label + ": " + which + " is " + deg + ", wanted " + want + " (tolerance " + tol + ")");
        }
    }

    /** Native spherical (phi, theta) to projection plane (x, y), with separate tolerances on x and y. */
    private static void checkFwdXy(
            String label,
            int type,
            String pv,
            double phi,
            double theta,
            double xWant,
            double xTol,
            double yWant,
            double yTol) {
        double[] xy = tran(wcsmap(type, pv), true, phi * DD2R, theta * DD2R);
        checkValue(label, "x", xy[0], xWant, xTol);
        checkValue(label, "y", xy[1], yWant, yTol);
    }

    /** Native spherical (phi, theta) to projection plane (x, y). */
    private static void checkFwd(
            String label, int type, String pv, double phi, double theta, double xWant, double yWant, double tol) {
        checkFwdXy(label, type, pv, phi, theta, xWant, tol, yWant, tol);
    }

    /** Projection plane (x, y) to native spherical (phi, theta). */
    private static void checkRev(
            String label, int type, String pv, double x, double y, double phiWant, double thetaWant, double tol) {
        double[] pt = tran(wcsmap(type, pv), false, x * DD2R, y * DD2R);
        checkValue(label, "phi", pt[0], phiWant, tol);
        checkValue(label, "theta", pt[1], thetaWant, tol);
    }

    /** Forward then reverse, passing the forward's radians straight back, and checks the reverse's (phi, theta). */
    private static void checkRoundTrip(
            String label, int type, String pv, double phi, double theta, double phiWant, double thetaWant, double tol) {
        WcsMap map = wcsmap(type, pv);
        double[] xy = tran(map, true, phi * DD2R, theta * DD2R);
        assertTrue(xy[0] != BAD && xy[1] != BAD, label + ": the forward projection was bad");
        double[] pt = tran(map, false, xy[0], xy[1]);
        checkValue(label, "phi", pt[0], phiWant, tol);
        checkValue(label, "theta", pt[1], thetaWant, tol);
    }

    /**
     * Forward then reverse at a pole. The longitude of a pole is arbitrary, so only theta is
     * compared; phi must not be bad.
     */
    private static void checkPoleRoundTrip(String label, int type, String pv, double phi, double theta, double tol) {
        WcsMap map = wcsmap(type, pv);
        double[] xy = tran(map, true, phi * DD2R, theta * DD2R);
        assertTrue(xy[0] != BAD && xy[1] != BAD, label + ": the forward projection was bad");
        double[] pt = tran(map, false, xy[0], xy[1]);
        assertNotEquals(BAD, pt[0], label + ": phi at the pole is bad");
        checkValue(label, "theta", pt[1], theta, tol);
    }

    /**
     * QSC, face 4. Within about 0.008 degrees of the face centre the forward projection takes a
     * small-angle branch, which squared the longitude offset where it should have taken it once.
     */
    @Test
    void qscNearFace4Centre() {
        int qsc = WcsMap.AST__QSC;
        checkFwd("qsc face 4 centre", qsc, "", -90.005, 0.0, 269.9948691392351, 0.0, 1.0e-10);
        checkRev("qsc face 4 centre reverse", qsc, "", 269.9948691392351, 0.0, -90.005, 0.0, 1.0e-9);
        checkFwd("qsc face 4 near", qsc, "", -89.995, 0.004, 270.00573448754045, 0.004757997190007863, 1.0e-10);
        checkRev("qsc face 4 near reverse", qsc, "", 270.00573448754045, 0.004757997190007863, -89.995, 0.004, 1.0e-9);
    }

    /** HPX polar zone with an odd H, the polar zone's extent for H other than 4, and a point on a facet edge. */
    @Test
    void hpxPolarZoneAndFacetEdges() {
        int hpx = WcsMap.AST__HPX;
        String h3k3 = "PV2_1=3,PV2_2=3";
        String h4k4 = "PV2_1=4,PV2_2=4";
        // The facet centre was computed with floor(... + 1/2), an integer division that added
        // nothing.
        checkFwd("hpx H=3 K=3 south pole", hpx, h3k3, -180.0, -90.0, -120.0, -120.0, 1.0e-10);
        checkFwd("hpx H=4 K=4 south pole", hpx, h4k4, -131.9, -90.0, -90.0, -112.5, 1.0e-10);
        // The polar zone extends to (K+1)/2 facets of 180/H degrees, not to a fixed 90 degrees,
        // which is only right for H = 4.
        checkRev("hpx H=3 K=3 polar zone", hpx, h3k3, -0.4, -119.5, -48.00000000000017, -89.61015084735652, 1.0e-9);
        // A point on a facet edge, where (x - x_c)/sigma reaches 180/H only up to rounding.
        checkFwd("hpx facet edge", hpx, "", 90.0, -89.26, 134.28818979161747, -89.28818979161747, 1.0e-10);
        checkRev("hpx facet edge reverse", hpx, "", 134.28818979161747, -89.28818979161747, 90.0, -89.26, 1.0e-9);
    }

    /**
     * HPX at the pole: the forward projection puts it at the apex of the facet, and the reverse must
     * accept it rather than reject sigma = 0.
     */
    @Test
    void hpxPoleAtFacetApex() {
        int hpx = WcsMap.AST__HPX;
        double[][] cases = {{-180.0, -135.0}, {-45.0, -45.0}, {0.0, 45.0}, {90.0, 135.0}};
        for (double[] c : cases) {
            double phi = c[0];
            double x = c[1];
            String label = "hpx pole phi=" + phi;
            checkFwd(label, hpx, "", phi, -90.0, x, -90.0, 1.0e-10);
            checkRev(label + " reverse", hpx, "", x, -90.0, x, -90.0, 1.0e-10);
        }
    }

    /**
     * HPX pole through a WcsMap, which converts x and y to radians and back: for these H and K that
     * leaves y a few ULP from the pole's, so sigma is about 1e-16 rather than 0, or y is just beyond
     * the polar zone.
     */
    @Test
    void hpxPoleRoundTripNonDefaultHK() {
        int hpx = WcsMap.AST__HPX;
        checkPoleRoundTrip("hpx H=3 K=3 north pole", hpx, "PV2_1=3,PV2_2=3", 60.0, 90.0, 1.0e-10);
        checkPoleRoundTrip("hpx H=3 K=1 south pole", hpx, "PV2_1=3,PV2_2=1", -120.0, -90.0, 1.0e-10);
        checkPoleRoundTrip("hpx H=3 K=6 north pole", hpx, "PV2_1=3,PV2_2=6", 0.0, 90.0, 1.0e-10);
        checkPoleRoundTrip("hpx H=6 K=1 north pole", hpx, "PV2_1=6,PV2_2=1", 30.0, 90.0, 1.0e-10);
    }

    /**
     * HPX polar zone at native longitude +180: the facet arithmetic puts it beyond x = 180, and
     * wcslib folds it back to 360 - x so that it lands on the facet it belongs to.
     */
    @Test
    void hpxPhiPlus180() {
        int hpx = WcsMap.AST__HPX;
        checkFwd("hpx phi=+180 north pole", hpx, "", 180.0, 90.0, 135.0, 90.0, 1.0e-10);
        checkFwd("hpx phi=+180 north", hpx, "", 180.0, 60.0, 163.52885682970026, 61.471143170299726, 1.0e-10);
        checkFwd("hpx phi=+180 south", hpx, "", 180.0, -60.0, 163.52885682970026, -61.471143170299726, 1.0e-10);
        checkFwd("hpx phi=+180 south pole", hpx, "", 180.0, -90.0, 135.0, -90.0, 1.0e-10);
        checkFwd(
                "hpx H=3 K=3 phi=+180",
                hpx,
                "PV2_1=3,PV2_2=3",
                180.0,
                60.0,
                158.03847577293368,
                81.9615242270663,
                1.0e-10);
        checkFwd("hpx H=4 K=4 phi=+180", hpx, "PV2_1=4,PV2_2=4", 180.0, -60.0, 180.0, -79.55771365940052, 1.0e-10);
    }

    /**
     * ZPN with a degree-2 polynomial: beyond the turning point of r(theta) the projection folds back
     * on itself, and the point is on the far side. wcslib reports it as bad at every degree.
     */
    @Test
    void zpnDegree2FarSide() {
        checkFwd(
                "zpn degree 2 far side",
                WcsMap.AST__ZPN,
                "PV2_1=1.27118,PV2_2=-0.210643",
                5.4085552,
                -89.2063216,
                BAD,
                BAD,
                0.0);
    }

    /**
     * PCO within 1e-4 degrees of the equator: cot(theta) is huge and 1 - cos(phi*sin(theta))
     * cancels, so y must come from a series instead.
     */
    @Test
    void pcoNearEquator() {
        int pco = WcsMap.AST__PCO;
        checkFwd("pco near equator", pco, "", 90.0, 1.0e-9, 90.0, 2.23370055013617e-09, 1.0e-15);
        checkFwd(
                "pco near equator, far from the meridian",
                pco,
                "",
                -170.0,
                -5.0e-5,
                -169.99999999993526,
                -0.0002700860857957293,
                1.0e-12);
    }

    /**
     * COP at the poles. The pole on the same side of the equator as the standard parallels is the
     * apex of the cone, r = 0, and is returned exactly; the other pole is on the far side of the
     * projection and is bad.
     */
    @Test
    void copAtThePoles() {
        int cop = WcsMap.AST__COP;
        String north = "PV2_1=60,PV2_2=0";
        String south = "PV2_1=-45,PV2_2=5";
        checkFwd("cop opposite pole, north parallels", cop, north, -180.0, -90.0, BAD, BAD, 0.0);
        checkFwd("cop opposite pole, south parallels", cop, south, 0.0, 90.0, BAD, BAD, 0.0);
        // At the apex r is exactly zero, so x is exactly zero on any platform; y = Y0 depends on
        // libm and is given a margin of a few hundred ULP.
        checkFwdXy("cop apex, north parallels", cop, north, 37.0, 90.0, 0.0, 0.0, 33.079733725307534, 1.0e-12);
        checkRev("cop apex, north parallels, reverse", cop, north, 0.0, 33.079733725307534, 0.0, 90.0, 1.0e-12);
        checkFwdXy("cop apex, south parallels", cop, south, 30.0, -90.0, 0.0, 0.0, -57.07775177396628, 1.0e-12);
    }

    /**
     * MER close to the pole, where y = ln(tan(45 + theta/2)) magnifies any error in theta: wcslib's
     * D2R must be (PI/180.0), so that theta*D2R is theta*(pi/180) rather than (theta*pi)/180.
     * Without the parentheses y is 1.5e-6 larger; the tolerance allows for libm differences between
     * platforms.
     */
    @Test
    void merNearPole() {
        checkFwd("mer near pole", WcsMap.AST__MER, "", 0.0, 89.999999, 0.0, 1063.2311737965763, 1.0e-9);
    }

    /**
     * Native coordinates a little past a limit. wcslib sets a longitude or latitude within a
     * per-projection tolerance of a limit to the limit, and rejects one beyond it; the tolerance is
     * 1e-13 degrees for ARC, 1e-12 for PAR and 1e-11 for MOL. Wanted values are wcslib's prjx2s
     * results with bounds checking on.
     */
    @Test
    void nativeCoordinatesPastALimit() {
        int arc = WcsMap.AST__ARC;
        int par = WcsMap.AST__PAR;
        // ARC beyond the antipode: r = 180 + 6e-14 gives a latitude 4 ULP below -90, which is the
        // pole; r = 180 + 2e-13 is beyond the tolerance.
        checkRev("arc 4 ulp past the antipode", arc, "", -180.00000000000006, 0.0, -90.0, -90.0, 0.0);
        checkRev("arc 2e-13 past the antipode", arc, "", -180.0000000000002, 0.0, BAD, BAD, 0.0);
        // PAR and MOL at phi = -180: the reverse of the forward lands about 9e-14 degrees below
        // -180, three ULP, through rounding alone.
        checkRoundTrip("par round trip at -180", par, "", -180.0, -58.68, -180.0, -58.68, 1.0e-9);
        checkRoundTrip("mol round trip at -180", WcsMap.AST__MOL, "", -180.0, -59.76, -180.0, -59.76, 1.0e-9);
        checkRev("par 5.5e-12 below -180", par, "", -99.29743646562997, -60.26288541919301, BAD, BAD, 0.0);
    }
}
