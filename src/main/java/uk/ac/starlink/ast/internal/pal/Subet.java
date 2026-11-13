package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * Removes the E-terms of aberration: {@code palSubet}.
 *
 * <p>Ported from Rust {@code pal/subet.rs}, itself ported from PAL's {@code palSubet} ({@code
 * pal/palSubet.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Subet {

    private Subet() {}

    /**
     * Removes the E-terms of aberration for equinox {@code eq} from the apparent place {@code (rc,
     * dc)}, giving the mean {@code {rm, dm}}, in radians with {@code rm} in 0 to 2 pi.
     */
    public static double[] subet(double rc, double dc, double eq) {
        double[] a = Etrms.etrms(eq);
        double[] v = S2c.s2c(rc, dc);
        double f = 1.0 + Pdp.pdp(v, a);
        for (int i = 0; i < 3; i++) {
            v[i] = f * v[i] - a[i];
        }
        double[] m = C2s.c2s(v);
        return new double[] {Anp.anp(m[0]), m[1]};
    }
}
