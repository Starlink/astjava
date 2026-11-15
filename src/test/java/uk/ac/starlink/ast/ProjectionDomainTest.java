package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Points outside a projection's domain give {@code AST__BAD}, without an exception, while a valid
 * neighbour in the same call gives finite values (Review Focus 2). For each projection type, a
 * sphere-to-plane transformation of a point outside the projection's domain and a plane-to-sphere
 * transformation of a plane point outside the projected region are made, each beside a valid
 * point, and each expected bad point is traced to the C code that makes it bad: the wcslib
 * projection function in {@code wcslib/proj.c} or {@code wcslib/tpn.c} returning status 2, which
 * {@code Map} in {@code src/wcsmap.c} turns into {@code AST__BAD} (wcsmap.c:2875-2877 forward,
 * 2949-2951 inverse), or {@code Map}'s own test of the native longitude and latitude the reverse
 * projection gives (wcsmap.c:2932-2941: a longitude outside [-180, 180), or [0, 360) for
 * projections giving longitudes beyond 180, when the projection is not cyclic, and a latitude
 * more than the projection's tolerance beyond 90 degrees).
 *
 * <p>Some projections have no bad point in one direction, since {@code Map} first brings a
 * forward input to a longitude in [-180, 180] and a latitude in [-90, 90] (wcsmap.c:2849-2857):
 * the forward projections of ARC, ZEA, CYP, CEA, CAR, SFL, GLS, PAR, MOL, AIT, COE, COD, BON,
 * PCO, TSC, CSC, QSC, HPX and XPH cover the whole sphere, and the reverse projections of AZP,
 * SZP, TAN, STG, AIR and TPN, with their default parameters, the whole plane. For those, the
 * points tested are a pole and a point far out in the plane, which must transform to finite
 * values.
 */
class ProjectionDomainTest {

    private static final double BAD = AstObject.AST__BAD;
    private static final double D2R = Math.PI / 180.0;

    /** A point that must be bad, and why. */
    private static final class Case {
        final int type;
        final String settings;
        final boolean forward;
        final double a;
        final double b;
        final String cite;

        /** A case whose point, in degrees, is ({@code a}, {@code b}). */
        Case(int type, String settings, boolean forward, double a, double b, String cite) {
            this.type = type;
            this.settings = settings;
            this.forward = forward;
            this.a = a;
            this.b = b;
            this.cite = cite;
        }
    }

    private static final Case[] BAD_CASES = {
        // Forward: native (longitude, latitude) in degrees.
        new Case(WcsMap.AST__AZP, "", true, 5.7, -10, "proj.c:548, astAZPfwd: divergence, theta < max(a, b) = 0"),
        new Case(WcsMap.AST__SZP, "", true, 5.7, -10, "proj.c:700, astSZPfwd: divergence, theta < w[8]"),
        new Case(WcsMap.AST__TAN, "", true, 5.7, -10, "proj.c:849, astTANfwd: sin(theta) < 0"),
        new Case(WcsMap.AST__STG, "", true, 5.7, -90, "proj.c:930, astSTGfwd: 1 + sin(theta) == 0"),
        new Case(WcsMap.AST__SIN, "", true, 5.7, -10, "proj.c:1039, astSINfwd: orthographic, theta < 0"),
        new Case(
                WcsMap.AST__ZPN,
                "PV2_1=1,PV2_3=-0.1",
                true,
                5.7,
                -45,
                "proj.c:1358, astZPNfwd: colatitude beyond the polynomial's turning point w[0]"),
        new Case(WcsMap.AST__AIR, "", true, 5.7, -90, "proj.c:1651, astAIRfwd: theta at the south pole"),
        new Case(WcsMap.AST__MER, "", true, 5.7, 90, "proj.c:2076, astMERfwd: theta >= 90"),
        new Case(WcsMap.AST__COP, "PV2_1=45", true, 5.7, -45, "proj.c:2584, astCOPfwd: cos(theta - PV2_1) == 0"),
        new Case(WcsMap.AST__COO, "PV2_1=45", true, 5.7, -90, "proj.c:2958, astCOOfwd: theta = -90 with w[0] >= 0"),
        new Case(WcsMap.AST__TPN, "", true, 5.7, -10, "tpn.c:90, astTPNfwd: sin(theta) < 0"),
        // Reverse: projection-plane (x, y) in degrees.
        new Case(WcsMap.AST__SIN, "", false, 60, 0, "proj.c:1083, astSINrev: orthographic, r^2 > 1"),
        new Case(WcsMap.AST__ARC, "", false, 181, 0, "wcsmap.c:2934, latitude -91 from astARCrev is beyond the pole"),
        new Case(
                WcsMap.AST__ZPN,
                "PV2_1=1,PV2_3=-0.1",
                false,
                90,
                0,
                "proj.c:1429, astZPNrev: r beyond the polynomial's largest value"),
        new Case(WcsMap.AST__ZEA, "", false, 150, 0, "proj.c:1556, astZEArev: r > 2 R"),
        new Case(WcsMap.AST__CYP, "", false, 181, 0, "wcsmap.c:2932, longitude 181 is not in [-180, 180)"),
        new Case(WcsMap.AST__CEA, "", false, 0, 60, "proj.c:1945, astCEArev: |y w[3]| > 1"),
        new Case(WcsMap.AST__CAR, "", false, 181, 0, "wcsmap.c:2932, longitude 181 is not in [-180, 180)"),
        new Case(WcsMap.AST__MER, "", false, 181, 0, "wcsmap.c:2932, longitude 181 is not in [-180, 180)"),
        new Case(WcsMap.AST__SFL, "", false, 0, 91, "wcsmap.c:2934, latitude 91 from astSFLrev is beyond the pole"),
        new Case(WcsMap.AST__GLS, "", false, 0, 91, "wcsmap.c:2934, latitude 91 from astSFLrev is beyond the pole"),
        new Case(WcsMap.AST__PAR, "", false, 0, 91, "wcsmap.c:2934, latitude 91.1 from astPARrev is beyond the pole"),
        new Case(WcsMap.AST__MOL, "", false, 0, 89, "proj.c:2374, astMOLrev: 2 - y0^2 < -tol"),
        new Case(WcsMap.AST__AIT, "", false, 179, 0, "proj.c:2483, astAITrev: u < 0.5 - tol"),
        new Case(
                WcsMap.AST__COP,
                "PV2_1=45",
                false,
                0,
                60,
                "wcsmap.c:2932, longitude 254.6 from astCOPrev is not in [-180, 180)"),
        new Case(WcsMap.AST__COE, "PV2_1=45", false, 0, 60, "proj.c:2760, astCOErev: w beyond -1 - tol"),
        new Case(
                WcsMap.AST__COD,
                "PV2_1=45",
                false,
                200,
                0,
                "wcsmap.c:2934, latitude -105.7 from astCODrev is beyond the pole"),
        new Case(
                WcsMap.AST__COO,
                "PV2_1=45",
                false,
                0,
                60,
                "wcsmap.c:2932, longitude 254.6 from astCOOrev is not in [-180, 180)"),
        new Case(
                WcsMap.AST__BON,
                "PV2_1=45",
                false,
                115,
                0,
                "wcsmap.c:2932, longitude 209.2 from astBONrev is not in [-180, 180)"),
        new Case(WcsMap.AST__PCO, "", false, 181, 0, "wcsmap.c:2932, longitude 181 is not in [-180, 180)"),
        new Case(WcsMap.AST__TSC, "", false, 400, 0, "proj.c:3421, astTSCrev: |xf| > 7"),
        new Case(WcsMap.AST__CSC, "", false, 400, 0, "proj.c:3701, astCSCrev: |xf| > 7"),
        new Case(WcsMap.AST__QSC, "", false, 400, 0, "proj.c:4029, astQSCrev: |xf| > 7"),
        new Case(WcsMap.AST__HPX, "", false, 0, 100, "proj.c:4370, astHPXrev: |y| beyond the polar zone"),
        new Case(WcsMap.AST__XPH, "", false, 0, 300, "proj.c:4586, astXPHrev: beyond the latitude range"),
    };

    /** Projections whose forward projection covers the sphere: a pole must give finite values. */
    private static final int[] WHOLE_SPHERE = {
        WcsMap.AST__ARC, WcsMap.AST__ZEA, WcsMap.AST__CYP, WcsMap.AST__CEA, WcsMap.AST__CAR, WcsMap.AST__SFL,
        WcsMap.AST__GLS, WcsMap.AST__PAR, WcsMap.AST__MOL, WcsMap.AST__AIT, WcsMap.AST__COE, WcsMap.AST__COD,
        WcsMap.AST__BON, WcsMap.AST__PCO, WcsMap.AST__TSC, WcsMap.AST__CSC, WcsMap.AST__QSC, WcsMap.AST__HPX,
        WcsMap.AST__XPH
    };

    /** Projections whose reverse projection covers the plane: a far point must give finite values. */
    private static final int[] WHOLE_PLANE = {
        WcsMap.AST__AZP, WcsMap.AST__SZP, WcsMap.AST__TAN, WcsMap.AST__STG, WcsMap.AST__AIR, WcsMap.AST__TPN
    };

    private static WcsMap map(int type, String settings) {
        WcsMap m = new WcsMap(2, type, 1, 2);
        if (!settings.isEmpty()) {
            m.set(settings);
        }
        if ((type == WcsMap.AST__COE || type == WcsMap.AST__COD || type == WcsMap.AST__BON) && settings.isEmpty()) {
            m.set("PV2_1=45");
        }
        return m;
    }

    /** Transforms two points given in degrees; neither may raise an exception. */
    private static double[][] tran(WcsMap m, boolean forward, double[] p1, double[] p2) {
        double[][] in = {{p1[0] * D2R, p2[0] * D2R}, {p1[1] * D2R, p2[1] * D2R}};
        return m.transform(in, 2, forward);
    }

    private static void assertGood(double[][] out, int i, String what) {
        for (int c = 0; c < 2; c++) {
            assertNotEquals(BAD, out[c][i], what);
            assertTrue(Double.isFinite(out[c][i]), what);
        }
    }

    @Test
    void pointsOutsideTheDomainAreBad() {
        for (Case c : BAD_CASES) {
            WcsMap m = map(c.type, c.settings);
            double[] neighbour = c.forward ? new double[] {5.7, 30.0} : new double[] {10.0, 10.0};
            String what = m.getC("WcsType") + (c.forward ? " forward " : " inverse ") + c.a + ", " + c.b + " (" + c.cite
                    + ")";
            double[][] out = tran(m, c.forward, new double[] {c.a, c.b}, neighbour);
            assertEquals(BAD, out[0][0], what);
            assertEquals(BAD, out[1][0], what);
            assertGood(out, 1, what + ": neighbour");
            // The same with the points the other way round, so the bad point is not the first.
            out = tran(m, c.forward, neighbour, new double[] {c.a, c.b});
            assertEquals(BAD, out[0][1], what);
            assertGood(out, 0, what + ": neighbour");
        }
    }

    @Test
    void wholeSphereProjectionsProjectThePoles() {
        for (int type : WHOLE_SPHERE) {
            WcsMap m = map(type, "");
            double[][] out = tran(m, true, new double[] {5.7, -90.0}, new double[] {5.7, 90.0});
            assertGood(out, 0, type + " south pole");
            assertGood(out, 1, type + " north pole");
        }
    }

    @Test
    void wholePlaneProjectionsReachFarPoints() {
        for (int type : WHOLE_PLANE) {
            WcsMap m = map(type, "");
            double[][] out = tran(m, false, new double[] {1000.0, 0.0}, new double[] {0.0, -1000.0});
            assertGood(out, 0, type + " far x");
            assertGood(out, 1, type + " far y");
        }
    }
}
