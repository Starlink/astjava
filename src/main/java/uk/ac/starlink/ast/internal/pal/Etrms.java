package uk.ac.starlink.ast.internal.pal;

/**
 * The E-terms of aberration: {@code palEtrms}.
 *
 * <p>Ported from Rust {@code pal/etrms.rs}, itself ported from PAL's {@code palEtrms} ({@code
 * pal/palEtrms.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Etrms {

    private Etrms() {}

    /**
     * The E-terms of aberration at Besselian-style epoch {@code ep} (a Julian year number such as
     * 1950.0), as a vector in the mean equator and equinox of that epoch.
     */
    public static double[] etrms(double ep) {
        // The J2000 aberration constant.
        final double abconst = 20.49552;

        // Julian centuries since B1950.
        double t = (ep - 1950.0) * 0.0100002135903;

        // Eccentricity.
        double e = 0.01673011 - (t * 1.26e-7 + 4.193e-5) * t;

        // Mean obliquity.
        double e0 = (84404.836 - ((t * 0.00181 + 0.00319) * t + 46.8495) * t) * Pal.PAL__DAS2R;

        // Mean longitude of perihelion.
        double p = (((t * 0.012 + 1.65) * t + 6190.67) * t + 1015489.951) * Pal.PAL__DAS2R;

        // E-terms. PAL calls sin and cos of each of p and e0.
        double ek = e * abconst * Pal.PAL__DAS2R;
        double sp = Math.sin(p);
        double cp = Math.cos(p);
        double se0 = Math.sin(e0);
        double ce0 = Math.cos(e0);
        return new double[] {ek * sp, -ek * cp * ce0, -ek * cp * se0};
    }
}
