package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The celestial axes of a FITS-WCS description being written: C's {@code CelestialAxes}, {@code
 * SplitMap} and {@code SplitMap2} (fitschan.c).
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/celestial_axes.rs}, following fitschan.c.
 * Describing celestial axes with the {@code -TAB} algorithm needs a LutMap, which this library
 * does not have.
 */
final class FitsCelestialAxes {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** C's {@code AST__DD2R}: degrees to radians. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code AST__DR2D}: radians to degrees. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code WCSLIB_MXPAR}: the number of projection parameters searched. */
    private static final int WCSLIB_MXPAR = 100;

    /** C's {@code AST__DBL_DIG}: the significant digits C's {@code %.*g} gives a double. */
    private static final int AST__DBL_DIG = 17;

    private FitsCelestialAxes() {}

    /**
     * A Mapping split about its projection, as C's {@code SplitMap} and {@code SplitMap2} return
     * it in {@code map1}, {@code map2} and {@code map3}.
     */
    static final class Split {
        /** The Mappings before the projection, from pixel to intermediate world coordinates. */
        final Mapping map1;

        /** The WcsMap with FITSProj set. */
        final WcsMap map2;

        /** The Mappings after the projection, from native spherical to world coordinates. */
        final Mapping map3;

        Split(Mapping map1, WcsMap map2, Mapping map3) {
            this.map1 = map1;
            this.map2 = map2;
            this.map3 = map3;
        }
    }

    /**
     * C's {@code CelestialAxes}: finds the celestial axes of the current Frame of {@code fs}, and
     * if the pixel to world Mapping describes them with a projection stores the FITS-WCS values
     * describing them other than CRPIX, PC and CDELT (CRVAL, the projection parameters, CTYPE,
     * CNAME, RADESYS, EQUINOX, MJD-OBS, LONPOLE, LATPOLE and the others {@link FitsSkySys#skySys}
     * stores), and returns the Mapping from the current Frame to intermediate world coordinates,
     * which embodies the rest. That Mapping has an output for every current Frame axis, and passes
     * the non-celestial axes unchanged.
     *
     * <p>The projection is the one {@link #splitMap} finds, unless the FitsChan's ForceTab is set.
     * With FitsAxisOrder {@code <auto>}, when the rotation from world to intermediate world
     * coordinates exchanges the celestial axes (a LONPOLE of 90 degrees, for example), their FITS
     * axes, and the CRVAL, CTYPE, CNAME and projection parameters stored for them, are exchanged
     * to keep each intermediate world axis close to the pixel axis of the same number.
     *
     * @param fc the FitsChan
     * @param fs the FrameSet, whose base Frame is pixel coordinates and current Frame world
     *     coordinates
     * @param dim the number of pixels along each pixel axis, {@code AST__BAD} where unknown
     * @param wperm the zero-based FITS axis describing each current Frame axis; the celestial
     *     axes' are exchanged if their intermediate world axes are
     * @param s the coordinate version character
     * @param store the store
     * @param axisDone a flag for each current Frame axis, set for each celestial axis
     * @param isoff as {@link FitsSkySys#skySys} takes it
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return the Mapping from the current Frame to intermediate world coordinates for the
     *     celestial axes, or a UnitMap if they cannot be described
     * @throws UnsupportedOperationException if C would describe the celestial axes with the {@code
     *     -TAB} algorithm (TabOK positive, and no projection or ForceTab set), which needs LutMap
     */
    static Mapping celestialAxes(
            FitsChan fc,
            FrameSet fs,
            double[] dim,
            int[] wperm,
            char s,
            FitsStore store,
            boolean[] axisDone,
            int isoff,
            String method,
            String cls) {
        Mapping ret = null;
        Frame wcsfrm = fs.getFrame(FrameSet.AST__CURRENT);
        int nwcs = fs.getNout();

        // The celestial axes of the WCS Frame, and the SkyFrame they belong to; MakeFitsFrameSet
        // ensures there is at most one SkyFrame.
        SkyFrame skyfrm = null;
        int ilon = -1;
        int ilat = -1;
        int[] paxis = new int[1];
        for (int iax = 0; iax < nwcs; iax++) {
            Frame pframe = wcsfrm.primaryFrame(iax + 1, paxis);
            if (FitsMakeIntWorld.isASkyFrame(pframe)) {
                if (skyfrm == null) {
                    skyfrm = (SkyFrame) pframe;
                }
                if (paxis[0] == 1) {
                    ilon = iax;
                } else {
                    ilat = iax;
                }
                axisDone[iax] = true;
            }
        }

        if (ilon != -1 && ilat != -1) {
            int fitsIlon = wperm[ilon];
            int fitsIlat = wperm[ilat];

            // The projection plane coordinates of the fiducial point.
            double[] ppcfid = new double[nwcs];

            Mapping map =
                    fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();

            // The table version for the -TAB algorithm: TabOK, if positive.
            int extver = fc.getTabOK();

            Split split = fc.getForceTab() == 0 ? splitMap(map, map.getInvert(), ilon, ilat) : null;
            if (split != null) {
                WcsMap map2 = split.map2;
                Mapping map3 = split.map3;

                // The axes of latitude and longitude within the SkyFrame, which may be permuted.
                int skylataxis = skyfrm.getLatAxis() - 1;
                int skylonaxis = skyfrm.getLonAxis() - 1;

                // The reference point: the fiducial point in native spherical coordinates
                // transformed to world coordinates by map3.
                double[] fid = FitsFiducial.getFiducialWcs(map2, map3, ilon, ilat);
                if (fid != null) {
                    double[] skyfid = new double[2];
                    skyfid[skylonaxis] = fid[0];
                    skyfid[skylataxis] = fid[1];

                    // The longitude and latitude outputs of the WcsMap, which permutations in
                    // map3 may make differ from ilon and ilat.
                    int axlon = map2.getWcsAxis(1) - 1;
                    int axlat = map2.getWcsAxis(2) - 1;

                    // CRVAL, normalised, in degrees.
                    if (zeroAng(skyfid[0])) {
                        skyfid[0] = 0.0;
                    }
                    if (zeroAng(skyfid[1])) {
                        skyfid[1] = 0.0;
                    }
                    skyfrm.norm(skyfid);
                    store.crval[fitsIlon] = AST__DR2D * skyfid[skylonaxis];
                    store.crval[fitsIlat] = AST__DR2D * skyfid[skylataxis];

                    // The projection parameters, except 3 and 4 of the longitude axis, which hold
                    // LONPOLE and LATPOLE, unless the projection is TPN, whose parameters are
                    // polynomial terms.
                    boolean tpn = map2.getWcsType() == WcsMap.AST__TPN;
                    for (int m = 0; m < WCSLIB_MXPAR; m++) {
                        if (map2.testPV(axlon, m) && (m < 3 || m > 4 || tpn)) {
                            double pv = map2.getPV(axlon, m);
                            if (pv != AST__BAD) {
                                store.setPv(fitsIlon, m, pv);
                            }
                        }
                        if (map2.testPV(axlat, m)) {
                            double pv = map2.getPV(axlat, m);
                            if (pv != AST__BAD) {
                                store.setPv(fitsIlat, m, pv);
                            }
                        }
                    }

                    // A non-zero PVi_0 on the longitude axis shifts projection plane coordinates
                    // to intermediate world coordinates by those of the fiducial point, so that
                    // CRPIX maps onto CRVAL (FITS-WCS paper II); not for TPN.
                    if (!tpn && map2.getPV(axlon, 0) != 0.0) {
                        double[] ppc = FitsFiducial.getFiducialPpc(map2);
                        ppcfid[ilon] = ppc[0];
                        ppcfid[ilat] = ppc[1];
                        if (ppcfid[ilon] == AST__BAD) {
                            ppcfid[ilon] = 0.0;
                        }
                        if (ppcfid[ilat] == AST__BAD) {
                            ppcfid[ilat] = 0.0;
                        }
                        ppcfid[ilon] *= AST__DR2D;
                        ppcfid[ilat] *= AST__DR2D;
                    }

                    FitsSkySys.skySys(
                            fc, skyfrm, 1, map2.getWcsType(), store, fitsIlon, fitsIlat, s, isoff, method, cls);
                    FitsSkyPole.skyPole(map2, map3, ilon, ilat, wperm, s, store, method, cls);

                    // WcsNative prefers parameters 3 and 4 of the longitude axis to the LONPOLE
                    // and LATPOLE just stored, so they are cleared in a copy (except for TPN).
                    WcsMap map2b;
                    if (map2.getWcsType() != WcsMap.AST__TPN) {
                        map2b = (WcsMap) map2.copy();
                        map2b.clearPV(axlon, 3);
                        map2b.clearPV(axlon, 4);
                    } else {
                        map2b = map2;
                    }

                    // The Mapping from intermediate world coordinates to world coordinates: the
                    // shift to projection plane coordinates, degrees to radians on the celestial
                    // axes, the projection, and the rotation to celestial coordinates.
                    Mapping tmap0 = new ShiftMap(ppcfid);
                    double[] mat = new double[nwcs];
                    Arrays.fill(mat, 1.0);
                    mat[ilon] = AST__DD2R;
                    mat[ilat] = AST__DD2R;
                    Mapping tmap1 = new MatrixMap(nwcs, nwcs, mat);
                    Mapping tmap2 = FitsWcsCelestial.wcsNative(store, map2b, fitsIlon, fitsIlat, null, method, cls);
                    Mapping tmap3 = new CmpMap(map2b, tmap2, true);

                    // A UnitMap for WCS axes beyond those of the projection.
                    int nwcsmap = map3.getNin();
                    if (nwcsmap < nwcs) {
                        tmap3 = new CmpMap(tmap3, new UnitMap(nwcs - nwcsmap), false);
                        nwcsmap = nwcs;
                    }

                    // Between two PermMaps selecting the axes, when a PermMap in the pixel to
                    // world Mapping selects a subset or superset of the WCS axes, or permutes the
                    // celestial ones.
                    if (nwcsmap != nwcs || ilon != axlon || ilat != axlat) {
                        tmap3 = permuted(tmap3, nwcs, nwcsmap, ilon, ilat, axlon, axlat);
                    }

                    // The Mapping from world to intermediate world coordinates.
                    Mapping tmap4 = new CmpMap(tmap0, tmap1, true);
                    ret = new CmpMap(tmap4, tmap3, true);
                    ret.invert();

                    // With the axis order chosen automatically, the FITS axes of the celestial
                    // axes are exchanged if the pixel to intermediate world Mapping exchanges
                    // them.
                    if (AstObject.chrMatch(fc.getFitsAxisOrder(), "<auto>")) {
                        Mapping pixToIwc = CmpMap.transientCmpMap(map, ret, true);
                        int[] tperm = new int[nwcs];
                        if (!FitsWorldAxes.worldAxes(fc, pixToIwc, dim, tperm)) {
                            ret = null;
                        }
                        if (ret != null && fitsIlon == tperm[ilat] && fitsIlat == tperm[ilon]) {
                            wperm[ilon] = fitsIlat;
                            wperm[ilat] = fitsIlon;
                            swapAxes(store, fitsIlon, fitsIlat);
                        }
                    }
                }

            } else if (extver > 0) {
                // C's IsMapTab2D describes the axes with look-up tables.
                throw new UnsupportedOperationException(
                        method + "(" + cls + "): describing celestial axes with the -TAB algorithm needs LutMap");
            }
        }

        if (ret != null) {
            return ret.simplify();
        }
        return new UnitMap(nwcs);
    }

    /**
     * {@code tmap3}, with {@code nwcsmap} inputs and outputs, between a PermMap from the {@code
     * nwcs} WCS axes, taking WCS axes {@code ilon} and {@code ilat} to the WcsMap's {@code axlon}
     * and {@code axlat} and the rest in order, and its inverse.
     */
    private static Mapping permuted(Mapping tmap3, int nwcs, int nwcsmap, int ilon, int ilat, int axlon, int axlat) {
        int[] inperm = new int[nwcs];
        int[] outperm = new int[nwcsmap];

        // The WcsMap's longitude and latitude axes are the WCS Frame's; the rest are assigned in
        // turn, the WcsMap being a UnitMap on them.
        Arrays.fill(inperm, -1);
        inperm[ilon] = axlon;
        inperm[ilat] = axlat;
        int iax = 0;
        for (int i = 0; i < nwcs; i++) {
            while (iax == axlon || iax == axlat) {
                iax++;
            }
            if (inperm[i] == -1) {
                inperm[i] = iax++;
            }
        }

        Arrays.fill(outperm, -1);
        outperm[axlon] = ilon;
        outperm[axlat] = ilat;
        iax = 0;
        for (int i = 0; i < nwcsmap; i++) {
            while (iax == ilon || iax == ilat) {
                iax++;
            }
            if (outperm[i] == -1) {
                outperm[i] = iax++;
            }
        }

        PermMap pm = PermMap.fromInternal(nwcs, inperm, nwcsmap, outperm, new double[] {AST__BAD});
        Mapping tmap4 = new CmpMap(pm, tmap3, true);
        pm.invert();
        return new CmpMap(tmap4, pm, true);
    }

    /**
     * Exchanges the CRVAL values, and the projection parameters up to the highest stored, of FITS
     * axes {@code a} and {@code b}, and their CTYPE and CNAME values when both are set.
     */
    private static void swapAxes(FitsStore store, int a, int b) {
        Double val = store.crval[b];
        store.crval[b] = store.crval[a];
        store.crval[a] = val;

        String cval = store.ctype[b];
        if (cval != null && store.ctype[a] != null) {
            store.ctype[b] = store.ctype[a];
            store.ctype[a] = cval;
        }

        cval = store.cname[b];
        if (cval != null && store.cname[a] != null) {
            store.cname[b] = store.cname[a];
            store.cname[a] = cval;
        }

        int maxm = store.maxPvM();
        for (int m = 0; m <= maxm; m++) {
            val = pv(store, b, m);
            store.setPv(b, m, pv(store, a, m));
            store.setPv(a, m, val);
        }
    }

    /** Projection parameter {@code m} of FITS axis {@code i}, null when not stored. */
    private static Double pv(FitsStore store, int i, int m) {
        return m < store.pv[i].length ? store.pv[i][m] : null;
    }

    /** C's {@code ZEROANG}: an angle less than 1.0E-9 in magnitude. */
    private static boolean zeroAng(double a) {
        return Math.abs(a) < 1.0e-9;
    }

    /**
     * C's {@code SplitMap}: {@code map}, applied with Invert value {@code invert}, as three
     * Mappings in series, the second an inverted WcsMap with FITSProj set, with no such WcsMap in
     * the others. Failing that, a linear Mapping is given a CAR projection, with a ShiftMap moving
     * the origin of the longitude axis of intermediate world coordinates to the pixel origin (the
     * latitude axis cannot be moved, since readers before FITS-WCS could not handle the rotation
     * that would need); and a Mapping whose celestial outputs {@code ilon} and {@code ilat} are
     * constant is given the Mappings of a TAN header with those values at its reference point.
     *
     * @return the three Mappings, or null if none of these applies
     */
    static Split splitMap(Mapping map, boolean invert, int ilon, int ilat) {
        Split ret = null;
        Split split = splitMap2(map, invert);
        if (split != null
                && split.map2.getInvert()
                && splitMap2(split.map1, split.map1.getInvert()) == null
                && splitMap2(split.map3, split.map3.getInvert()) == null) {
            ret = split;
        }

        // A linear Mapping: a CAR projection.
        if (ret == null && map.isLinear()) {
            int nwcs = map.getNout();
            int npix = map.getNin();
            double[][] iwc = map.transform(new double[npix][1], 1, true);
            double[] iwcOrigin = new double[nwcs];
            for (int i = 0; i < nwcs; i++) {
                iwcOrigin[i] = i != ilon ? 0.0 : iwc[i][0] * -1;
            }
            Mapping mapa = new ShiftMap(iwcOrigin);
            Mapping map1 = new CmpMap(map, mapa, true);
            WcsMap map2 = new WcsMap(nwcs, WcsMap.AST__CAR, ilon + 1, ilat + 1);
            map2.setInvert(true);
            mapa.invert();
            ret = new Split(map1, map2, mapa);
        }

        // Constant celestial outputs: two pixel positions with the same good longitude and
        // latitude.
        if (ret == null) {
            int npix = map.getNin();
            double[][] ptr1 = new double[npix][2];
            for (int i = 0; i < npix; i++) {
                ptr1[i][0] = 1.0;
                ptr1[i][1] = 1000.0;
            }
            double[][] ptr2 = map.transform(ptr1, 2, true);
            if (ptr2[ilon][0] == ptr2[ilon][1]
                    && ptr2[ilon][0] != AST__BAD
                    && ptr2[ilat][0] == ptr2[ilat][1]
                    && ptr2[ilat][0] != AST__BAD) {
                ret = constantSplit(map, ilon, ilat, ptr2[ilon][0], ptr2[ilat][0]);
            }
        }
        return ret;
    }

    /**
     * The Mappings of C's {@code SplitMap} for a Mapping {@code map} whose outputs {@code ilon}
     * and {@code ilat} are the constants {@code lon} and {@code lat}: those of a FITS-WCS header,
     * read by a FitsChan, describing a TAN projection with them at its reference point, the
     * keywords that do not affect them given arbitrary values; the Mapping before the projection
     * is {@code map} followed by a ShiftMap subtracting the constants, giving zero on the
     * celestial axes. Null if the header gives no FrameSet.
     */
    private static Split constantSplit(Mapping map, int ilon, int ilat, double lon, double lat) {
        int nwcs = map.getNout();
        FitsChan fc = new FitsChan();
        for (int i = 0; i < nwcs; i++) {
            fc.putFits("CRPIX" + (i + 1) + "  = 0", false);
            fc.putFits("CDELT" + (i + 1) + "  = 0.0003", false);
            if (i == ilon) {
                fc.putFits("CTYPE" + (i + 1) + "  = 'RA---TAN'", false);
            } else if (i == ilat) {
                fc.putFits("CTYPE" + (i + 1) + "  = 'DEC--TAN'", false);
            } else {
                fc.putFits("CTYPE" + (i + 1) + "  = 'DUMMY'", false);
            }
            if (i == ilon) {
                fc.putFits(
                        "CRVAL" + (i + 1) + "  = " + CFormat.format("%." + AST__DBL_DIG + "g", AST__DR2D * lon), false);
            } else if (i == ilat) {
                fc.putFits(
                        "CRVAL" + (i + 1) + "  = " + CFormat.format("%." + AST__DBL_DIG + "g", AST__DR2D * lat), false);
            } else {
                fc.putFits("CRVAL" + (i + 1) + "  = 0.0", false);
            }
        }
        fc.cards().clearCard();
        AstObject tfs = fc.read();
        if (tfs == null) {
            return null;
        }
        Mapping tmap2 = ((FrameSet) tfs).getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Split tsplit = splitMap(tmap2, tmap2.getInvert(), 0, 1);
        if (tsplit == null) {
            // C goes on to annul the null pre-projection Mapping SplitMap gave, which is an error.
            throw Ast.err(
                    AstException.AST__INTER,
                    "SplitMap(FitsChan): AST internal error; no projection found in a TAN header.");
        }
        double[] w1 = new double[nwcs];
        w1[ilon] = -lon;
        w1[ilat] = -lat;
        Mapping map1 = new CmpMap(map, new ShiftMap(w1), true);
        return new Split(map1, tsplit.map2, tsplit.map3);
    }

    /**
     * C's {@code SplitMap2}: {@code map}, applied with Invert value {@code invert}, as three
     * Mappings in series, the second a WcsMap with FITSProj set, inverted or not; the others may
     * hold WcsMaps without FITSProj. A CmpMap is searched through its Mappings in series or in
     * parallel, any combination of the two, the first WcsMap found being used. For a WcsMap found
     * in parallel with other Mappings, a WcsMap of the same projection, with as many axes as the
     * parallel Mappings have outputs, its longitude and latitude axes and projection parameters
     * moved to the WcsMap's place among them, is made, and is inverted whatever the Invert value
     * of the WcsMap found; it has none of that WcsMap's other attributes.
     *
     * <p>A CmpMap with Ident set, which C's {@code astMapList} gives back whole for either
     * decomposition, is taken to hold no WcsMap: C searches it again without end.
     *
     * @return the three Mappings, copies of those in {@code map}, the first and last UnitMaps
     *     where there are none; or null if no WcsMap with FITSProj set is found
     */
    static Split splitMap2(Mapping map, boolean invert) {
        String cls = map.astClass();
        if ("CmpMap".equals(cls)) {
            List<Mapping> maps = new ArrayList<>();
            List<Boolean> inverts = new ArrayList<>();
            map.mapList(true, invert, maps, inverts);
            if (maps.size() > 1) {
                return splitSeries(map, invert, maps, inverts);
            }
            maps.clear();
            inverts.clear();
            map.mapList(false, invert, maps, inverts);
            @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
            boolean whole = maps.size() == 1 && maps.get(0) == map;
            if (whole) {
                return null;
            }
            return splitParallel(maps, inverts);
        } else if ("WcsMap".equals(cls) && ((WcsMap) map).getFITSProj()) {
            int naxes = map.getNin();
            WcsMap map2 = (WcsMap) map.copy();
            map2.setInvert(invert);
            return new Split(new UnitMap(naxes), map2, new UnitMap(naxes));
        }
        return null;
    }

    /** {@link #splitMap2} for a CmpMap {@code map} that is {@code maps} in series. */
    private static Split splitSeries(Mapping map, boolean invert, List<Mapping> maps, List<Boolean> inverts) {
        Mapping map1 = invert == map.getInvert() ? new UnitMap(map.getNin()) : new UnitMap(map.getNout());
        WcsMap map2 = null;
        Mapping map3 = null;
        for (int imap = 0; imap < maps.size(); imap++) {
            if (map2 == null) {
                Split sub = splitMap2(maps.get(imap), inverts.get(imap));
                Mapping mapa;
                if (sub != null) {
                    mapa = sub.map1;
                    map2 = sub.map2;
                    map3 = sub.map3;
                } else {
                    mapa = copyWithInvert(maps.get(imap), inverts.get(imap));
                }
                map1 = new CmpMap(map1, mapa, true);
            } else {
                map3 = new CmpMap(map3, copyWithInvert(maps.get(imap), inverts.get(imap)), true);
            }
        }
        return map2 == null ? null : new Split(map1, map2, map3);
    }

    /** {@link #splitMap2} for a CmpMap that is {@code maps} in parallel. */
    private static Split splitParallel(List<Mapping> maps, List<Boolean> inverts) {
        Mapping map1 = null;
        Mapping map3 = null;
        WcsMap mapb = null;
        int axis = 0;
        int wcsaxis = -1;
        for (int imap = 0; imap < maps.size(); imap++) {
            Split sub = mapb == null ? splitMap2(maps.get(imap), inverts.get(imap)) : null;
            Mapping mapa;
            Mapping mapc;
            if (sub != null) {
                mapb = sub.map2;
                wcsaxis = axis;
                mapa = sub.map1;
                mapc = sub.map3;
            } else {
                mapa = copyWithInvert(maps.get(imap), inverts.get(imap));
                mapc = new UnitMap(mapa.getNout());
            }
            axis += mapa.getNout();
            map1 = map1 != null ? new CmpMap(map1, mapa, false) : mapa;
            map3 = map3 != null ? new CmpMap(map3, mapc, false) : mapc;
        }
        if (mapb == null) {
            return null;
        }
        int axlat = mapb.getWcsAxis(2) - 1;
        int axlon = mapb.getWcsAxis(1) - 1;
        WcsMap map2 = new WcsMap(axis, mapb.getWcsType(), axlon + wcsaxis + 1, axlat + wcsaxis + 1);
        for (int i = 0; i < mapb.getNin(); i++) {
            for (int m = 0; m < WCSLIB_MXPAR; m++) {
                if (mapb.testPV(i, m)) {
                    double pv = mapb.getPV(i, m);
                    if (pv != AST__BAD) {
                        map2.setPV(i + wcsaxis, m, pv);
                    }
                }
            }
        }
        map2.invert();
        return new Split(map1, map2, map3);
    }

    /** A copy of {@code map} with Invert value {@code invert}. */
    private static Mapping copyWithInvert(Mapping map, boolean invert) {
        Mapping copy = (Mapping) map.copy();
        copy.setInvert(invert);
        return copy;
    }
}
