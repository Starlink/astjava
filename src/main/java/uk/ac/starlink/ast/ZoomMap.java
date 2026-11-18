package uk.ac.starlink.ast;

/**
 * A Mapping that zooms coordinates about the origin: the forward transformation multiplies every
 * coordinate by the same scale factor, the Zoom attribute, and the inverse divides by it. The
 * number of coordinates representing each point is unchanged.
 *
 * <p>Ported from Rust {@code mappings/zoom.rs} and C {@code zoommap.c}.
 */
public class ZoomMap extends Mapping {

    /** C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    /** The Zoom attribute: the scale factor, or zero when it is unset (default 1). */
    private double zoom;

    /**
     * Creates a ZoomMap.
     *
     * @param ncoord the number of input and output coordinates
     * @param zoom the scale factor, by which the forward transformation multiplies each
     *     coordinate; positive or negative, but not zero
     * @throws AstException with status {@code AST__ZOOMI} if {@code zoom} is zero, or {@code
     *     AST__BADNI} if {@code ncoord} is negative
     */
    public ZoomMap(int ncoord, double zoom) {
        super(checkedNcoord(ncoord, zoom), ncoord, true, true);
        this.zoom = zoom;
    }

    /** {@code ncoord}, after C's check of the zoom factor, which comes first (zoommap.c {@code astInitZoomMap}). */
    private static int checkedNcoord(int ncoord, double zoom) {
        if (zoom == 0.0) {
            throw Ast.err(AstException.AST__ZOOMI, "astInitZoomMap(ZoomMap): A zoom factor of zero is not allowed.");
        }
        return ncoord;
    }

    /**
     * Loads a ZoomMap from its description, as C's {@code astLoadZoomMap} does: a non-zero {@code
     * Zoom} item is set through the setter, which, given the value just read, keeps the record of
     * simplification the Mapping part set up.
     */
    ZoomMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("ZoomMap");
        zoom = part.readDouble("Zoom", 0.0);
        if (zoom != 0.0) {
            storeZoom(zoom);
        }
    }

    @Override
    String dumpComment() {
        return "Zoom about the origin";
    }

    /** Writes the Zoom item, which is helpful, so an unset value is written as a comment. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeDouble("Zoom", testZoom(), true, getZoom(), "Zoom factor");
    }

    @Override
    boolean isLinear() {
        return true;
    }

    /**
     * zoommap.c {@code MapMerge}. The run of ZoomMaps and UnitMaps around this ZoomMap is replaced
     * by one Mapping applied forward: in series a ZoomMap of the product of their effective
     * factors, or a UnitMap if that is one to within {@code 8 * DBL_EPSILON}; in parallel a ZoomMap
     * if every factor is the same, a UnitMap if every factor is one, and a diagonal MatrixMap
     * otherwise. That is done if it removes a Mapping, gives a UnitMap, or turns this ZoomMap,
     * applied inverted, forward. Failing that, in series, the ZoomMap is absorbed into the
     * Mapping before it, or else the one after it, if that is a MatrixMap, a WinMap or a ShiftMap.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        int result = -1;
        Mapping replacement = null;
        boolean simpler;
        int imap1 = where;
        int imap2 = where;
        double zoom = 1.0;
        if (series) {
            zoom = ((ZoomMap) list.map(where)).getZoom();
            if (list.invert(where)) {
                zoom = 1.0 / zoom;
            }
            while (imap1 - 1 >= 0) {
                Mapping map = list.map(imap1 - 1);
                if (map.getClass() == ZoomMap.class) {
                    if (list.invert(imap1 - 1)) {
                        zoom /= ((ZoomMap) map).getZoom();
                    } else {
                        zoom *= ((ZoomMap) map).getZoom();
                    }
                } else if (map.getClass() != UnitMap.class) {
                    break;
                }
                imap1--;
            }
            while (imap2 + 1 < list.size()) {
                Mapping map = list.map(imap2 + 1);
                if (map.getClass() == ZoomMap.class) {
                    if (list.invert(imap2 + 1)) {
                        zoom /= ((ZoomMap) map).getZoom();
                    } else {
                        zoom *= ((ZoomMap) map).getZoom();
                    }
                } else if (map.getClass() != UnitMap.class) {
                    break;
                }
                imap2++;
            }
            int ngone = imap2 - imap1;
            boolean unit = Math.abs(zoom - 1.0) <= 8.0 * DBL_EPSILON;
            simpler = ngone != 0 || unit || list.invert(where);
            if (simpler) {
                int nin = list.map(where).getNin();
                replacement = unit ? new UnitMap(nin) : new ZoomMap(nin, zoom);
            }
        } else {
            int nin = list.map(where).getNin();
            while (imap1 - 1 >= 0) {
                Mapping map = list.map(imap1 - 1);
                if (map.getClass() != ZoomMap.class && map.getClass() != UnitMap.class) {
                    break;
                }
                nin += map.getNin();
                imap1--;
            }
            while (imap2 + 1 < list.size()) {
                Mapping map = list.map(imap2 + 1);
                if (map.getClass() != ZoomMap.class && map.getClass() != UnitMap.class) {
                    break;
                }
                nin += map.getNin();
                imap2++;
            }
            double[] zooms = new double[nin];
            int nzoom = 0;
            double minzoom = Double.MAX_VALUE;
            double maxzoom = -Double.MAX_VALUE;
            for (int imap = imap1; imap <= imap2; imap++) {
                Mapping map = list.map(imap);
                double z = 1.0;
                if (map.getClass() == ZoomMap.class) {
                    z = list.invert(imap) ? 1.0 / ((ZoomMap) map).getZoom() : ((ZoomMap) map).getZoom();
                }
                int ncoord = map.getNin();
                for (int coord = 0; coord < ncoord; coord++) {
                    zooms[nzoom++] = z;
                }
                if (z < minzoom) {
                    minzoom = z;
                }
                if (z > maxzoom) {
                    maxzoom = z;
                }
            }
            int ngone = imap2 - imap1;
            boolean single = minzoom == maxzoom;
            boolean unit = single && minzoom == 1.0;
            simpler = ngone != 0 || unit || list.invert(where);
            if (simpler) {
                if (unit) {
                    replacement = new UnitMap(nin);
                } else if (single) {
                    replacement = new ZoomMap(nin, minzoom);
                } else {
                    replacement = new MatrixMap(nin, nin, MatrixMap.DIAGONAL, zooms);
                }
            }
        }
        if (simpler) {
            list.replaceRangeWith(imap1, imap2, replacement);
            result = imap1;
        }
        if (!simpler && series) {
            result = absorb(list, where, zoom);
        }
        return result;
    }

    /**
     * The last part of zoommap.c {@code MapMerge}: the ZoomMap, of effective factor {@code zoom},
     * merged into the Mapping before it or, failing that, the one after it, if that is a MatrixMap
     * (whose matrix is scaled), a WinMap (whose terms are scaled) or a ShiftMap (which becomes a
     * WinMap). The merged Mapping replaces the neighbour, applied with its own Invert attribute,
     * and the ZoomMap is removed.
     *
     * @return the lower index of the two, or -1 if neither neighbour can absorb the ZoomMap
     */
    private static int absorb(MergeList list, int where, double zoom) {
        Mapping replacement = null;
        int imod = -1;
        if (where > 0) {
            imod = where - 1;
            replacement = absorbed(list, imod, zoom, true);
        }
        if (replacement == null && where < list.size() - 1) {
            imod = where + 1;
            replacement = absorbed(list, imod, zoom, false);
        }
        if (replacement == null) {
            return -1;
        }
        list.set(imod, replacement, replacement.getInvert());
        list.removeAt(where);
        return Math.min(where, imod);
    }

    /**
     * The Mapping at {@code imod} with the ZoomMap of effective factor {@code zoom} merged into it,
     * the ZoomMap applied after it if {@code zoomAfter} and before it otherwise; or null if it is
     * not a MatrixMap, a WinMap or a ShiftMap.
     */
    private static Mapping absorbed(MergeList list, int imod, double zoom, boolean zoomAfter) {
        Mapping neighbour = list.map(imod);
        if (neighbour instanceof MatrixMap) {
            int saved = neighbour.swapInvert(list.invert(imod));
            try {
                return ((MatrixMap) neighbour).mtrZoom(zoom);
            } finally {
                neighbour.restoreInvert(saved);
            }
        } else if (neighbour instanceof WinMap) {
            WinMap result = (WinMap) neighbour.copy();
            result.setInvert(list.invert(imod));
            double[][] terms = result.winTerms();
            double[] a = terms[0];
            double[] b = terms[1];
            for (int i = 0; i < a.length; i++) {
                if (zoomAfter) {
                    a[i] *= zoom;
                }
                b[i] *= zoom;
            }
            result.setWinTerms(a, b);
            return result;
        } else if (neighbour instanceof ShiftMap) {
            int nin = neighbour.getNin();
            double[] a;
            int saved = neighbour.swapInvert(list.invert(imod));
            try {
                a = ((ShiftMap) neighbour).getShifts();
            } finally {
                neighbour.restoreInvert(saved);
            }
            double[] b = new double[nin];
            for (int i = 0; i < nin; i++) {
                if (zoomAfter) {
                    a[i] *= zoom;
                }
                b[i] = zoom;
            }
            return new WinMap(a, b);
        } else {
            return null;
        }
    }

    /**
     * The rate of change: the zoom factor, or its reciprocal when inverted, where the output is the
     * input, and zero elsewhere ({@code Rate}, zoommap.c).
     */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        if (ax1 != ax2) {
            return 0.0;
        }
        double result = getZoom();
        if (getInvert()) {
            result = result != AST__BAD && result != 0.0 ? 1.0 / result : AST__BAD;
        }
        return result;
    }

    /**
     * Splits off a ZoomMap of the chosen inputs, with the same zoom factor and its Invert attribute
     * set to this one's, as C's {@code MapSplit} (zoommap.c) does; an input that does not exist
     * gives no split.
     */
    @Override
    MapSplit splitOff(int[] in) {
        if (in.length == 0 || !MapSplit.allInputs(in, getNin())) {
            return null;
        }
        ZoomMap map = new ZoomMap(in.length, getZoom());
        map.setInvert(getInvert());
        return new MapSplit(in.clone(), map);
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    /**
     * Gets the Zoom attribute: the scale factor by which the forward transformation of the ZoomMap
     * as created multiplies coordinates. When the ZoomMap is inverted, its forward transformation
     * in effect uses the reciprocal. The factor is never zero. Default 1, which only clearing the
     * attribute restores, since the constructor sets it.
     *
     * @return the zoom factor
     */
    public double getZoom() {
        return zoom == 0.0 ? 1.0 : zoom;
    }

    /**
     * Sets the Zoom attribute. A value different from the one held discards the record that the
     * ZoomMap has been simplified.
     *
     * @param zoom the zoom factor
     * @throws AstException with status {@code AST__ZOOMI} if {@code zoom} is zero, leaving the
     *     factor unchanged, or {@code AST__IMMUT} if the ZoomMap is shared with a holder such as a
     *     CmpMap, which a copy is not
     */
    public void setZoom(double zoom) {
        storeZoom(zoom);
    }

    /**
     * C's private {@code SetZoom}, which both {@link #setZoom} and the loader call. It is an
     * {@code astMAKE_SET1} attribute, so a shared ZoomMap refuses the change first.
     */
    private void storeZoom(double zoom) {
        checkSet1("Zoom");
        // C tests the raw value, so a rejected zero still discards the records (zoommap.c, the
        // astMAKE_SET1 of Zoom).
        if (zoom != this.zoom) {
            clearIsSimple();
        }
        if (zoom == 0.0) {
            throw Ast.err(
                    AstException.AST__ZOOMI, "astSetZoom(" + astClass() + "): A zoom factor of zero is not allowed.");
        }
        this.zoom = zoom;
    }

    /** Whether the Zoom attribute is set. */
    boolean testZoom() {
        return zoom != 0.0;
    }

    /**
     * Clears the Zoom attribute, which also discards the record of simplification. It is an
     * {@code astMAKE_CLEAR1} attribute, so a shared ZoomMap refuses to clear it.
     */
    void clearZoom() {
        checkClear1("Zoom");
        clearIsSimple();
        zoom = 0.0;
    }

    @Override
    String getAttrib(String attrib) {
        if ("zoom".equals(attrib)) {
            return CFormat.format("%.17g", getZoom());
        }
        return super.getAttrib(attrib);
    }

    @Override
    void setAttrib(String attrib, String value) {
        Double dval = CScan.scanDouble(value);
        if ("zoom".equals(attrib) && dval != null) {
            setZoom(dval);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        return "zoom".equals(attrib) ? testZoom() : super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if ("zoom".equals(attrib)) {
            clearZoom();
        } else {
            super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /** The factor the transformation in direction {@code forward} multiplies by. */
    private double scale(boolean forward) {
        return effectiveForward(forward) ? getZoom() : 1.0 / getZoom();
    }

    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        double scale = scale(forward);
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            for (int p = 0; p < npoint; p++) {
                double v = a[p];
                o[p] = isBad(v) ? AST__BAD : v * scale;
            }
        }
    }

    /** NaN protocol: a plain multiplication, through which NaN propagates. */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        double scale = scale(forward);
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            for (int p = 0; p < npoint; p++) {
                o[p] = a[p] * scale;
            }
        }
    }
}
