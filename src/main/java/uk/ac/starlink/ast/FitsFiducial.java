package uk.ac.starlink.ast;

/**
 * The fiducial point of a WcsMap projection in native spherical coordinates, as a FITS-WCS read
 * needs it.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/fiducial.rs} ({@code get_fiducial_nsc}).
 */
final class FitsFiducial {

    /** C's {@code AST__DD2R}: degrees to radians. */
    private static final double AST__DD2R = 0.017453292519943295;

    private FitsFiducial() {}

    /**
     * C's {@code GetFiducialNSC} (fitschan.c:12902): the native spherical coordinates, in radians,
     * of the fiducial point of {@code wcsmap}, as {@code {phi, theta}}.
     *
     * <p>When parameter 0 of the longitude axis is non-zero, parameters 1 and 2 of that axis, in
     * degrees, give the fiducial point; their defaults are the native coordinates of the
     * projection's reference point, which are otherwise used. A TPN projection always uses the
     * reference point, since its parameters hold polynomial coefficients.
     */
    static double[] getFiducialNsc(WcsMap wcsmap) {
        if (wcsmap.getWcsType() != WcsMap.AST__TPN) {
            int axlon = wcsmap.getWcsAxis(1) - 1;
            if (wcsmap.getPV(axlon, 0) != 0.0) {
                return new double[] {AST__DD2R * wcsmap.getPV(axlon, 1), AST__DD2R * wcsmap.getPV(axlon, 2)};
            }
        }
        return new double[] {wcsmap.natLon(), wcsmap.natLat()};
    }
}
