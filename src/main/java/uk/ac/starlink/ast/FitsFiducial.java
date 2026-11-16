package uk.ac.starlink.ast;

/**
 * The fiducial point of a WcsMap projection, in native spherical, projection plane and celestial
 * coordinates, as FITS-WCS reads and writes need it.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/fiducial.rs}, following fitschan.c.
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

    /**
     * C's {@code GetFiducialPPC} (fitschan.c:12977): the projection plane coordinates, in radians,
     * of the fiducial point of {@code map}, an inverted WcsMap, as {@code {x0, y0}}; found by
     * transforming the native spherical coordinates of the fiducial point through the inverse of
     * {@code map}, with zero on the other axes.
     */
    static double[] getFiducialPpc(WcsMap map) {
        int naxes = map.getNin();
        int axlon = map.getWcsAxis(1) - 1;
        int axlat = map.getWcsAxis(2) - 1;
        double[][] in = new double[naxes][1];
        double[] nsc = getFiducialNsc(map);
        in[axlon][0] = nsc[0];
        in[axlat][0] = nsc[1];
        double[][] out = map.tranP(1, naxes, in, false, naxes);
        return new double[] {out[axlon][0], out[axlat][0]};
    }

    /**
     * C's {@code GetFiducialWCS} (fitschan.c:13076): the celestial longitude and latitude, in
     * radians, of the fiducial point of the projection of {@code wcsmap}, as {@code {fidlon,
     * fidlat}}; found by transforming the native spherical coordinates of the fiducial point, with
     * zero on the other axes, through {@code map2}, from native spherical to world coordinates,
     * and taking outputs {@code colon} and {@code colat}.
     *
     * @return the position, or null if either value is bad
     */
    static double[] getFiducialWcs(WcsMap wcsmap, Mapping map2, int colon, int colat) {
        int naxin = map2.getNin();
        int axlon = wcsmap.getWcsAxis(1) - 1;
        int axlat = wcsmap.getWcsAxis(2) - 1;
        double[][] ptr1 = new double[naxin][1];
        double[] nsc = getFiducialNsc(wcsmap);
        ptr1[axlon][0] = nsc[0];
        ptr1[axlat][0] = nsc[1];
        double[][] ptr2 = map2.transform(ptr1, 1, true);
        double fidlon = ptr2[colon][0];
        double fidlat = ptr2[colat][0];
        if (fidlon == AstObject.AST__BAD || fidlat == AstObject.AST__BAD) {
            return null;
        }
        return new double[] {fidlon, fidlat};
    }
}
