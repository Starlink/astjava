package uk.ac.starlink.ast;

/**
 * The cards of a FITS-IRAF header for the primary axis description of a FrameSet: C's {@code
 * IRAFFromStore}, which writes the description {@link FitsFsetToStore#fsetToStore} made.
 *
 * <p>FITS-IRAF is FITS-WCS with these restrictions: only the primary description is written; the
 * celestial axes must be RA/DEC, galactic or ecliptic, with no LONPOLE or LATPOLE, and a projection
 * with no parameters other than TPN, or a SIN projection with parameters that are zero or give the
 * old NCP projection, which is written as {@code -NCP}; SFL is written with the older code {@code
 * GLS}; there is no WCSAXES keyword, so there must be as many WCS axes as pixel axes; RADECSYS is
 * used in place of RADESYS; and the CD matrix is used in place of CDELT and PC.
 *
 * <p>Ported from fitschan.c {@code IRAFFromStore}, which includes the fix of upstream 6c47ddde: an
 * MJD-OBS card already in the header, which a read leaves there, is set to the date written as
 * DATE-OBS. The Rust port's {@code fitschan/enc_iraf.rs} is organised differently.
 */
final class FitsEncIraf {

    /** C's {@code AST__DD2R}: radians per degree. */
    private static final double AST__DD2R = 0.017453292519943295;

    private FitsEncIraf() {}

    /**
     * C's {@code IRAFFromStore}: writes the primary description in {@code store} to {@code fc} as
     * FITS-IRAF cards, each in front of the current card, if the description meets the
     * restrictions of the encoding. A description lacking a CRPIX, CRVAL or CTYPE on one of its
     * axes, or with a {@code -TAB} axis, stops the write part way, as C returns there; {@link
     * FitsChan#write} then removes the cards written.
     *
     * @param fc the FitsChan written to
     * @param store the descriptions
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return true if the description was written
     */
    static boolean irafFromStore(FitsChan fc, FitsPlaneStores store, String method, String cls) {
        int rnd = fc.getFitsRounding();
        FitsCardList cards = fc.cards();

        // Only the primary description is written.
        FitsStore st = store.get(' ');
        if (st == null) {
            // C reads unset values from the store of a version with nothing stored.
            st = new FitsStore(0);
        }

        // Whether the description meets the restrictions of the encoding.
        boolean ok = true;
        int[] axes = st.lonLatSpecAxes(store.naxis);
        int axlon = axes[0];
        int axlat = axes[1];
        String lontype = null;
        String lattype = null;
        if (axlon >= 0 && axlat >= 0) {
            lontype = at(st.ctype, axlon);
            if (lontype == null) {
                return false;
            }
            lattype = at(st.ctype, axlat);
            if (lattype == null) {
                return false;
            }
            int prj = FitsWcsCelestial.wcsPrjType(lattype.substring(4));
            ok = false;
            if (prj == WcsMap.AST__TPN || prj == WcsMap.AST__WCSBAD) {
                // FITS-IRAF cannot hold the AST-specific TPN projection.
                ok = false;
            } else if (prj != WcsMap.AST__SIN) {
                // There must be no projection parameters.
                if (st.maxPvM() == -1) {
                    ok = true;
                }
                if (prj == WcsMap.AST__SFL) {
                    lontype = withCode(lontype, "-GLS");
                    lattype = withCode(lattype, "-GLS");
                }
            } else {
                // A SIN projection whose parameters are both zero, or the first zero and the
                // second the cotangent of the reference latitude (the old NCP projection).
                double p1 = value(at(st.pv, axlat, 1), 0.0);
                double p2 = value(at(st.pv, axlat, 2), 0.0);
                Double val = at(st.crval, axlat);
                if (val != null && p1 == 0.0) {
                    if (p2 == 0.0) {
                        ok = true;
                    } else if (Math.abs(p2) >= 1.0e14 && val == 0.0) {
                        ok = true;
                        lontype = withCode(lontype, "-NCP");
                        lattype = withCode(lattype, "-NCP");
                    } else if (Math.abs(p2 * Math.tan(AST__DD2R * val) - 1.0) < 0.01) {
                        ok = true;
                        lontype = withCode(lontype, "-NCP");
                        lattype = withCode(lattype, "-NCP");
                    }
                }
            }

            // Only RA, galactic longitude and ecliptic longitude can be held.
            if (!lontype.startsWith("RA--") && !lontype.startsWith("GLON") && !lontype.startsWith("ELON")) {
                ok = false;
            }

            // Nor can a LONPOLE or LATPOLE.
            if (st.latpole != null || st.lonpole != null) {
                ok = false;
            }
        }

        // There is no WCSAXES keyword, so there must be as many WCS axes as pixel axes.
        int naxis = st.maxCrpixAxis() + 1;
        if (naxis != store.naxis) {
            ok = false;
        }
        if (!ok) {
            return false;
        }

        for (int i = 0; i < naxis; i++) {
            Double val = at(st.crpix, i);
            if (val == null) {
                return false;
            }
            setFloat(cards, "CRPIX" + (i + 1), val, "Reference pixel on axis " + (i + 1), rnd);
        }
        for (int j = 0; j < naxis; j++) {
            Double val = at(st.crval, j);
            if (val == null) {
                return false;
            }
            setFloat(cards, "CRVAL" + (j + 1), val, "Value at ref. pixel on axis " + (j + 1), rnd);
        }

        // CTYPE, the celestial axes with the projection codes chosen above.
        for (int i = 0; i < naxis; i++) {
            String cval;
            if (i == axlat) {
                cval = lattype;
            } else if (i == axlon) {
                cval = lontype;
            } else {
                cval = at(st.ctype, i);
                if (cval == null) {
                    return false;
                }
            }
            if (cval.length() > 4 && "-TAB".equals(cval.substring(4))) {
                return false;
            }
            String comm = at(st.ctypeCom, i);
            if (comm == null) {
                comm = "Type of co-ordinate on axis " + (i + 1); // codespell:ignore
            }
            setString(cards, "CTYPE" + (i + 1), cval, comm, rnd);
        }

        // The CD matrix, the PC matrix with each row multiplied by its CDELT; zero elements left out.
        for (int i = 0; i < naxis; i++) {
            double cdelt = value(at(st.cdelt, i), 1.0);
            for (int j = 0; j < naxis; j++) {
                double val = value(at(st.pc, i, j), i == j ? 1.0 : 0.0);
                val *= cdelt;
                if (val != 0.0) {
                    setFloat(cards, "CD" + (i + 1) + "_" + (j + 1), val, "Transformation matrix element", rnd);
                }
            }
        }

        for (int i = 0; i < naxis; i++) {
            String cval = at(st.cunit, i);
            if (cval != null) {
                setString(cards, "CUNIT" + (i + 1), cval, "Units for axis " + (i + 1), rnd);
            }
        }

        if (st.radesys != null) {
            setString(cards, "RADECSYS", st.radesys, "Reference frame for RA/DEC values", rnd);
        }
        if (st.equinox != null) {
            setFloat(cards, "EQUINOX", st.equinox, "Epoch of reference equinox", rnd);
        }

        // The date of observation as DATE-OBS. A read leaves any MJD-OBS card in the header, since
        // other software may use it, so such a card is set to the same date.
        if (st.mjdobs != null) {
            double val = st.mjdobs;
            setString(cards, "DATE-OBS", FitsDates.mjdToDateStr(val), "Date of observation", rnd);
            if (cards.hasCard("MJD-OBS")) {
                setFloat(cards, "MJD-OBS", val, "Modified Julian Date of observation", rnd);
            }
        }
        return true;
    }

    /** C's {@code strcpy( type + 4, code )}: the first four characters of {@code ctype} and {@code code}. */
    private static String withCode(String ctype, String code) {
        return ctype.substring(0, 4) + code;
    }

    /** {@code v}, or {@code unset} (in place of C's unset item) if it is null. */
    private static double value(Double v, double unset) {
        return v != null ? v : unset;
    }

    /** Element {@code i} of {@code a}, null (C's unset item) beyond its end. */
    private static <T> T at(T[] a, int i) {
        return i < a.length ? a[i] : null;
    }

    /** Element {@code [i][j]} of {@code a}, null beyond its ends. */
    private static <T> T at(T[][] a, int i, int j) {
        return i < a.length && j < a[i].length ? a[i][j] : null;
    }

    private static void setFloat(FitsCardList cards, String keyword, double value, String comment, int rnd) {
        cards.setValueAsWriter(keyword, FitsCard.Value.ofFloat(value), comment, rnd);
    }

    private static void setString(FitsCardList cards, String keyword, String value, String comment, int rnd) {
        cards.setValueAsWriter(keyword, FitsCard.Value.ofString(value), comment, rnd);
    }
}
