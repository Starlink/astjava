package uk.ac.starlink.ast;

/**
 * The FITS-CLASS encoding's writer, C's {@code CLASSFromStore}, as far as this library reaches it.
 *
 * <p>FITS-CLASS, the encoding of the GILDAS CLASS package, describes a cube whose first three WCS
 * axes are a frequency axis and two celestial axes (RA/DEC or galactic, in a projection CLASS
 * knows, with no rotation), with the frequency given as an offset from the rest frequency and in
 * the source's rest frame, and the reference point's radio velocity in the LSRK (VELO-LSR) and its
 * azimuth and elevation. C checks the description first, and writes it only if FITS-CLASS can hold
 * it; to write it, C makes a SpecFrame, which this library does not have, to find the VELO-LSR
 * value. A description FITS-CLASS can hold has the SPECSYS of the source's rest frame, which only
 * a SpecFrame gives it; so every FrameSet of this library's classes is refused, as C refuses it,
 * and a description that passes the checks (which can be made only by hand) throws
 * UnsupportedOperationException.
 *
 * <p>Ported from fitschan.c {@code CLASSFromStore}, which includes the fix of upstream aea3090b
 * (a LONPOLE of +180 or -180 degrees is allowed); the Rust port's {@code fitschan/enc_class.rs} is
 * organised differently.
 */
final class FitsEncClass {

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code AST__BAD}, for the values C tests against it. */
    private static final double BAD = AstObject.AST__BAD;

    private FitsEncClass() {}

    /**
     * C's {@code CLASSFromStore}: the checks it makes of the primary description in {@code store}
     * before it writes it as FITS-CLASS cards. The spectral and celestial axes must be among the
     * first three; the projection TAN, ARC or STG, AIT or SFL with the reference point at the
     * origin, or SIN with zero parameters; the longitude RA or galactic; the spectral axis FREQ in
     * the source's rest frame (SPECSYS SOURCE), in Hz, kHz, MHz or GHz; no rotation unless both
     * celestial pixel axes span one pixel; an FK4 or FK5 system (when there is one) whose equinox
     * the IAU 1984 rule allows; a rest frequency; and, unless both celestial pixel axes span one
     * pixel, no LATPOLE and no LONPOLE but +180 or -180 degrees.
     *
     * <p>When the celestial pixel axes span one pixel, C first puts the reference point at pixel
     * (1, 1, 1), transforming that pixel with {@code fs}, which must then have three inputs and
     * three outputs.
     *
     * @param fc the FitsChan written to, whose NAXISi cards give the size of the celestial pixel
     *     axes
     * @param store the descriptions
     * @param fs the FrameSet the descriptions were made from
     * @param dim the size of each pixel axis, or {@code AST__BAD} where unknown, indexed (as C
     *     indexes it) by the WCS axis
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return false: the description is refused
     * @throws AstException as {@code fs} reports, with status {@code AST__NCPIN}, a transformation
     *     of pixel (1, 1, 1) by a FrameSet without three inputs and outputs
     * @throws UnsupportedOperationException if the description passes the checks, since C then
     *     makes a SpecFrame
     */
    static boolean classFromStore(
            FitsChan fc, FitsPlaneStores store, FrameSet fs, double[] dim, String method, String cls) {
        FitsStore st = store.get(' ');
        if (st == null) {
            st = new FitsStore(0);
        }
        FitsCardList cards = fc.cards();
        int rnd = fc.getFitsRounding();
        boolean ok = false;

        // The primary celestial and spectral axes.
        int[] axes = st.lonLatSpecAxes(store.naxis);
        int axlon = axes[0];
        int axlat = axes[1];
        int axspec = axes[2];
        Frame curfrm = fs.getFrame(FrameSet.AST__CURRENT);

        // They must be among WCS axes 1, 2 and 3.
        if (axspec >= 0 && axspec < 3 && axlon >= 0 && axlon < 3 && axlat >= 0 && axlat < 3) {
            // When the celestial pixel axes span one pixel, the reference point becomes the centre
            // of that pixel, pixel (1, 1, 1).
            if (store.naxis >= 3 && dim[axlon] == 1.0 && dim[axlat] == 1.0) {
                double[] xout = fs.tranN(1, 3, new double[] {1.0, 1.0, 1.0}, true, 3);
                if (xout[axlon] != BAD && xout[axlat] != BAD) {
                    // The celestial axes of the current Frame may not be the FITS axes of those
                    // indices, so the values are taken from its longitude and latitude axes.
                    double lonval = 0.0;
                    double latval = 0.0;
                    for (int i = 0; i < 3; i++) {
                        Boolean isLon = isLonAxis(curfrm, i + 1);
                        if (isLon != null) {
                            if (isLon) {
                                lonval = xout[i];
                            } else {
                                latval = xout[i];
                            }
                        }
                    }
                    st.crval[axlon] = lonval * AST__DR2D;
                    st.crval[axlat] = latval * AST__DR2D;
                    st.crpix[axlon] = 1.0;
                    st.crpix[axlat] = 1.0;
                }
            }

            // The projection, from the latitude CTYPE, decides whether the description is
            // usable so far. C first sets ok, and clears it for a missing CRVAL or CTYPE of either
            // celestial axis, but the test of the projection sets it again whatever it was; a
            // missing CRVAL is BAD, which fails the test of AIT and SFL. The CTYPE values are
            // never missing, as they name the celestial axes.
            double latval = value(at(st.crval, axlat));
            double lonval = value(at(st.crval, axlon));
            String lontype = at(st.ctype, axlon);
            String lattype = at(st.ctype, axlat);
            int prj = lattype == null ? WcsMap.AST__WCSBAD : FitsWcsCelestial.wcsPrjType(lattype.substring(4));
            if (prj == WcsMap.AST__WCSBAD) {
                ok = false;
            } else if (prj != WcsMap.AST__SIN) {
                ok = prj == WcsMap.AST__TAN
                        || prj == WcsMap.AST__ARC
                        || prj == WcsMap.AST__STG
                        || prj == WcsMap.AST__AIT
                        || prj == WcsMap.AST__SFL;
                // AIT and SFL need the reference point at the origin of the celestial coordinates.
                if (ok && (prj == WcsMap.AST__AIT || prj == WcsMap.AST__SFL) && (latval != 0.0 || lonval != 0.0)) {
                    ok = false;
                }
            } else {
                // A SIN projection whose parameters are both zero.
                double p1 = value(at(st.pv, axlat, 1));
                double p2 = value(at(st.pv, axlat, 2));
                ok = (p1 == BAD ? 0.0 : p1) == 0.0 && (p2 == BAD ? 0.0 : p2) == 0.0;
            }

            // RA or galactic longitude.
            if (ok && !lontype.startsWith("RA--") && !lontype.startsWith("GLON")) {
                ok = false;
            }

            // A FREQ spectral axis: the CTYPE must be a leading part of "FREQ".
            String cval = at(st.ctype, axspec);
            if (cval == null || !"FREQ".startsWith(cval.substring(0, FitsCard.chrLen(cval)))) {
                ok = false;
            }

            // The source's rest frame: SPECSYS a leading part of "SOURCE".
            cval = st.specsys;
            if (cval == null) {
                ok = false;
            } else if (ok && !"SOURCE".startsWith(cval.substring(0, FitsCard.chrLen(cval)))) {
                ok = false;
            }

            // Units of frequency.
            cval = at(st.cunit, axspec);
            if (cval == null) {
                ok = false;
            } else if (ok && !"Hz".equals(cval) && !"kHz".equals(cval) && !"MHz".equals(cval) && !"GHz".equals(cval)) {
                ok = false;
            }
        }

        // The number of WCS axes, at most three.
        int naxis = Math.min(st.maxCrpixAxis() + 1, 3);

        // The sizes of the celestial pixel axes, 0 where unknown; the rotation check is skipped
        // when both span one pixel.
        int naxis2 = naxisValue(cards, axlon, rnd, method, cls);
        int naxis3 = naxisValue(cards, axlat, rnd, method, cls);
        for (int i = 0; i < naxis && ok; i++) {
            for (int j = 0; j < naxis && ok; j++) {
                if (i != j && st.cdElement(i, j) != 0.0 && (naxis2 != 1 || naxis3 != 1)) {
                    ok = false;
                }
            }
        }

        // FK4 or FK5, and the equinox the IAU 1984 rule gives them.
        String radesys = st.radesys;
        if (radesys != null) {
            Double equ = st.equinox;
            if ("FK4".equals(radesys)) {
                if (equ != null && equ >= 1984.0) {
                    ok = false;
                }
            } else if ("FK5".equals(radesys)) {
                if (equ != null && equ < 1984.0) {
                    ok = false;
                }
            } else {
                ok = false;
            }
        }

        // A rest frequency.
        if (st.restfrq == null) {
            ok = false;
        }

        // No LATPOLE, and a LONPOLE of +180 or -180 degrees, unless the celestial pixel axes span
        // one pixel.
        if (ok && (naxis2 != 1 || naxis3 != 1)) {
            if (st.lonpole != null && st.lonpole != -180.0 && st.lonpole != 180.0) {
                ok = false;
            }
            if (st.latpole != null) {
                ok = false;
            }
        }

        if (ok) {
            throw new UnsupportedOperationException(
                    method + "(" + cls + "): the VELO-LSR value of a FITS-CLASS header needs SpecFrame");
        }
        return false;
    }

    /**
     * C's {@code astHasAttribute( frame, "IsLonAxis(axis)" )} and {@code astGetI}: whether axis
     * {@code axis} (from 1) of {@code frame} is a longitude axis, or null if the Frame has no such
     * attribute for that axis (it is not an axis of a SkyFrame).
     */
    private static Boolean isLonAxis(Frame frame, int axis) {
        try {
            return frame.getI("IsLonAxis(" + axis + ")") != 0;
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
            return null;
        }
    }

    /**
     * The integer value of keyword NAXIS{@code axis + 1}, read as C's {@code GetValue} reads it,
     * unmarked and with no error for a card that is absent or does not convert, which give 0.
     */
    private static int naxisValue(FitsCardList cards, int axis, int rnd, String method, String cls) {
        FitsCardValue.Got g =
                cards.getValue("NAXIS" + (axis + 1), FitsCardValue.Want.INT, false, false, rnd, method, cls);
        return g == null ? 0 : (int) g.i;
    }

    /** {@code v}, or {@link #BAD} (C's unset item) if it is null. */
    private static double value(Double v) {
        return v != null ? v : BAD;
    }

    /** Element {@code i} of {@code a}, null (C's unset item) beyond its end. */
    private static <T> T at(T[] a, int i) {
        return i < a.length ? a[i] : null;
    }

    /** Element {@code [i][j]} of {@code a}, null beyond its ends. */
    private static <T> T at(T[][] a, int i, int j) {
        return i < a.length && j < a[i].length ? a[i][j] : null;
    }
}
