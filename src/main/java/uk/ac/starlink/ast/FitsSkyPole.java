package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The LONPOLE and LATPOLE values of a FITS-WCS description being written: C's {@code SkyPole}
 * (fitschan.c).
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/sky_pole.rs}, following fitschan.c.
 */
final class FitsSkyPole {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** C's {@code AST__DD2R}: degrees to radians. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code AST__DR2D}: radians to degrees. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code AST__DPI}: pi. */
    private static final double AST__DPI = 3.141592653589793;

    /** C's {@code AST__DPIBY2}: pi/2. */
    private static final double AST__DPIBY2 = 1.5707963267948966;

    /** The LATPOLE C's {@code SkyPole} passes to {@code WcsNatPole}. */
    private static final double LATPOLE_PROBE = 999.0;

    private FitsSkyPole() {}

    /**
     * C's {@code SkyPole}: stores LONPOLE and LATPOLE, in degrees, for the projection {@code
     * map2}, and for any projection but TPN copies them to parameters 3 and 4 of the FITS
     * longitude axis. A value taking its default is stored as unset.
     *
     * <p>For a zenithal projection any LONPOLE can be had by a rotation in the projection plane,
     * so the values are parameters 3 and 4 of the longitude axis of {@code map2}, where set (and
     * never for TPN, whose parameters are polynomial terms). For any other projection, the
     * celestial north pole is transformed to native spherical coordinates through the inverse of
     * {@code map3}; its native latitude is LATPOLE unless it is the default {@code WcsNatPole}
     * gives for the CRVAL already stored, and its native longitude, normalised to 0 to 2pi, is
     * LONPOLE unless it is the default, 0 when the celestial latitude of the fiducial point is at
     * least its native latitude and pi otherwise.
     *
     * @param map2 the inverted WcsMap, from intermediate world coordinates to native spherical
     *     coordinates
     * @param map3 the Mapping from native spherical to celestial coordinates
     * @param ilon the zero-based output of {@code map3} giving longitude
     * @param ilat the zero-based output of {@code map3} giving latitude
     * @param wperm the zero-based FITS axis describing each axis of the WCS Frame
     * @param s the coordinate version character
     * @param store the store, holding the CRVAL values
     * @param method the calling method (unused, as in C)
     * @param cls the class of the object written (unused, as in C)
     */
    static void skyPole(
            WcsMap map2,
            Mapping map3,
            int ilon,
            int ilat,
            int[] wperm,
            char s,
            FitsStore store,
            String method,
            String cls) {
        int axlon = map2.getWcsAxis(1) - 1;
        int axlat = map2.getWcsAxis(2) - 1;
        int fitsIlon = wperm[ilon];
        int fitsIlat = wperm[ilat];

        double lonpole = AST__BAD;
        double latpole = AST__BAD;
        if (map2.isZenithal()) {
            if (map2.getWcsType() != WcsMap.AST__TPN) {
                lonpole = map2.testPV(axlon, 3) ? map2.getPV(axlon, 3) : AST__BAD;
                latpole = map2.testPV(axlon, 4) ? map2.getPV(axlon, 4) : AST__BAD;
            }
        } else {
            // The celestial north pole in native spherical coordinates, through the inverse of
            // map3: LONPOLE and LATPOLE.
            int nax2 = map3.getNout();
            double[][] ptr2 = new double[nax2][1];
            ptr2[ilat][0] = AST__DPIBY2;
            double[][] ptr1 = map3.transform(ptr2, 1, false);

            // The fiducial point (CRVAL) in radians.
            Double crvalLat = store.crval[fitsIlat];
            double delta0 = (crvalLat == null ? 0.0 : crvalLat) * AST__DD2R;
            Double crvalLon = store.crval[fitsIlon];
            double alpha0 = (crvalLon == null ? 0.0 : crvalLon) * AST__DD2R;

            // The default LATPOLE, by equation 8 of FITS-WCS paper II taking the positive signs;
            // LATPOLE need not be stored if the pole has it. WcsNatPole sets a bad LONPOLE to its
            // default, which C then uses below.
            double phip = ptr1[axlon][0];
            FitsNativePole.NativePole np = FitsNativePole.wcsNatPole(
                    map2, alpha0, delta0, LATPOLE_PROBE, phip == AST__BAD ? null : phip, null);
            ptr1[axlon][0] = np.phip;
            if (np.pole != null) {
                latpole = FitsStore.equalAng(ptr1[axlat][0], np.pole[1]) ? AST__BAD : ptr1[axlat][0];
            } else {
                latpole = ptr1[axlat][0];
            }

            // The default LONPOLE is 0 if the celestial latitude of the fiducial point is at
            // least its native latitude, and pi otherwise.
            double theta0 = FitsFiducial.getFiducialNsc(map2)[1];
            lonpole = Pal.dranrm(ptr1[axlon][0]);
            double deflonpole = delta0 >= theta0 ? 0.0 : AST__DPI;
            if (FitsStore.equalAng(lonpole, deflonpole)) {
                lonpole = AST__BAD;
            }

            if (lonpole != AST__BAD) {
                lonpole *= AST__DR2D;
            }
            if (latpole != AST__BAD) {
                latpole *= AST__DR2D;
            }
        }

        store.lonpole = valueOrNull(lonpole);
        store.latpole = valueOrNull(latpole);

        // FITS-WCS paper II recommends copies in parameters 3 and 4 of the longitude axis, which
        // TPN uses for other purposes.
        if (map2.getWcsType() != WcsMap.AST__TPN) {
            store.setPv(fitsIlon, 3, valueOrNull(lonpole));
            store.setPv(fitsIlon, 4, valueOrNull(latpole));
        }
    }

    /** A store value: null for {@code AST__BAD}, as C's {@code GetItem} reads a bad value as unset. */
    private static Double valueOrNull(double v) {
        return v == AST__BAD ? null : v;
    }
}
