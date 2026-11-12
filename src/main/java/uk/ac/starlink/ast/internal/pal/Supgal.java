package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.Anpm;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.S2c;
import uk.ac.starlink.ast.internal.erfa.Trxp;

/**
 * Supergalactic to galactic coordinates: {@code palSupgal}.
 *
 * <p>Ported from Rust {@code pal/supgal.rs}, itself ported from PAL's {@code palSupgal} ({@code
 * pal/palSupgal.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Supgal {

    private Supgal() {}

    /** The galactic to supergalactic rotation matrix. */
    private static final double[][] RMAT = {
        {-0.735742574804, 0.677261296414, 0.000000000000},
        {-0.074553778365, -0.080991471307, 0.993922590400},
        {0.673145302109, 0.731271165817, 0.110081262225}
    };

    /** Supergalactic (sl, sb) to galactic {@code {l, b}}, in radians. */
    public static double[] supgal(double dsl, double dsb) {
        double[] v1 = S2c.s2c(dsl, dsb);
        double[] v2 = Trxp.trxp(RMAT, v1);
        double[] ab = C2s.c2s(v2);
        return new double[] {Anp.anp(ab[0]), Anpm.anpm(ab[1])};
    }
}
