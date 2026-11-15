package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Epj2jd;
import uk.ac.starlink.ast.internal.erfa.P06e;
import uk.ac.starlink.ast.internal.erfa.Pnm06a;
import uk.ac.starlink.ast.internal.erfa.Rx;
import uk.ac.starlink.ast.internal.erfa.Rxr;
import uk.ac.starlink.ast.internal.erfa.Rz;

/**
 * Precession-nutation matrix, IAU 2006/2000A: {@code palPrenut}.
 *
 * <p>Ported from Rust {@code pal/prenut.rs}, itself ported from PAL's {@code palPrenut} ({@code
 * pal/palPrenut.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Prenut {

    private Prenut() {}

    /**
     * The combined precession and nutation matrix from the mean equator and equinox of Julian epoch
     * {@code epoch} to the true equator and equinox of the TT date {@code date} (a Modified Julian
     * Date), IAU 2006 precession and IAU 2000A nutation.
     */
    public static double[][] prenut(double epoch, double date) {
        // Specified Julian epoch as a 2-part JD.
        double[] d = Epj2jd.epj2jd(epoch);

        // P matrix, from specified epoch to J2000.0.
        double[] p = P06e.p06e(d[0], d[1]);
        double eps0 = p[0];
        double psia = p[1];
        double oma = p[2];
        double chia = p[8];
        double[][] r1 = {{1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0}};
        r1 = Rz.rz(-chia, r1);
        r1 = Rx.rx(oma, r1);
        r1 = Rz.rz(psia, r1);
        r1 = Rx.rx(-eps0, r1);

        // NPB matrix, from J2000.0 to date.
        double[][] r2 = Pnm06a.pnm06a(Pal.PAL__MJD0, date);

        // NPB matrix, from specified epoch to date.
        return Rxr.rxr(r2, r1);
    }
}
