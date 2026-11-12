package uk.ac.starlink.ast;

/**
 * A Mapping that zooms coordinates about the origin: the forward transformation multiplies every
 * coordinate by the same scale factor, the Zoom attribute, and the inverse divides by it. The
 * number of coordinates representing each point is unchanged.
 *
 * <p>Ported from Rust {@code mappings/zoom.rs} and C {@code zoommap.c}.
 */
public class ZoomMap extends Mapping {

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
     *     factor unchanged
     */
    public void setZoom(double zoom) {
        storeZoom(zoom);
    }

    /** C's private {@code SetZoom}, which both {@link #setZoom} and the loader call. */
    private void storeZoom(double zoom) {
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

    /** Clears the Zoom attribute, which also discards the record of simplification. */
    void clearZoom() {
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
