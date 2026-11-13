package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.Pn;
import uk.ac.starlink.ast.internal.erfa.S2c;
import uk.ac.starlink.ast.internal.erfa.Trxp;

/**
 * Apparent to mean place, using precomputed parameters: {@code palAmpqk}.
 *
 * <p>Ported from Rust {@code pal/ampqk.rs}, itself ported from PAL's {@code palAmpqk} ({@code
 * pal/palAmpqk.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Ampqk {

    private Ampqk() {}

    /**
     * Geocentric apparent {@code (ra, da)} to mean place {@code {rm, dm}}, in radians with {@code
     * rm} in 0 to 2 pi, using the parameters from {@link Mappa#mappa}: the inverse of {@link
     * Mapqkz#mapqkz}, found by iterating the aberration twice and the light deflection five times.
     */
    public static double[] ampqk(double ra, double da, double[] amprms) {
        // Unpack some of the parameters.
        double gr2e = amprms[7];
        double ab1 = amprms[11];
        double[] ehn = {amprms[4], amprms[5], amprms[6]};
        double[] abv = {amprms[8], amprms[9], amprms[10]};

        // Apparent RA,Dec to Cartesian.
        double[] p3 = S2c.s2c(ra, da);

        // Precession and nutation.
        double[] p2 = Trxp.trxp(Mappa.npbMatrix(amprms), p3);

        // Aberration.
        double ab1p1 = ab1 + 1.0;
        double[] p1 = p2.clone();
        for (int iter = 0; iter < 2; iter++) {
            double p1dv = Pdp.pdp(p1, abv);
            double p1dvp1 = 1.0 + p1dv;
            double w = 1.0 + p1dv / ab1p1;
            for (int i = 0; i < 3; i++) {
                p1[i] = (p1dvp1 * p2[i] - w * abv[i]) / ab1;
            }
            p1 = Pn.pn(p1).unit;
        }

        // Light deflection.
        double[] p = p1.clone();
        for (int iter = 0; iter < 5; iter++) {
            double pde = Pdp.pdp(p, ehn);
            double pdep1 = 1.0 + pde;
            double w = pdep1 - gr2e * pde;
            for (int i = 0; i < 3; i++) {
                p[i] = (pdep1 * p1[i] - gr2e * ehn[i]) / w;
            }
            p = Pn.pn(p).unit;
        }

        // Mean RA,Dec.
        double[] m = C2s.c2s(p);
        return new double[] {Anp.anp(m[0]), m[1]};
    }
}
