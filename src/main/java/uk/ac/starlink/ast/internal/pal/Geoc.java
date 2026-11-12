package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Eform;
import uk.ac.starlink.ast.internal.erfa.Gd2gc;

/**
 * Geodetic to geocentric coordinates: {@code palGeoc}.
 *
 * <p>Ported from Rust {@code pal/geoc.rs}, itself ported from PAL's {@code palGeoc} ({@code
 * pal/palGeoc.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Geoc {

    private Geoc() {}

    /**
     * Geodetic latitude {@code p} and height {@code h} (metres) to {@code {r, z}}, the distance
     * from the Earth's axis and from the equatorial plane, in au (WGS84 ellipsoid, zero longitude).
     * The geocentric latitude is {@code atan2(z, r)}.
     */
    public static double[] geoc(double p, double h) {
        // Use zero longitude.
        final double elong = 0.0;
        // This is not ERFA_DAU: PAL writes the au as 1.49597870E11 m.
        final double au = 1.4959787E11;
        // ERFA's status is ignored, as in PAL; on failure ERFA zeroes the vector.
        double[] xyz = Gd2gc.gd2gc(Eform.Ellipsoid.WGS84, elong, p, h);
        if (xyz == null) {
            xyz = new double[3];
        }
        // PAL divides by cos(elong) for a longitude that is the constant 0.
        return new double[] {xyz[0] / (au * Math.cos(elong)), xyz[2] / au};
    }
}
