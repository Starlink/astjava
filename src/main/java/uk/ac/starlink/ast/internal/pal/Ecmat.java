package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Obl06;

/**
 * Equatorial to ecliptic rotation matrix, IAU 2006: {@code palEcmat}.
 *
 * <p>Ported from Rust {@code pal/ecmat.rs}, itself ported from PAL's {@code palEcmat} ({@code
 * pal/palEcmat.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Ecmat {

    private Ecmat() {}

    /**
     * The rotation matrix from mean equatorial to mean ecliptic coordinates of the TDB date {@code
     * date} (a Modified Julian Date), IAU 2006 model.
     */
    public static double[][] ecmat(double date) {
        // Mean obliquity (the angle between the ecliptic and mean equator of date).
        double eps0 = Obl06.obl06(Pal.PAL__MJD0, date);

        // Matrix.
        return Deuler.deuler("X", eps0, 0.0, 0.0);
    }
}
