package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * Adds the E-terms of aberration: {@code palAddet}.
 *
 * <p>Ported from Rust {@code pal/addet.rs}, itself ported from PAL's {@code palAddet} ({@code
 * pal/palAddet.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Addet {

    private Addet() {}

    /**
     * Adds the E-terms of aberration for equinox {@code eq} to the mean place {@code (rm, dm)},
     * giving the apparent {@code {rc, dc}}, in radians with {@code rc} in 0 to 2 pi.
     */
    public static double[] addet(double rm, double dm, double eq) {
        double[] a = Etrms.etrms(eq);
        double[] v = S2c.s2c(rm, dm);
        for (int i = 0; i < 3; i++) {
            v[i] += a[i];
        }
        double[] c = C2s.c2s(v);
        return new double[] {Anp.anp(c[0]), c[1]};
    }
}
