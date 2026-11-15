package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The celestial coordinates of the north pole of a projection's native spherical coordinates, by
 * equations 8 to 10 of FITS-WCS paper II (Calabretta and Greisen), with the special cases the
 * paper describes.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/sky_pole.rs} ({@code wcs_nat_pole} and {@code
 * nat_pole}); C warns as it goes, where Rust gathers the warnings first, with the same result.
 */
final class FitsNativePole {

    /** C's {@code AST__DR2D}: radians to degrees. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code AST__DPI}: pi. */
    private static final double AST__DPI = 3.141592653589793;

    /** C's {@code AST__DPIBY2}: pi/2. */
    private static final double AST__DPIBY2 = 1.5707963267948966;

    /** C's {@code TOL1} (fitschan.c:1587): 10 times the machine precision. */
    private static final double TOL1 = 10.0 * Math.ulp(1.0);

    /** C's {@code TOL2} (fitschan.c:1590). */
    private static final double TOL2 = 1.0e-10;

    /** C's {@code DBL_MIN}: the smallest positive normalised double. */
    private static final double DBL_MIN = Double.MIN_NORMAL;

    private FitsNativePole() {}

    /** The result of {@link #wcsNatPole}: Rust's {@code (f64, Option<(f64, f64)>)}. */
    static final class NativePole {
        /** The longitude of the celestial north pole in native coordinates (LONPOLE), in radians. */
        final double phip;

        /**
         * The celestial longitude and latitude of the native north pole, {@code {alphap,
         * deltap}} in radians, or null when they are undefined.
         */
        final double[] pole;

        NativePole(double phip, double[] pole) {
            this.phip = phip;
            this.pole = pole;
        }
    }

    /** Adds the warning {@code condition} with {@code text} to {@code warnings}, if any. */
    private static void addWarning(FitsWarnings warnings, String condition, String text) {
        if (warnings != null) {
            warnings.push(new FitsWarnings.Warning(condition, text).namedBy("astRead"));
        }
    }

    /**
     * C's {@code WcsNatPole} (fitschan.c:38906): the celestial coordinates of the native north
     * pole of {@code wcsmap}'s projection, given those of the fiducial point, {@code alpha0} and
     * {@code delta0}, the FITS LATPOLE, and the LONPOLE {@code phip}, each in radians and null
     * when the header gives none. A LONPOLE is chosen when none is given, so that celestial
     * latitude increases with native latitude at the fiducial point, and is returned.
     *
     * <p>Certain combinations of values leave the pole undefined; the returned pole is then null,
     * and no error is reported.
     *
     * <p>A default LONPOLE or LATPOLE adds a warning (condition nolonpole or nolatpole) to
     * {@code warnings}, which with Strict in force is an error naming the method {@code astRead}
     * whatever the caller, as C's {@code Warn} call names it; {@code warnings} is null where C
     * passes no FitsChan, and then nothing is warned of.
     */
    static NativePole wcsNatPole(
            WcsMap wcsmap, double alpha0, double delta0, Double latpole, Double phip, FitsWarnings warnings) {
        double[] nsc = FitsFiducial.getFiducialNsc(wcsmap);
        double phi0 = nsc[0];
        double theta0 = nsc[1];

        double lonpole;
        if (phip != null) {
            lonpole = phip;
        } else {
            lonpole = delta0 >= theta0 ? 0.0 : AST__DPI;
            addWarning(
                    warnings,
                    "nolonpole",
                    "The original FITS header did not specify the longitude of the native north pole. A default"
                            + " value of " + CFormat.format("%.8g", lonpole * AST__DR2D) + " degrees was assumed.");
        }

        // A fiducial point at the native north pole is the pole; one at the native south pole is
        // diametrically opposite it.
        double sinTheta0 = Math.sin(theta0);
        if (MappingHelpers.astEqual(sinTheta0, 1.0)) {
            return new NativePole(lonpole, new double[] {alpha0, delta0});
        } else if (MappingHelpers.astEqual(sinTheta0, -1.0)) {
            return new NativePole(lonpole, new double[] {alpha0 + AST__DPI, -delta0});
        }

        double cosTheta0 = Math.cos(theta0);
        double cosDelta0 = Math.cos(delta0);
        double cosPhip = Math.cos(lonpole - phi0);
        double sinDelta0 = Math.sin(delta0);
        double sinPhip = Math.sin(lonpole - phi0);

        // The latitude of the pole. Where it is indeterminate LATPOLE gives it (item 6 para. 2 of
        // the paper), and failing that there is none.
        double deltap;
        double t0 = cosTheta0 * cosPhip;
        if (Math.abs(t0) < TOL2 && Math.abs(sinTheta0) < TOL2) {
            if (latpole == null) {
                return new NativePole(lonpole, null);
            }
            deltap = latpole;
        } else {
            double t1 = Math.atan2(sinTheta0, t0);
            double t2 = cosTheta0 * cosPhip;
            t2 *= t2;
            t2 += sinTheta0 * sinTheta0;
            if (t2 <= DBL_MIN) {
                return new NativePole(lonpole, null);
            }
            double t3 = sinDelta0 / Math.sqrt(t2);
            if (Math.abs(t3) > 1.0 + TOL1) {
                return new NativePole(lonpole, null);
            }
            double t4;
            if (t3 < -1.0) {
                t4 = AST__DPI;
            } else if (t3 > 1.0) {
                t4 = 0.0;
            } else {
                t4 = Math.acos(t3);
            }
            double deltap1 = Pal.drange(t1 + t4);
            double deltap2 = Pal.drange(t1 - t4);

            // Of two solutions within +/- pi/2, use the one closer to LATPOLE, or with none the
            // northern one.
            if (Math.abs(deltap1) > AST__DPIBY2 + TOL2) {
                deltap = deltap2;
            } else if (Math.abs(deltap2) > AST__DPIBY2 + TOL2) {
                deltap = deltap1;
            } else if (latpole != null) {
                deltap = Math.abs(deltap1 - latpole) < Math.abs(deltap2 - latpole) ? deltap1 : deltap2;
            } else {
                deltap = deltap1 > deltap2 ? deltap1 : deltap2;
                addWarning(
                        warnings,
                        "nolatpole",
                        "The original FITS header did not specify the latitude of the native north pole. A"
                                + " default value of " + CFormat.format("%.8g", deltap * AST__DR2D)
                                + " degrees was assumed.");
            }
            if (Math.abs(deltap) > AST__DPIBY2 + TOL2) {
                return new NativePole(lonpole, null);
            } else if (deltap < -AST__DPIBY2) {
                deltap = -AST__DPIBY2;
            } else if (deltap > AST__DPIBY2) {
                deltap = AST__DPIBY2;
            }
        }

        // The longitude of the pole.
        double alphap;
        if (Math.abs(cosDelta0) > TOL2) {
            double cosDeltap = Math.cos(deltap);
            double sinDeltap = Math.sin(deltap);
            if (Math.abs(cosDeltap) > TOL2) {
                double t1 = sinPhip * cosTheta0 / cosDelta0;
                double t2 = (sinTheta0 - sinDeltap * sinDelta0) / (cosDelta0 * cosDeltap);
                if (Math.abs(t1) > TOL2 || Math.abs(t2) > TOL2) {
                    alphap = alpha0 - Math.atan2(t1, t2);
                } else {
                    alphap = alpha0;
                }
            } else if (sinDeltap > 0.0) {
                alphap = alpha0 + (lonpole - phi0) - AST__DPI;
            } else {
                alphap = alpha0 - (lonpole - phi0);
            }
        } else {
            alphap = alpha0;
        }
        return new NativePole(lonpole, new double[] {alphap, deltap});
    }
}
