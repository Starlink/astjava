package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Eform;
import uk.ac.starlink.ast.internal.erfa.Erfa;
import uk.ac.starlink.ast.internal.erfa.Gd2gc;

/**
 * Position and velocity of an observing station: {@code palPvobs}.
 *
 * <p>Ported from Rust {@code pal/pvobs.rs}, itself ported from PAL's {@code palPvobs} ({@code
 * pal/palPvobs.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Pvobs {

    private Pvobs() {}

    /**
     * Position and velocity of an observing station, {@code {x, y, z, xdot, ydot, zdot}} in au and
     * au/s, in the true equator and equinox of date (WGS84 ellipsoid), from the geodetic latitude
     * {@code p}, the height {@code h} in metres and the local apparent sidereal time {@code stl}.
     */
    public static double[] pvobs(double p, double h, double stl) {
        // ERFA's status is ignored, as in PAL; on failure ERFA zeroes the vector.
        double[] xyz = Gd2gc.gd2gc(Eform.Ellipsoid.WGS84, 0.0, p, h);
        if (xyz == null) {
            xyz = new double[3];
        }
        double r = xyz[0] / Erfa.ERFA_DAU;
        double z = xyz[2] / Erfa.ERFA_DAU;
        // PAL calls sin and cos of one argument.
        double s = Math.sin(stl);
        double c = Math.cos(stl);
        double v = Pal.PAL__SR * r;
        return new double[] {r * c, r * s, z, -v * s, v * c, 0.0};
    }
}
