package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.Rxp;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * Mean to apparent place, zero proper motion, precomputed parameters: {@code palMapqkz}.
 *
 * <p>Ported from Rust {@code pal/mapqkz.rs}, itself ported from PAL's {@code palMapqkz} ({@code
 * pal/palMapqkz.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Mapqkz {

    private Mapqkz() {}

    /**
     * Mean place {@code (rm, dm)} to geocentric apparent {@code {ra, da}}, in radians with {@code
     * ra} in 0 to 2 pi, for a star with no proper motion, parallax or radial velocity, using the
     * parameters from {@link Mappa#mappa}. Light deflection is restrained within the Sun's disc.
     */
    public static double[] mapqkz(double rm, double dm, double[] amprms) {
        // Unpack scalar and vector parameters.
        double ab1 = amprms[11];
        double gr2e = amprms[7];
        double[] abv = {amprms[8], amprms[9], amprms[10]};
        double[] ehn = {amprms[4], amprms[5], amprms[6]};

        // Spherical to x,y,z.
        double[] p = S2c.s2c(rm, dm);

        // Light deflection (restrained within the Sun's disc).
        double pde = Pdp.pdp(p, ehn);
        double pdep1 = pde + 1.0;
        double w = gr2e / (pdep1 > 1.0e-5 ? pdep1 : 1.0e-5);
        double[] p1 = new double[3];
        for (int i = 0; i < 3; i++) {
            p1[i] = p[i] + w * (ehn[i] - pde * p[i]);
        }

        // Aberration.
        double p1dv = Pdp.pdp(p1, abv);
        w = 1.0 + p1dv / (ab1 + 1.0);
        double[] p2 = new double[3];
        for (int i = 0; i < 3; i++) {
            p2[i] = (ab1 * p1[i]) + (w * abv[i]);
        }

        // Precession and nutation.
        double[] p3 = Rxp.rxp(Mappa.npbMatrix(amprms), p2);

        // Geocentric apparent RA,dec.
        double[] a = C2s.c2s(p3);
        return new double[] {Anp.anp(a[0]), a[1]};
    }
}
