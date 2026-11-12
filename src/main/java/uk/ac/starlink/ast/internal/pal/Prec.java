package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Erfa;
import uk.ac.starlink.ast.internal.erfa.Pmat06;
import uk.ac.starlink.ast.internal.erfa.Rxr;
import uk.ac.starlink.ast.internal.erfa.Tr;

/**
 * Julian (FK5) precession matrix, IAU 2006: {@code palPrec}.
 *
 * <p>Ported from Rust {@code pal/prec.rs}, itself ported from PAL's {@code palPrec} ({@code
 * pal/palPrec.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Prec {

    private Prec() {}

    /**
     * The Julian (FK5) precession matrix from Julian epoch {@code ep0} to Julian epoch {@code ep1},
     * from the IAU 2006 model.
     */
    public static double[][] prec(double ep0, double ep1) {
        // Convert supplied dates to days since J2000.
        double ep0Days = (ep0 - 2000.0) * Erfa.ERFA_DJY;
        double ep1Days = (ep1 - 2000.0) * Erfa.ERFA_DJY;

        if (ep0 == 2000.0) {
            // If the beginning epoch is J2000, just return the rotation matrix from J2000 to EP1.
            return Pmat06.pmat06(Erfa.ERFA_DJ00, ep1Days);
        } else if (ep1 == 2000.0) {
            // If the end epoch is J2000, get the rotation matrix from J2000 to EP0 and then
            // transpose it to get the rotation matrix from EP0 to J2000.
            return Tr.tr(Pmat06.pmat06(Erfa.ERFA_DJ00, ep0Days));
        } else {
            // Otherwise, get the two matrices used above and multiply them together.
            double[][] rmatp = Tr.tr(Pmat06.pmat06(Erfa.ERFA_DJ00, ep0Days));
            double[][] rmatq = Pmat06.pmat06(Erfa.ERFA_DJ00, ep1Days);
            return Rxr.rxr(rmatp, rmatq);
        }
    }
}
