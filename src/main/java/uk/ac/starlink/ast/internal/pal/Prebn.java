package uk.ac.starlink.ast.internal.pal;

/**
 * Besselian (FK4) precession matrix, Andoyer-Newcomb: {@code palPrebn}.
 *
 * <p>Ported from Rust {@code pal/prebn.rs}, itself ported from PAL's {@code palPrebn} ({@code
 * pal/palPrebn.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Prebn {

    private Prebn() {}

    /**
     * The Besselian (FK4) precession matrix from Besselian epoch {@code bep0} to Besselian epoch
     * {@code bep1}, from the Andoyer-Newcomb model.
     */
    public static double[][] prebn(double bep0, double bep1) {
        // Interval between basic epoch B1850.0 and beginning epoch in TC.
        double bigt = (bep0 - 1850.0) / 100.0;

        // Interval over which precession required, in tropical centuries.
        double t = (bep1 - bep0) / 100.0;

        // Euler angles.
        double tas2r = t * Pal.PAL__DAS2R;
        double w = 2303.5548 + (1.39720 + 0.000059 * bigt) * bigt;

        double zeta = (w + (0.30242 - 0.000269 * bigt + 0.017996 * t) * t) * tas2r;
        double z = (w + (1.09478 + 0.000387 * bigt + 0.018324 * t) * t) * tas2r;
        double theta =
                (2005.1125 + (-0.85294 - 0.000365 * bigt) * bigt + (-0.42647 - 0.000365 * bigt - 0.041802 * t) * t)
                        * tas2r;

        // Rotation matrix.
        return Deuler.deuler("ZYZ", -zeta, theta, -z);
    }
}
