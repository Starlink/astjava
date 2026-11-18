package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The cards of a FITS-AIPS or FITS-AIPS++ header for the primary axis description of a FrameSet:
 * C's {@code AIPSFromStore} and {@code AIPSPPFromStore}, which write the description {@link
 * FitsFsetToStore#fsetToStore} made.
 *
 * <p>FITS-AIPS is FITS-WCS with these restrictions: only the primary description is written; the
 * celestial projection has no parameters, except a SIN projection whose parameters are zero or give
 * the old NCP projection, written as {@code -NCP}; AIT, MER and SFL are written only with the
 * reference point at the origin of the celestial coordinates, and SFL with the older code {@code
 * GLS}; the celestial axes are RA/DEC, galactic or ecliptic, with no LONPOLE or LATPOLE; EPOCH is
 * written in place of EQUINOX and RADECSYS, so the system must follow the IAU 1984 rule (FK4 before
 * 1984, FK5 after), and ICRS cannot be written; any rotation is in the celestial plane (or, with no
 * celestial axes, that of the first two non-spectral axes), with no shear, and is written as CROTA
 * and CDELT; a spectral axis has a CTYPE of {@code FREQ}, {@code VRAD} or {@code VOPT-F2W}, written
 * with the standard of rest in AIPS's form ({@code VELO-LSR} and so on), a standard of rest of LSRK,
 * LSRD, BARYCENT or GEOCENTR, and units of Hz or m/s or a multiple of them; and there is no WCSAXES
 * keyword, so there must be as many WCS axes as pixel axes.
 *
 * <p>FITS-AIPS++ lifts some of these: the latitude axis may have up to ten projection parameters,
 * written as {@code PROJPm}, with any projection but TPN; LONPOLE and LATPOLE are written; and a
 * rotation that CROTA cannot describe is written as a CD matrix.
 *
 * <p>Ported from fitschan.c {@code AIPSFromStore} and {@code AIPSPPFromStore}, which include the fix
 * of upstream 6c47ddde: an MJD-OBS card already in the header, which a read leaves there, is set to
 * the date written as DATE-OBS. The Rust port's {@code fitschan/enc_aips.rs} is organised
 * differently.
 */
final class FitsEncAips {

    /** C's {@code AST__DD2R}: radians per degree. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code AST__BAD}, for the values C tests against it. */
    private static final double BAD = AstObject.AST__BAD;

    private FitsEncAips() {}

    /** What the checks of the spectral axis give: the AIPS CTYPE, units and unit factor. */
    private static final class Spectral {
        /** The AIPS CTYPE, null until the FITS-WCS CTYPE is recognised. */
        String type;

        /** The AIPS units, empty until the units are recognised. */
        String unit = "";

        /** The factor converting the FITS-WCS units to {@link #unit}. */
        double factor = 1.0;
    }

    /** The rotation and pixel sizes the CD matrix gives. */
    private static final class Rotation {
        /** The first and second rotation axes, -1 for none. */
        int axrot1;

        int axrot2;

        /** The CDELT value of each axis. */
        double[] cdelt;

        /** The CROTA value in degrees, or {@link #BAD} if the rotation is not one CROTA can give. */
        double crota;

        /** False if the CD matrix rotates outside the plane of the rotation axes. */
        boolean ok = true;
    }

    /**
     * C's {@code AIPSFromStore}: writes the primary description in {@code store} to {@code fc} as
     * FITS-AIPS cards, each in front of the current card, if the description meets the
     * restrictions of the encoding. A description lacking a CRPIX, CRVAL or CTYPE on one of its
     * axes, or with a {@code -TAB} axis, gives false after the cards written; {@link
     * FitsChan#write} then removes them.
     *
     * @param fc the FitsChan written to
     * @param store the descriptions
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return true if the description was written
     */
    static boolean aipsFromStore(FitsChan fc, FitsPlaneStores store, String method, String cls) {
        int rnd = fc.getFitsRounding();
        FitsCardList cards = fc.cards();
        FitsStore st = primary(store);

        // Whether the description meets the restrictions of the encoding.
        boolean ok = true;
        int[] axes = st.lonLatSpecAxes(store.naxis);
        int axlon = axes[0];
        int axlat = axes[1];
        int axspec = axes[2];
        String lontype = null;
        String lattype = null;
        if (axlon >= 0 && axlat >= 0) {
            double latval = value(at(st.crval, axlat), BAD);
            if (latval == BAD) {
                ok = false;
            }
            double lonval = value(at(st.crval, axlon), BAD);
            if (lonval == BAD) {
                ok = false;
            }

            // The CTYPE values of both axes, which name a longitude and a latitude axis, and the
            // projection the latitude axis gives.
            lontype = at(st.ctype, axlon);
            lattype = at(st.ctype, axlat);
            int prj = FitsWcsCelestial.wcsPrjType(lattype.substring(4));

            if (prj == WcsMap.AST__WCSBAD) {
                ok = false;
            } else if (prj != WcsMap.AST__SIN) {
                if (st.maxPvM() >= 0) {
                    // There must be no projection parameters.
                    ok = false;
                } else if (prj == WcsMap.AST__TPN) {
                    // FITS-AIPS cannot hold the AST-specific TPN projection.
                    ok = false;
                } else if (prj == WcsMap.AST__MER || prj == WcsMap.AST__AIT || prj == WcsMap.AST__SFL) {
                    // These need the reference point at the origin of the celestial coordinates;
                    // SFL is written with the older code GLS.
                    if (latval != 0.0 || lonval != 0.0) {
                        ok = false;
                    } else if (prj == WcsMap.AST__SFL) {
                        lontype = withCode(lontype, "-GLS");
                        lattype = withCode(lattype, "-GLS");
                    }
                }
            } else {
                // A SIN projection whose parameters are both zero, or the first zero and the second
                // the cotangent of the reference latitude (the old NCP projection).
                double p1 = value(at(st.pv, axlat, 1), 0.0);
                double p2 = value(at(st.pv, axlat, 2), 0.0);
                ok = false;
                if (p1 == 0.0) {
                    if (p2 == 0.0) {
                        ok = true;
                    } else if (Math.abs(p2) >= 1.0e14 && latval == 0.0) {
                        ok = true;
                        lontype = withCode(lontype, "-NCP");
                        lattype = withCode(lattype, "-NCP");
                    } else if (Math.abs(p2 * Math.tan(AST__DD2R * latval) - 1.0) < 0.01) {
                        ok = true;
                        lontype = withCode(lontype, "-NCP");
                        lattype = withCode(lattype, "-NCP");
                    }
                }
            }

            // Only RA, galactic longitude and ecliptic longitude can be held.
            if (ok && !isAipsLongitude(lontype)) {
                ok = false;
            }

            // Nor can a LONPOLE or LATPOLE.
            if (st.latpole != null || st.lonpole != null) {
                ok = false;
            }
        }

        // The spectral axis.
        Spectral spec = new Spectral();
        if (ok && axspec >= 0) {
            String cval = at(st.ctype, axspec);
            if (cval == null) {
                ok = false;
            } else {
                spec.type = specType(cval);
                if (spec.type == null) {
                    ok = false;
                }
            }
            cval = st.specsys;
            if (cval == null) {
                ok = false;
            } else if (ok) {
                String sor = standardOfRest(cval);
                if (sor == null) {
                    ok = false;
                } else {
                    spec.type += sor;
                }
            }
            cval = at(st.cunit, axspec);
            if (cval == null) {
                ok = false;
            } else if (ok && !specUnit(cval, spec)) {
                ok = false;
            }
        }

        // There is no WCSAXES keyword, so there must be as many WCS axes as pixel axes.
        int naxis = st.maxCrpixAxis() + 1;
        if (naxis != store.naxis) {
            ok = false;
        }

        // The rotation, which must be one CROTA can give.
        Rotation rot = rotation(st, naxis, axlon, axlat, axspec, ok);
        ok = ok && rot.ok;
        double crota = 0.0;
        if (ok && rot.axrot1 >= 0 && rot.axrot2 >= 0) {
            if (rot.crota == BAD) {
                ok = false;
            } else {
                crota = rot.crota;
            }
        }

        // The reference equinox, called EPOCH, which must agree with RADECSYS.
        double[] epoch = {value(st.equinox, BAD)};
        if (!epochAgrees(st.radesys, epoch)) {
            ok = false;
        }
        if (!ok) {
            return false;
        }

        ok = writeAxes(cards, st, naxis, axlon, axlat, axspec, lontype, lattype, spec, rot.cdelt, rnd);

        // CROTA, on the second rotation axis.
        if (rot.axrot2 != -1) {
            setFloat(cards, "CROTA" + (rot.axrot2 + 1), crota, "Axis rotation", rnd);
        } else if ((axspec == -1 && naxis > 1) || (axspec != -1 && naxis > 2)) {
            setFloat(cards, "CROTA1", crota, "Axis rotation", rnd);
        }

        if (epoch[0] != BAD) {
            setFloat(cards, "EPOCH", epoch[0], "Epoch of reference equinox", rnd);
        }
        writeDate(cards, st, rnd);
        writeRestFrequency(cards, st, axspec, rnd);
        return ok;
    }

    /**
     * C's {@code AIPSPPFromStore}: writes the primary description in {@code store} to {@code fc} as
     * FITS-AIPS++ cards, each in front of the current card, if the description meets the
     * restrictions of the encoding. A description lacking a CRPIX, CRVAL or CTYPE on one of its
     * axes, or with a {@code -TAB} axis, gives false after the cards written; {@link
     * FitsChan#write} then removes them.
     *
     * @param fc the FitsChan written to
     * @param store the descriptions
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return true if the description was written
     */
    static boolean aipsppFromStore(FitsChan fc, FitsPlaneStores store, String method, String cls) {
        int rnd = fc.getFitsRounding();
        FitsCardList cards = fc.cards();
        FitsStore st = primary(store);

        // Whether the description meets the restrictions of the encoding.
        boolean ok = true;
        int naxis = st.maxCrpixAxis() + 1;
        int maxm = 0;
        int[] axes = st.lonLatSpecAxes(store.naxis);
        int axlon = axes[0];
        int axlat = axes[1];
        int axspec = axes[2];
        String lontype = null;
        String lattype = null;
        if (axlon >= 0 && axlat >= 0) {
            // The CTYPE values of both axes, which name a longitude and a latitude axis, and the
            // projection the latitude axis gives. FITS-AIPS++ cannot hold the AST-specific TPN
            // projection.
            lontype = at(st.ctype, axlon);
            lattype = at(st.ctype, axlat);
            int prj = FitsWcsCelestial.wcsPrjType(lattype.substring(4));
            if (prj == WcsMap.AST__TPN || prj == WcsMap.AST__WCSBAD) {
                ok = false;
            }

            // Projection parameters: only the first ten of the latitude axis; those of the
            // longitude axis are left out.
            maxm = st.maxPvM();
            for (int i = 0; i < naxis && ok; i++) {
                if (i != axlon) {
                    for (int m = 0; m <= maxm; m++) {
                        if (at(st.pv, i, m) != null && (i != axlat || m >= 10)) {
                            ok = false;
                            break;
                        }
                    }
                }
            }

            // Only RA, galactic longitude and ecliptic longitude can be held.
            if (ok && !isAipsLongitude(lontype)) {
                ok = false;
            }
        }

        // The spectral axis.
        Spectral spec = new Spectral();
        if (axspec >= 0) {
            String cval = at(st.ctype, axspec);
            if (cval == null) {
                ok = false;
            } else {
                spec.type = specType(cval);
                if (spec.type == null) {
                    ok = false;
                }
            }
            cval = st.specsys;
            if (cval == null) {
                ok = false;
            } else {
                String sor = standardOfRest(cval);
                if (sor == null) {
                    ok = false;
                } else if (spec.type != null) {
                    spec.type += sor;
                }
            }
            cval = at(st.cunit, axspec);
            if (cval == null) {
                ok = false;
            } else if (ok && !specUnit(cval, spec)) {
                ok = false;
            }
        }

        // There is no WCSAXES keyword, so there must be as many WCS axes as pixel axes.
        if (naxis != store.naxis) {
            ok = false;
        }

        // The rotation; one CROTA cannot give is written as a CD matrix.
        Rotation rot = rotation(st, naxis, axlon, axlat, axspec, ok);
        ok = ok && rot.ok;
        double crota = 0.0;
        if (ok && rot.axrot1 >= 0 && rot.axrot2 >= 0) {
            crota = rot.crota;
        }

        // The reference equinox, called EPOCH, which must agree with RADECSYS.
        double[] epoch = {value(st.equinox, BAD)};
        if (!epochAgrees(st.radesys, epoch)) {
            ok = false;
        }
        if (!ok) {
            return false;
        }

        ok = writeAxes(cards, st, naxis, axlon, axlat, axspec, lontype, lattype, spec, rot.cdelt, rnd);

        if (crota == BAD) {
            // The CD matrix, the PC matrix with each row multiplied by its CDELT; zero elements
            // left out.
            for (int i = 0; i < naxis; i++) {
                for (int j = 0; j < naxis; j++) {
                    double val = st.cdElement(i, j);
                    if (val != 0.0) {
                        setFloat(cards, "CD" + (i + 1) + "_" + (j + 1), val, "Transformation matrix element", rnd);
                    }
                }
            }
        } else if (crota != 0.0) {
            // CROTA, on the second rotation axis.
            if (rot.axrot2 != -1) {
                setFloat(cards, "CROTA" + (rot.axrot2 + 1), crota, "Axis rotation", rnd);
            } else if ((axspec == -1 && naxis > 1) || (axspec != -1 && naxis > 2)) {
                setFloat(cards, "CROTA1", crota, "Axis rotation", rnd);
            }
        }

        if (epoch[0] != BAD) {
            setFloat(cards, "EPOCH", epoch[0], "Epoch of reference equinox", rnd);
        }
        if (st.latpole != null) {
            setFloat(cards, "LATPOLE", st.latpole, "Latitude of native north pole", rnd);
        }
        if (st.lonpole != null) {
            setFloat(cards, "LONPOLE", st.lonpole, "Longitude of native north pole", rnd);
        }
        writeDate(cards, st, rnd);

        // The projection parameters of the latitude axis.
        if (axlat >= 0 && axlon >= 0) {
            for (int m = 0; m <= maxm; m++) {
                Double val = at(st.pv, axlat, m);
                if (val != null) {
                    setFloat(cards, "PROJP" + m, val, "Projection parameter", rnd);
                }
            }
        }
        writeRestFrequency(cards, st, axspec, rnd);
        return ok;
    }

    /** The primary description, or an empty one for a version with nothing stored, which C reads as unset values. */
    private static FitsStore primary(FitsPlaneStores store) {
        FitsStore st = store.get(' ');
        return st != null ? st : new FitsStore(0);
    }

    /**
     * The rotation axes and the CDELT values and CROTA (in degrees) of the CD matrix, as both
     * writers of C find them: the rotation is in the celestial plane, or, with no celestial axes,
     * that of the first two non-spectral axes. The matrix is examined only if {@code ok}; {@link
     * Rotation#ok} is false if it rotates outside that plane, and {@link Rotation#crota} is {@link
     * #BAD} if its two estimates of CROTA disagree, and zero if there is no pair of rotation axes.
     */
    private static Rotation rotation(FitsStore st, int naxis, int axlon, int axlat, int axspec, boolean ok) {
        Rotation rot = new Rotation();
        if (axlat < 0 && axlon < 0) {
            if (axspec >= 0 && naxis > 2) {
                rot.axrot2 = axspec == 0 ? 1 : 0;
                rot.axrot1 = rot.axrot2 + 1;
                if (rot.axrot1 == axspec) {
                    rot.axrot1++;
                }
            } else if (naxis > 1) {
                rot.axrot2 = 0;
                rot.axrot1 = 1;
            } else {
                rot.axrot2 = -1;
                rot.axrot1 = -1;
            }
        } else {
            rot.axrot1 = axlon;
            rot.axrot2 = axlat;
        }
        rot.cdelt = new double[Math.max(naxis, 0)];
        if (!ok) {
            return rot;
        }

        // The diagonal of the CD matrix, and its elements in the plane of the rotation axes.
        double cdlatLon = 0.0;
        double cdlonLat = 0.0;
        for (int i = 0; i < naxis && rot.ok; i++) {
            for (int j = 0; j < naxis && rot.ok; j++) {
                double val = st.cdElement(i, j);
                if (i == j) {
                    rot.cdelt[i] = val;
                } else if (i == rot.axrot2 && j == rot.axrot1) {
                    cdlatLon = val;
                } else if (i == rot.axrot1 && j == rot.axrot2) {
                    cdlonLat = val;
                } else if (val != 0.0) {
                    rot.ok = false;
                }
            }
        }
        if (!rot.ok || rot.axrot1 < 0 || rot.axrot2 < 0) {
            rot.crota = 0.0;
            return rot;
        }

        // Two estimates of CROTA, which must agree; the CDELT values of the rotation axes follow
        // from their mean.
        double rhoA;
        if (cdlatLon > 0.0) {
            rhoA = Math.atan2(cdlatLon, rot.cdelt[rot.axrot1]);
        } else if (cdlatLon == 0.0) {
            rhoA = 0.0;
        } else {
            rhoA = Math.atan2(-cdlatLon, -rot.cdelt[rot.axrot1]);
        }
        double rhoB;
        if (cdlonLat > 0.0) {
            rhoB = Math.atan2(cdlonLat, -rot.cdelt[rot.axrot2]);
        } else if (cdlonLat == 0.0) {
            rhoB = 0.0;
        } else {
            rhoB = Math.atan2(-cdlonLat, rot.cdelt[rot.axrot2]);
        }
        if (Math.abs(Pal.drange(rhoA - rhoB)) < 1.0e-2) {
            double crota = 0.5 * (Pal.dranrm(rhoA) + Pal.dranrm(rhoB));
            double coscro = Math.cos(crota);
            double sincro = Math.sin(crota);
            if (Math.abs(coscro) > Math.abs(sincro)) {
                rot.cdelt[rot.axrot2] /= coscro;
                rot.cdelt[rot.axrot1] /= coscro;
            } else {
                rot.cdelt[rot.axrot2] = -cdlonLat / sincro;
                rot.cdelt[rot.axrot1] = cdlatLon / sincro;
            }
            rot.crota = crota * AST__DR2D;
        } else {
            rot.crota = BAD;
        }
        return rot;
    }

    /**
     * Whether the reference equinox {@code epoch[0]} ({@link #BAD} if unset) agrees with {@code
     * radesys} as the IAU 1984 rule has it: FK4 before 1984 and FK5 after. ICRS, or any other
     * system, does not. An unset equinox is set to the default of the system, 1950 for FK4 and 2000
     * for FK5. With no {@code radesys}, any equinox agrees.
     */
    private static boolean epochAgrees(String radesys, double[] epoch) {
        if (radesys == null) {
            return true;
        }
        boolean ok = !"ICRS".equals(radesys);
        if (epoch[0] == BAD) {
            if ("FK4".equals(radesys)) {
                epoch[0] = 1950.0;
            } else if ("FK5".equals(radesys)) {
                epoch[0] = 2000.0;
            } else {
                ok = false;
            }
        } else if ("FK4".equals(radesys)) {
            if (epoch[0] >= 1984.0) {
                ok = false;
            }
        } else if ("FK5".equals(radesys)) {
            if (epoch[0] < 1984.0) {
                ok = false;
            }
        } else {
            ok = false;
        }
        return ok;
    }

    /**
     * The cards both writers write for each axis: CRPIX, CRVAL (the spectral axis's in the AIPS
     * units), CTYPE (the celestial and spectral axes with the values chosen for them), CDELT and
     * CUNIT. Writing CRPIX, CRVAL and CTYPE stops at the first axis without one, or with a {@code
     * -TAB} CTYPE, giving false, after which CDELT and CUNIT are still written, as in C.
     */
    private static boolean writeAxes(
            FitsCardList cards,
            FitsStore st,
            int naxis,
            int axlon,
            int axlat,
            int axspec,
            String lontype,
            String lattype,
            Spectral spec,
            double[] cdelt,
            int rnd) {
        boolean ok = true;
        for (int j = 0; j < naxis && ok; j++) {
            Double val = at(st.crpix, j);
            if (val == null) {
                ok = false;
            } else {
                setFloat(cards, "CRPIX" + (j + 1), val, "Reference pixel on axis " + (j + 1), rnd);
            }
        }
        for (int i = 0; i < naxis && ok; i++) {
            Double val = at(st.crval, i);
            if (val == null) {
                ok = false;
            } else {
                double v = val;
                if (i == axspec) {
                    v *= spec.factor;
                }
                setFloat(cards, "CRVAL" + (i + 1), v, "Value at ref. pixel on axis " + (i + 1), rnd);
            }
        }
        for (int i = 0; i < naxis && ok; i++) {
            String cval;
            if (i == axlat) {
                cval = lattype;
            } else if (i == axlon) {
                cval = lontype;
            } else if (i == axspec) {
                cval = spec.type;
            } else {
                cval = at(st.ctype, i);
            }
            if (cval != null && (cval.length() < 5 || !"-TAB".equals(cval.substring(4)))) {
                String comm = at(st.ctypeCom, i);
                if (comm == null) {
                    comm = "Type of co-ordinate on axis " + (i + 1); // codespell:ignore
                }
                setString(cards, "CTYPE" + (i + 1), cval, comm, rnd);
            } else {
                ok = false;
            }
        }

        if (axspec != -1) {
            cdelt[axspec] *= spec.factor;
        }
        for (int i = 0; i < naxis; i++) {
            setFloat(cards, "CDELT" + (i + 1), cdelt[i], "Pixel size", rnd);
        }
        for (int i = 0; i < naxis; i++) {
            String cval = at(st.cunit, i);
            if (cval != null) {
                if (i == axspec) {
                    cval = spec.unit;
                }
                setString(cards, "CUNIT" + (i + 1), cval, "Units for axis " + (i + 1), rnd);
            }
        }
        return ok;
    }

    /**
     * The date of observation as DATE-OBS. A read leaves any MJD-OBS card in the header, since
     * other software may use it, so such a card is set to the same date.
     */
    private static void writeDate(FitsCardList cards, FitsStore st, int rnd) {
        if (st.mjdobs != null) {
            double val = st.mjdobs;
            setString(cards, "DATE-OBS", FitsDates.mjdToDateStr(val), "Date of observation", rnd);
            if (cards.hasCard("MJD-OBS")) {
                setFloat(cards, "MJD-OBS", val, "Modified Julian Date of observation", rnd);
            }
        }
    }

    /** The rest frequency, as RESTFREQ, when there is a spectral axis. */
    private static void writeRestFrequency(FitsCardList cards, FitsStore st, int axspec, int rnd) {
        if (axspec >= 0 && st.restfrq != null) {
            setFloat(cards, "RESTFREQ", st.restfrq, "[Hz] Rest frequency", rnd);
        }
    }

    /** Whether a longitude CTYPE is one FITS-AIPS can hold: RA, galactic or ecliptic longitude. */
    private static boolean isAipsLongitude(String lontype) {
        return lontype.startsWith("RA--") || lontype.startsWith("GLON") || lontype.startsWith("ELON");
    }

    /**
     * The first four characters of the AIPS CTYPE of a spectral axis whose FITS-WCS CTYPE is {@code
     * cval}, or null if AIPS has none: {@code FREQ}, {@code VELO} for {@code VRAD} and {@code FELO}
     * for {@code VOPT-F2W}, each matched as C's {@code strncmp} over the length of {@code cval}
     * without trailing spaces.
     */
    private static String specType(String cval) {
        if (FitsStore.namesStartOf(cval, "FREQ")) {
            return "FREQ";
        } else if (FitsStore.namesStartOf(cval, "VRAD")) {
            return "VELO";
        } else if (FitsStore.namesStartOf(cval, "VOPT-F2W")) {
            return "FELO";
        }
        return null;
    }

    /**
     * The end of the AIPS CTYPE of a spectral axis for the SPECSYS {@code cval}, or null if AIPS has
     * none: {@code -LSR} for LSRK, {@code -LSD} for LSRD, {@code -HEL} for BARYCENT and {@code
     * -GEO} for GEOCENTR, matched as {@link #specType} matches.
     */
    private static String standardOfRest(String cval) {
        if (FitsStore.namesStartOf(cval, "LSRK")) {
            return "-LSR";
        } else if (FitsStore.namesStartOf(cval, "LSRD")) {
            return "-LSD";
        } else if (FitsStore.namesStartOf(cval, "BARYCENT")) {
            return "-HEL";
        } else if (FitsStore.namesStartOf(cval, "GEOCENTR")) {
            return "-GEO";
        }
        return null;
    }

    /**
     * Sets the AIPS units of a spectral axis whose units are {@code cval}, Hz or m/s, and the
     * factor converting to them, in {@code spec}; false if AIPS cannot hold the units.
     */
    private static boolean specUnit(String cval, Spectral spec) {
        // MODERN-JAVA(21): switch expression
        switch (cval) {
            case "Hz":
                spec.unit = "HZ";
                spec.factor = 1.0;
                return true;
            case "kHz":
                spec.unit = "HZ";
                spec.factor = 1.0e3;
                return true;
            case "MHz":
                spec.unit = "HZ";
                spec.factor = 1.0e6;
                return true;
            case "GHz":
                spec.unit = "HZ";
                spec.factor = 1.0e9;
                return true;
            case "m/s":
                spec.unit = "m/s";
                spec.factor = 1.0;
                return true;
            case "km/s":
                spec.unit = "m/s";
                spec.factor = 1.0e3;
                return true;
            default:
                return false;
        }
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
