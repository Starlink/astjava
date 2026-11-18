package uk.ac.starlink.ast;

/**
 * C's {@code OtherAxes} (fitschan.c): the FITS-WCS description of the world axes that neither the
 * celestial nor the spectral axes took, with the helpers it uses ({@code LogAxis}, {@code
 * AddUnitMaps}, {@code SetAlgCode}).
 *
 * <p>Each such axis is described, in order of preference, as linear, with the {@code -LOG}
 * algorithm, or, when TabOK is positive, with the {@code -TAB} algorithm; ForceTab passes over the
 * first two. An axis none of them describes is given the linear description, which makes the
 * pixel to intermediate world Mapping non-linear, so that {@link FitsMakeIntWorld#makeIntWorld}
 * rejects it. The {@code -LOG} description needs a MathMap and the {@code -TAB} description a
 * FitsTable, neither of which this library has, so where C would make one an {@link
 * UnsupportedOperationException} is thrown.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/other_axes.rs} and {@code log_axis.rs},
 * following fitschan.c.
 */
final class FitsOtherAxes {

    private static final double AST__BAD = AstObject.AST__BAD;

    private FitsOtherAxes() {}

    /**
     * C's {@code OtherAxes}: stores CRVAL, CTYPE, its comment, CNAME and CUNIT for each axis of the
     * current Frame of {@code fs} not yet flagged in {@code axisDone}, flags it, and returns the
     * Mapping from the current Frame to intermediate world coordinates for those axes, which
     * passes the other axes unchanged.
     *
     * <p>The CRVAL of an axis is its element of {@code crvals}, if that is given and good, or else
     * the world value at the centre pixel ({@code floor(dim/2)} on each pixel axis, 1 where the
     * dimension is unknown); at an axis with neither, the axes from there on are left undescribed.
     * The CTYPE is the axis Symbol ({@code AXIS<n>} if it is blank), with {@code -LOG} or {@code
     * -TAB} for those algorithms; its comment and CNAME are the Label when that is set and differs
     * from the Symbol, the comment otherwise being C's default naming the axis number; and
     * CUNIT is the Unit when that is set.
     *
     * @param fc the FitsChan, whose TabOK and ForceTab attributes are used
     * @param fs the FrameSet, whose base Frame is pixel coordinates and current Frame world
     *     coordinates
     * @param dim the number of pixels along each pixel axis, {@code AST__BAD} where unknown
     * @param wperm the zero-based FITS axis describing each current Frame axis
     * @param s the coordinate version character
     * @param store the store
     * @param crvals the reference value of each current Frame axis, {@code AST__BAD} where there
     *     is none, or null for none at all
     * @param axisDone a flag for each current Frame axis, set for each axis described
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return the Mapping from the current Frame to intermediate world coordinates
     * @throws UnsupportedOperationException where C would describe an axis with the {@code -LOG}
     *     algorithm (an axis that is not linear but whose logarithm is, with a CRVAL other than
     *     zero, and ForceTab not set), which needs MathMap, or with the {@code -TAB} algorithm,
     *     which needs FitsTable
     */
    static Mapping otherAxes(
            FitsChan fc,
            FrameSet fs,
            double[] dim,
            int[] wperm,
            char s,
            FitsStore store,
            double[] crvals,
            boolean[] axisDone,
            String method,
            String cls) {
        Mapping ret = null;
        int nwcs = fs.getNaxes();

        int nother = 0;
        for (int iax = 0; iax < nwcs; iax++) {
            if (!axisDone[iax]) {
                nother++;
            }
        }

        if (nother > 0) {
            Frame wcsfrm = fs.getFrame(FrameSet.AST__CURRENT);
            Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            int npix = fs.getNin();
            nwcs = fs.getNout();

            double[] lbndP = new double[npix];
            double[] ubndP = new double[npix];
            for (int iax = 0; iax < npix; iax++) {
                lbndP[iax] = 1.0;
                ubndP[iax] = dim[iax] != AST__BAD ? dim[iax] : 500;
            }

            // The world coordinates of the central pixel.
            double[][] ptr1 = new double[npix][1];
            for (int iax = 0; iax < npix; iax++) {
                ptr1[iax][0] = dim[iax] != AST__BAD ? Math.floor(0.5 * dim[iax]) : 1.0;
            }
            double[][] ptr2 = map.transform(ptr1, 1, true);

            for (int iax = 0; iax < nwcs; iax++) {
                if (axisDone[iax]) {
                    continue;
                }
                int fitsI = wperm[iax];

                double crval = crvals != null ? crvals[iax] : AST__BAD;
                if (crval == AST__BAD) {
                    crval = ptr2[iax][0];
                }
                if (crval == AST__BAD) {
                    break;
                }
                store.crval[fitsI] = crval;

                boolean logAxis = false;
                int extver = fc.getTabOK();
                boolean forceTab = fc.getForceTab() != 0;

                Mapping axmap = null;
                if (!forceTab && FitsIsMapLinear.isMapLinear(map, lbndP, ubndP, iax, null)) {
                    axmap = addUnitMaps(new ShiftMap(new double[] {-crval}), iax, nwcs);
                } else {
                    if (!forceTab) {
                        axmap = logAxis(map, iax, nwcs, lbndP, ubndP, crval);
                        logAxis = axmap != null;
                    }
                    if (axmap == null && extver > 0) {
                        // The -TAB algorithm: where IsMapTab1D gives a Mapping, C stores the table
                        // version in the header of the FitsTable holding the table, and the column
                        // names, so the description needs FitsTable.
                        Mapping tmap0 = FitsMapTab.isMapTab1D(
                                map, 1.0, null, wcsfrm, dim, iax, fitsI, new int[1], new int[1], new int[1]);
                        if (tmap0 != null) {
                            throw new UnsupportedOperationException(
                                    "OtherAxes(FitsChan): the -TAB description of FITS-WCS axis " + (fitsI + 1)
                                            + " needs FitsTable");
                        }
                    }
                }

                // An axis no algorithm describes is described as linear, which gives a non-linear
                // pixel to intermediate world Mapping that MakeIntWorld rejects.
                if (axmap == null) {
                    axmap = addUnitMaps(new ShiftMap(new double[] {-crval}), iax, nwcs);
                }

                ret = ret != null ? new CmpMap(ret, axmap, true) : axmap;

                String sym = wcsfrm.getSymbol(iax + 1);
                String lab = wcsfrm.getLabel(iax + 1);
                String buf = !sym.isEmpty() ? sym : "AXIS" + (iax + 1);
                if (logAxis) {
                    buf = setAlgCode(buf, "-LOG");
                }
                store.ctype[fitsI] = buf;

                if (!lab.isEmpty() && wcsfrm.testLabel(iax + 1) && !sym.equals(lab)) {
                    store.ctypeCom[fitsI] = lab;
                    store.cname[fitsI] = lab;
                } else {
                    store.ctypeCom[fitsI] = "Type of co-ordinate on axis " + (iax + 1); // codespell:ignore
                }

                if (wcsfrm.testUnit(iax + 1)) {
                    store.cunit[fitsI] = wcsfrm.getUnit(iax + 1);
                }

                axisDone[iax] = true;
            }
        }

        return ret != null ? ret.simplify() : new UnitMap(nwcs);
    }

    /**
     * C's {@code LogAxis}: the Mapping from world to intermediate world coordinates of the {@code
     * -LOG} algorithm for output {@code iax} of the pixel to world Mapping {@code map}, if that
     * output is logarithmic in the pixel coordinates over the box from {@code lbndP} to {@code
     * ubndP}, or null if it is not or {@code crval} is zero.
     *
     * <p>As C does, the output is logarithmic if its logarithm, computed by the MathMap C makes
     * here ({@link FitsLogMap}), is linear by {@link FitsIsMapLinear#isMapLinear}.
     *
     * @return null if {@code crval} is zero or the output is not logarithmic
     * @throws UnsupportedOperationException if the output is logarithmic, where C makes the {@code
     *     -LOG} Mapping, which holds that MathMap, so needs MathMap
     */
    static Mapping logAxis(Mapping map, int iax, int nwcs, double[] lbndP, double[] ubndP, double crval) {
        if (crval == 0.0) {
            return null;
        }
        Mapping tmap0 = new CmpMap(map, addUnitMaps(new FitsLogMap(), iax, nwcs), true);
        if (!FitsIsMapLinear.isMapLinear(tmap0, lbndP, ubndP, iax, null)) {
            return null;
        }
        throw new UnsupportedOperationException(
                "LogAxis(FitsChan): the -LOG description of world axis " + (iax + 1) + " needs MathMap");
    }

    /**
     * C's {@code AddUnitMaps}: a Mapping of {@code naxes} axes in which {@code map}, a Mapping with as
     * many inputs as outputs, transforms the axes from {@code iax} (zero-based), with UnitMaps in
     * parallel for the axes before and after; {@code map} itself if there are none.
     */
    static Mapping addUnitMaps(Mapping map, int iax, int naxes) {
        Mapping ret = map;
        int nmap = map.getNin();
        if (iax > 0) {
            ret = new CmpMap(new UnitMap(iax), ret, false);
        }
        if (iax + nmap < naxes) {
            ret = new CmpMap(ret, new UnitMap(naxes - iax - nmap), false);
        }
        return ret;
    }

    /**
     * C's {@code SetAlgCode}: {@code buf} padded with {@code -} to 4 characters and {@code
     * algcode} put in place of everything from its fifth character on.
     */
    static String setAlgCode(String buf, String algcode) {
        StringBuilder b = new StringBuilder(buf);
        while (b.length() < 4) {
            b.append('-');
        }
        b.setLength(4);
        return b.append(algcode).toString();
    }
}
