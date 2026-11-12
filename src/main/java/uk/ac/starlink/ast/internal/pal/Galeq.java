package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.Anpm;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.S2c;
import uk.ac.starlink.ast.internal.erfa.Trxp;

/**
 * Galactic to FK5 J2000 coordinates: {@code palGaleq}.
 *
 * <p>Ported from Rust {@code pal/galeq.rs}, itself ported from PAL's {@code palGaleq} ({@code
 * pal/palGaleq.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Galeq {

    private Galeq() {}

    /** The equatorial-to-galactic rotation matrix (J2000.0), obtained by applying the standard FK4 to FK5 transformation, for zero proper motion in FK5, to the columns of the B1950 equatorial to galactic rotation matrix. */
    private static final double[][] RMAT = {
        {-0.054875539726, -0.873437108010, -0.483834985808},
        {0.494109453312, -0.444829589425, 0.746982251810},
        {-0.867666135858, -0.198076386122, 0.455983795705}
    };

    /**
     * Galactic (l, b) to FK5 J2000 {@code {ra, dec}}, in radians, with ra in 0 to 2 pi and dec in
     * -pi to pi.
     */
    public static double[] galeq(double dl, double db) {
        double[] v1 = S2c.s2c(dl, db);
        double[] v2 = Trxp.trxp(RMAT, v1);
        double[] ab = C2s.c2s(v2);
        return new double[] {Anp.anp(ab[0]), Anpm.anpm(ab[1])};
    }
}
