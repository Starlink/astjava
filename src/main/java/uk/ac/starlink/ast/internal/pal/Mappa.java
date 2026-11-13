package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Epj;
import uk.ac.starlink.ast.internal.erfa.Pn;

/**
 * Mean-to-apparent parameters: {@code palMappa}.
 *
 * <p>Ported from Rust {@code pal/mappa.rs}, itself ported from PAL's {@code palMappa} ({@code
 * pal/palMappa.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Mappa {

    private Mappa() {}

    /** The gravitational radius of the Sun times 2 (2 mu / c^2, au). */
    private static final double GR2 = 2.0 * 9.87063e-9;

    /**
     * The star-independent parameters for mean-to-apparent place transformations from the mean
     * equator and equinox of Julian epoch {@code eq} to geocentric apparent place at the TDB date
     * {@code date} (a Modified Julian Date):
     *
     * <ul>
     *   <li>0: the time interval for proper motion (Julian years);
     *   <li>1 to 3: the barycentric position of the Earth (au);
     *   <li>4 to 6: the heliocentric direction of the Earth (unit vector);
     *   <li>7: the light deflection parameter, twice the Sun's gravitational radius over the
     *       Earth-Sun distance;
     *   <li>8 to 10: the barycentric Earth velocity in units of c;
     *   <li>11: sqrt(1 - v^2), v being the magnitude of that velocity;
     *   <li>12 to 20: the precession and nutation matrix, row by row.
     * </ul>
     */
    public static double[] mappa(double eq, double date) {
        double[] amprms = new double[21];

        // Time interval for proper motion correction.
        amprms[0] = Epj.epj(Pal.PAL__MJD0, date) - eq;

        // Get Earth barycentric and heliocentric position and velocity.
        double[][] evp = Evp.evp(date, eq);
        double[] ebd = evp[0];
        double[] dpb = evp[1];
        double[] eh = evp[3];
        System.arraycopy(dpb, 0, amprms, 1, 3);

        // Heliocentric direction of Earth (normalised) and modulus.
        Pn.Result ehn = Pn.pn(eh);
        System.arraycopy(ehn.unit, 0, amprms, 4, 3);

        // Light deflection parameter.
        amprms[7] = GR2 / ehn.modulus;

        // Aberration parameters.
        for (int i = 0; i < 3; i++) {
            amprms[i + 8] = ebd[i] * Pal.PAL__CR;
        }
        double vm = Pn.pn(new double[] {amprms[8], amprms[9], amprms[10]}).modulus;
        amprms[11] = Math.sqrt(1.0 - vm * vm);

        // NPB matrix.
        double[][] npb = Prenut.prenut(eq, date);
        for (int i = 0; i < 3; i++) {
            System.arraycopy(npb[i], 0, amprms, 12 + 3 * i, 3);
        }
        return amprms;
    }

    /** The precession and nutation matrix held in {@code amprms[12..20]}. */
    static double[][] npbMatrix(double[] amprms) {
        return new double[][] {
            {amprms[12], amprms[13], amprms[14]},
            {amprms[15], amprms[16], amprms[17]},
            {amprms[18], amprms[19], amprms[20]}
        };
    }
}
