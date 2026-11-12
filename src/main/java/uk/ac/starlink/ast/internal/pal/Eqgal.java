package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.Anpm;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.Rxp;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * FK5 J2000 to galactic coordinates: {@code palEqgal}.
 *
 * <p>Ported from Rust {@code pal/eqgal.rs}, itself ported from PAL's {@code palEqgal} ({@code
 * pal/palEqgal.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Eqgal {

    private Eqgal() {}

    /** The equatorial-to-galactic rotation matrix (J2000.0), obtained by applying the standard FK4 to FK5 transformation, for zero proper motion in FK5, to the columns of the B1950 equatorial to galactic rotation matrix. */
    private static final double[][] RMAT = {
        {-0.054875539726, -0.873437108010, -0.483834985808},
        {0.494109453312, -0.444829589425, 0.746982251810},
        {-0.867666135858, -0.198076386122, 0.455983795705}
    };

    /**
     * FK5 J2000 (ra, dec) to galactic {@code {l, b}}, in radians, with l in 0 to 2 pi and b in -pi
     * to pi.
     */
    public static double[] eqgal(double dr, double dd) {
        double[] v1 = S2c.s2c(dr, dd);
        double[] v2 = Rxp.rxp(RMAT, v1);
        double[] ab = C2s.c2s(v2);
        return new double[] {Anp.anp(ab[0]), Anpm.anpm(ab[1])};
    }
}
