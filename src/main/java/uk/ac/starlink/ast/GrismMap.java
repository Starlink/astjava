package uk.ac.starlink.ast;

/**
 * A Mapping which transforms 1-dimensional coordinates using the spectral dispersion equation
 * described in FITS-WCS paper III "Representation of spectral coordinates in FITS". This describes
 * the dispersion produced by gratings, prisms and grisms.
 *
 * <p>When initially created, the forward transformation of a GrismMap transforms input "grism
 * parameter" values into output wavelength values. The "grism parameter" is a dimensionless value
 * which is linearly related to position on the detector. It is defined in FITS-WCS paper III as
 * "the offset on the detector from the point of intersection of the camera axis, measured in units
 * of the effective local length". The units in which wavelength values are expected or returned
 * are those used for GrismWaveR, GrismNRP and GrismG.
 *
 * <p>The dispersion is described by eight attributes: GrismNR (the refractive index at the
 * reference wavelength, default 1), GrismNRP (the rate of change of refractive index with
 * wavelength, default 0), GrismWaveR (the reference wavelength, default 5000), GrismAlpha (the
 * angle of incidence of the incoming light, in radians, default 0), GrismG (the grating ruling
 * density, default 0), GrismM (the interference order, an integer, default 0), GrismEps (the angle
 * between the normal and the dispersion plane, in radians, default 0) and GrismTheta (the angle
 * between the normal to the detector plane and the reference ray, in radians, default 0). A new
 * GrismMap has none of them set. Once the GrismMap is held by another Object, such as a CmpMap,
 * none of them can be set or cleared.
 *
 * <p>Ported from Rust {@code mappings/grismmap.rs} and C {@code grismmap.c}.
 */
public class GrismMap extends Mapping {

    /** The value C stores in GrismM while it is unset, {@code INT_MAX}. */
    private static final int UNSET_M = Integer.MAX_VALUE;

    /** GrismNR, {@link #AST__BAD} while unset (C's {@code nr}). */
    private double nr = AST__BAD;

    /** GrismNRP, {@link #AST__BAD} while unset (C's {@code nrp}). */
    private double nrp = AST__BAD;

    /** GrismWaveR, {@link #AST__BAD} while unset (C's {@code waver}). */
    private double waver = AST__BAD;

    /** GrismAlpha, {@link #AST__BAD} while unset (C's {@code alpha}). */
    private double alpha = AST__BAD;

    /** GrismG, {@link #AST__BAD} while unset (C's {@code g}). */
    private double g = AST__BAD;

    /** GrismM, {@link #UNSET_M} while unset (C's {@code m}). */
    private int m = UNSET_M;

    /** GrismEps, {@link #AST__BAD} while unset (C's {@code eps}). */
    private double eps = AST__BAD;

    /** GrismTheta, {@link #AST__BAD} while unset (C's {@code theta}). */
    private double theta = AST__BAD;

    /** The constants the transformation uses, worked out from the attributes by {@link #updateConstants}. */
    private double k1;

    private double k2;

    private double k3;

    /** Creates a GrismMap with no attribute set, so that each takes its default value. */
    public GrismMap() {
        super(1, 1, true, true);
        updateConstants();
    }

    /**
     * Loads a GrismMap from its description, as C's {@code astLoadGrismMap} does: each attribute
     * read is set through its private setter, which, given the value just read, keeps the record
     * of simplification the Mapping part set up.
     */
    GrismMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("GrismMap");
        nr = part.readDouble("GrmNR", AST__BAD);
        if (testGrismNR()) {
            setGrismNRValue(nr);
        }
        nrp = part.readDouble("GrmNRP", AST__BAD);
        if (testGrismNRP()) {
            setGrismNRPValue(nrp);
        }
        waver = part.readDouble("GrmWR", AST__BAD);
        if (testGrismWaveR()) {
            setGrismWaveRValue(waver);
        }
        alpha = part.readDouble("GrmAlp", AST__BAD);
        if (testGrismAlpha()) {
            setGrismAlphaValue(alpha);
        }
        g = part.readDouble("GrmG", AST__BAD);
        if (testGrismG()) {
            setGrismGValue(g);
        }
        m = part.readInt("GrmM", UNSET_M);
        if (testGrismM()) {
            setGrismMValue(m);
        }
        eps = part.readDouble("GrmEps", AST__BAD);
        if (testGrismEps()) {
            setGrismEpsValue(eps);
        }
        theta = part.readDouble("GrmTh", AST__BAD);
        if (testGrismTheta()) {
            setGrismThetaValue(theta);
        }
        updateConstants();
    }

    @Override
    String dumpComment() {
        return "Map 1-d coordinates using a spectral disperser";
    }

    /**
     * Writes the eight attributes, each as a comment holding its default when it is unset, as C's
     * {@code Dump} (grismmap.c) does; GrismM is written as a floating-point value.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeDouble("GrmNR", testGrismNR(), true, getGrismNRValue(), "Refractive index at the ref. wavelength");
        node.writeDouble("GrmNRP", testGrismNRP(), true, getGrismNRPValue(), "Rate of change of refractive index");
        node.writeDouble("GrmWR", testGrismWaveR(), true, getGrismWaveRValue(), "Ref. wavelength");
        node.writeDouble(
                "GrmAlp", testGrismAlpha(), true, getGrismAlphaValue(), "Angle of incidence of incoming light");
        node.writeDouble("GrmG", testGrismG(), true, getGrismGValue(), "Grating ruling density");
        node.writeDouble("GrmM", testGrismM(), true, getGrismMValue(), "The interference order");
        node.writeDouble(
                "GrmEps",
                testGrismEps(),
                true,
                getGrismEpsValue(),
                "Angle between grating normal and dispersion plane");
        node.writeDouble(
                "GrmTh",
                testGrismTheta(),
                true,
                getGrismThetaValue(),
                "Angle between detector normal and reference ray");
    }

    // ------------------------------------------------------------------
    // Derived constants
    // ------------------------------------------------------------------

    /**
     * C's {@code UpdateConstants}: works out the constants the transformation uses from the
     * attribute values, {@link #AST__BAD} for those that do not exist (when GrismEps makes the
     * cosine zero, or the sine of the reference ray's angle is outside -1 to 1).
     */
    private void updateConstants() {
        double cnr = getGrismNRValue();
        double cnrp = getGrismNRPValue();
        double waveR = getGrismWaveRValue();
        double calpha = getGrismAlphaValue();
        double cg = getGrismGValue();
        int cm = getGrismMValue();
        double ceps = getGrismEpsValue();
        double ctheta = getGrismThetaValue();
        double coseps = Math.cos(ceps);
        double sinalpha = Math.sin(calpha);
        k1 = sinalpha * (cnr - cnrp * waveR);
        if (coseps != 0.0) {
            k2 = (cg * cm / coseps) - cnrp * sinalpha;
            double sinbetaR = cg * cm * waveR / coseps - cnr * sinalpha;
            if (sinbetaR < -1.0 || sinbetaR > 1.0) {
                k3 = AST__BAD;
            } else {
                k3 = Math.asin(sinbetaR) + ctheta;
            }
        } else {
            k2 = AST__BAD;
            k3 = AST__BAD;
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * C's {@code Transform} (grismmap.c): the forward transformation gives the wavelength {@code
     * (k1 + sin(atan(g) + k3)) / k2} of the grism parameter {@code g}, and the inverse the grism
     * parameter {@code tan(asin(k2 * w - k1) - k3)} of the wavelength {@code w}, bad where the
     * sine is outside -1 to 1. Every output is bad when a constant is bad, and the forward
     * transformation's when {@code k2} is zero.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        double[] xin = in[0];
        double[] xout = out[0];
        if (k1 == AST__BAD || k2 == AST__BAD || k3 == AST__BAD) {
            for (int p = 0; p < npoint; p++) {
                xout[p] = AST__BAD;
            }
        } else if (effectiveForward(forward)) {
            for (int p = 0; p < npoint; p++) {
                double v = xin[p];
                if (v == AST__BAD || k2 == 0.0) {
                    xout[p] = AST__BAD;
                } else {
                    xout[p] = (k1 + Math.sin(Math.atan(v) + k3)) / k2;
                }
            }
        } else {
            for (int p = 0; p < npoint; p++) {
                double v = xin[p];
                if (v == AST__BAD) {
                    xout[p] = AST__BAD;
                } else {
                    double sinbeta = k2 * v - k1;
                    if (sinbeta < -1.0 || sinbeta > 1.0) {
                        xout[p] = AST__BAD;
                    } else {
                        xout[p] = Math.tan(Math.asin(sinbeta) - k3);
                    }
                }
            }
        }
    }

    /**
     * NaN protocol: {@link #transformInto} without the per-point checks of bad input, through
     * whose arithmetic NaN propagates; the inverse's range check on the sine, which a NaN fails,
     * gives NaN.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        double[] xin = in[0];
        double[] xout = out[0];
        if (k1 == AST__BAD || k2 == AST__BAD || k3 == AST__BAD || (effectiveForward(forward) && k2 == 0.0)) {
            for (int p = 0; p < npoint; p++) {
                xout[p] = Double.NaN;
            }
        } else if (effectiveForward(forward)) {
            for (int p = 0; p < npoint; p++) {
                xout[p] = (k1 + Math.sin(Math.atan(xin[p]) + k3)) / k2;
            }
        } else {
            for (int p = 0; p < npoint; p++) {
                double sinbeta = k2 * xin[p] - k1;
                xout[p] = sinbeta >= -1.0 && sinbeta <= 1.0 ? Math.tan(Math.asin(sinbeta) - k3) : Double.NaN;
            }
        }
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * grismmap.c {@code Equal}: {@code that} is a GrismMap with the same numbers of inputs and
     * outputs and the same Invert attribute, whose attributes and constants are, to within {@code
     * astEQUAL}, this one's. C compares the attributes as stored, so an unset attribute differs
     * from one set to its default.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof GrismMap)) {
            return false;
        }
        GrismMap other = (GrismMap) that;
        if (other.getNin() != getNin() || other.getNout() != getNout() || getInvert() != other.getInvert()) {
            return false;
        }
        return MappingHelpers.astEqual(nr, other.nr)
                && MappingHelpers.astEqual(nrp, other.nrp)
                && MappingHelpers.astEqual(waver, other.waver)
                && MappingHelpers.astEqual(alpha, other.alpha)
                && MappingHelpers.astEqual(g, other.g)
                && m == other.m
                && MappingHelpers.astEqual(eps, other.eps)
                && MappingHelpers.astEqual(theta, other.theta)
                && MappingHelpers.astEqual(k1, other.k1)
                && MappingHelpers.astEqual(k2, other.k2)
                && MappingHelpers.astEqual(k3, other.k3);
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * grismmap.c {@code MapMerge}: in series, the GrismMap is merged with the Mapping before it
     * or, failing that, the one after it, as {@link #canMerge} decides, and the merged Mapping
     * replaces the pair, applied with its own Invert attribute. Nothing is merged in parallel.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        if (!series) {
            return -1;
        }
        Mapping merged = null;
        int i1 = -1;
        if (where > 0) {
            i1 = where - 1;
            merged = canMerge(list.map(i1), list.invert(i1), list.map(where), list.invert(where));
        }
        if (merged == null && where < list.size() - 1) {
            i1 = where;
            merged = canMerge(list.map(i1), list.invert(i1), list.map(where + 1), list.invert(where + 1));
        }
        if (merged == null) {
            return -1;
        }
        list.replacePairWith(i1, merged, merged.getInvert());
        return i1;
    }

    /**
     * C's {@code CanMerge} (grismmap.c): the Mapping equivalent to {@code map1} followed by {@code
     * map2}, applied with the Invert values {@code inv1} and {@code inv2}, one of which is a
     * GrismMap; or null if they cannot be merged. Two GrismMaps applied in opposite directions
     * whose attributes are equal to within {@code astEQUAL}, except GrismM, which must differ,
     * give a UnitMap. A GrismMap applied forward followed by a ZoomMap, or a ZoomMap followed by
     * a GrismMap applied inverted, give a copy of the GrismMap with its GrismG, GrismNRP and
     * GrismWaveR changed to describe wavelengths scaled by the zoom factor, applied in the
     * direction of the GrismMap. Classes are compared by name, as C does.
     *
     * <p>The zoom factor is the ZoomMap's Zoom attribute, or its reciprocal when the ZoomMap's own
     * Invert attribute differs from the Invert value it is applied with (when it follows the
     * GrismMap) or equals it (when it precedes it), as C decides; a ZoomMap whose own Invert
     * attribute is set is therefore taken in the direction opposite to the one it is applied in.
     */
    private static Mapping canMerge(Mapping map1, boolean inv1, Mapping map2, boolean inv2) {
        GrismMap gmap = null;
        boolean invertResult = false;
        double z = AST__BAD;
        if ("GrismMap".equals(map1.astClass())) {
            gmap = (GrismMap) map1;
            if ("GrismMap".equals(map2.astClass())) {
                GrismMap gmap2 = (GrismMap) map2;
                if (MappingHelpers.astEqual(gmap.getGrismNRValue(), gmap2.getGrismNRValue())
                        && MappingHelpers.astEqual(gmap.getGrismNRPValue(), gmap2.getGrismNRPValue())
                        && MappingHelpers.astEqual(gmap.getGrismWaveRValue(), gmap2.getGrismWaveRValue())
                        && MappingHelpers.astEqual(gmap.getGrismAlphaValue(), gmap2.getGrismAlphaValue())
                        && MappingHelpers.astEqual(gmap.getGrismGValue(), gmap2.getGrismGValue())
                        && gmap.getGrismMValue() != gmap2.getGrismMValue()
                        && MappingHelpers.astEqual(gmap.getGrismEpsValue(), gmap2.getGrismEpsValue())
                        && MappingHelpers.astEqual(gmap.getGrismThetaValue(), gmap2.getGrismThetaValue())
                        && inv1 != inv2) {
                    return new UnitMap(1);
                }
                return null;
            } else if (!inv1 && "ZoomMap".equals(map2.astClass())) {
                z = ((ZoomMap) map2).getZoom();
                if (map2.getInvert() != inv2 && z != 0.0) {
                    z = 1.0 / z;
                }
            }
        } else if ("GrismMap".equals(map2.astClass())) {
            gmap = (GrismMap) map2;
            if (inv2) {
                invertResult = true;
                if ("ZoomMap".equals(map1.astClass())) {
                    z = ((ZoomMap) map1).getZoom();
                    if (map1.getInvert() == inv1 && z != 0.0) {
                        z = 1.0 / z;
                    }
                }
            }
        }
        if (z == AST__BAD || z == 0.0) {
            return null;
        }
        GrismMap ret = (GrismMap) gmap.copy();
        ret.setInvert(false);
        ret.setGrismGValue(ret.getGrismGValue() / z);
        ret.setGrismNRPValue(ret.getGrismNRPValue() / z);
        ret.setGrismWaveRValue(ret.getGrismWaveRValue() * z);
        if (invertResult) {
            ret.invert();
        }
        return ret;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Gets the GrismNR attribute: the refractive index of the grism material at the reference
     * wavelength (given by GrismWaveR). Default 1.
     *
     * @return the refractive index
     */
    public double getGrismNR() {
        return getD("GrismNR");
    }

    /**
     * Sets the GrismNR attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param grismNR the refractive index at the reference wavelength
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismNR(double grismNR) {
        setD("GrismNR", grismNR);
    }

    /**
     * Gets the GrismNRP attribute: the rate of change of the refractive index of the grism
     * material with respect to wavelength, at the reference wavelength (given by GrismWaveR). Its
     * units should be consistent with those of GrismWaveR and GrismG. Default 0, the value for a
     * pure grating disperser with no prism.
     *
     * @return the rate of change of refractive index with wavelength
     */
    public double getGrismNRP() {
        return getD("GrismNRP");
    }

    /**
     * Sets the GrismNRP attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param grismNRP the rate of change of refractive index with wavelength
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismNRP(double grismNRP) {
        setD("GrismNRP", grismNRP);
    }

    /**
     * Gets the GrismWaveR attribute: the reference wavelength. Its units should be consistent
     * with those of GrismNRP and GrismG, and are those of the wavelengths the GrismMap
     * transforms. Default 5000 (Angstrom).
     *
     * @return the reference wavelength
     */
    public double getGrismWaveR() {
        return getD("GrismWaveR");
    }

    /**
     * Sets the GrismWaveR attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param grismWaveR the reference wavelength
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismWaveR(double grismWaveR) {
        setD("GrismWaveR", grismWaveR);
    }

    /**
     * Gets the GrismAlpha attribute: the angle between the incoming light and the normal to the
     * grating surface, in radians. Default 0.
     *
     * @return the angle of incidence
     */
    public double getGrismAlpha() {
        return getD("GrismAlpha");
    }

    /**
     * Sets the GrismAlpha attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param grismAlpha the angle of incidence, in radians
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismAlpha(double grismAlpha) {
        setD("GrismAlpha", grismAlpha);
    }

    /**
     * Gets the GrismG attribute: the grating ruling density, the number of grating rulings per
     * unit length. The unit of length should be consistent with the units of GrismWaveR and
     * GrismNRP. Default 0, the value for a pure prism disperser with no grating.
     *
     * @return the grating ruling density
     */
    public double getGrismG() {
        return getD("GrismG");
    }

    /**
     * Sets the GrismG attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param grismG the grating ruling density
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismG(double grismG) {
        setD("GrismG", grismG);
    }

    /**
     * Gets the GrismM attribute: the interference order being considered. Default 0.
     *
     * @return the interference order
     */
    public int getGrismM() {
        return getI("GrismM");
    }

    /**
     * Sets the GrismM attribute. {@link Integer#MAX_VALUE} leaves it unset.
     *
     * @param grismM the interference order
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismM(int grismM) {
        setI("GrismM", grismM);
    }

    /**
     * Gets the GrismEps attribute: the angle, in radians, between the normal to the grating or
     * exit prism face and the dispersion plane, the plane spanned by the incoming and outgoing
     * ray. Default 0.
     *
     * @return the angle between the normal and the dispersion plane
     */
    public double getGrismEps() {
        return getD("GrismEps");
    }

    /**
     * Sets the GrismEps attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param grismEps the angle between the normal and the dispersion plane, in radians
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismEps(double grismEps) {
        setD("GrismEps", grismEps);
    }

    /**
     * Gets the GrismTheta attribute: the angle of incidence of light of the reference
     * wavelength (given by GrismWaveR) onto the detector, the angle, in radians, between the
     * normal to the detector plane and an incident ray at the reference wavelength. Default 0.
     *
     * @return the angle of incidence onto the detector
     */
    public double getGrismTheta() {
        return getD("GrismTheta");
    }

    /**
     * Sets the GrismTheta attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param grismTheta the angle of incidence onto the detector, in radians
     * @throws AstException with status {@code AST__IMMUT} if the GrismMap is held by another
     *     Object, such as a CmpMap
     */
    public void setGrismTheta(double grismTheta) {
        setD("GrismTheta", grismTheta);
    }

    /** GrismNR, its default when unset (C's {@code astGetGrismNR}). */
    final double getGrismNRValue() {
        return nr == AST__BAD ? 1.0 : nr;
    }

    /** GrismNRP, its default when unset. */
    final double getGrismNRPValue() {
        return nrp == AST__BAD ? 0.0 : nrp;
    }

    /** GrismWaveR, its default when unset. */
    final double getGrismWaveRValue() {
        return waver == AST__BAD ? 5000.0 : waver;
    }

    /** GrismAlpha, its default when unset. */
    final double getGrismAlphaValue() {
        return alpha == AST__BAD ? 0.0 : alpha;
    }

    /** GrismG, its default when unset. */
    final double getGrismGValue() {
        return g == AST__BAD ? 0.0 : g;
    }

    /** GrismM, its default when unset. */
    final int getGrismMValue() {
        return m == UNSET_M ? 0 : m;
    }

    /** GrismEps, its default when unset. */
    final double getGrismEpsValue() {
        return eps == AST__BAD ? 0.0 : eps;
    }

    /** GrismTheta, its default when unset. */
    final double getGrismThetaValue() {
        return theta == AST__BAD ? 0.0 : theta;
    }

    /**
     * C's private {@code SetGrismNR}, from grismmap.c's {@code MAKE_SET}: a shared GrismMap
     * refuses the change; a value different from the one stored (unset being {@link #AST__BAD})
     * discards the record of simplification; {@link #AST__BAD} leaves the attribute unset; the
     * constants are worked out again. The other private setters do the same.
     */
    final void setGrismNRValue(double value) {
        checkSet1("GrismNR");
        if (value != nr) {
            clearIsSimple();
        }
        nr = value;
        updateConstants();
    }

    final void setGrismNRPValue(double value) {
        checkSet1("GrismNRP");
        if (value != nrp) {
            clearIsSimple();
        }
        nrp = value;
        updateConstants();
    }

    final void setGrismWaveRValue(double value) {
        checkSet1("GrismWaveR");
        if (value != waver) {
            clearIsSimple();
        }
        waver = value;
        updateConstants();
    }

    final void setGrismAlphaValue(double value) {
        checkSet1("GrismAlpha");
        if (value != alpha) {
            clearIsSimple();
        }
        alpha = value;
        updateConstants();
    }

    final void setGrismGValue(double value) {
        checkSet1("GrismG");
        if (value != g) {
            clearIsSimple();
        }
        g = value;
        updateConstants();
    }

    /** {@link Integer#MAX_VALUE}, C's {@code INT_MAX}, leaves GrismM unset. */
    final void setGrismMValue(int value) {
        checkSet1("GrismM");
        if (value != m) {
            clearIsSimple();
        }
        m = value;
        updateConstants();
    }

    final void setGrismEpsValue(double value) {
        checkSet1("GrismEps");
        if (value != eps) {
            clearIsSimple();
        }
        eps = value;
        updateConstants();
    }

    final void setGrismThetaValue(double value) {
        checkSet1("GrismTheta");
        if (value != theta) {
            clearIsSimple();
        }
        theta = value;
        updateConstants();
    }

    private boolean testGrismNR() {
        return nr != AST__BAD;
    }

    private boolean testGrismNRP() {
        return nrp != AST__BAD;
    }

    private boolean testGrismWaveR() {
        return waver != AST__BAD;
    }

    private boolean testGrismAlpha() {
        return alpha != AST__BAD;
    }

    private boolean testGrismG() {
        return g != AST__BAD;
    }

    private boolean testGrismM() {
        return m != UNSET_M;
    }

    private boolean testGrismEps() {
        return eps != AST__BAD;
    }

    private boolean testGrismTheta() {
        return theta != AST__BAD;
    }

    /**
     * Clears the attribute named in lower case, as grismmap.c's {@code MAKE_CLEAR} does: a shared
     * GrismMap refuses; otherwise the record of simplification is discarded, the attribute unset
     * and the constants worked out again.
     *
     * @return whether {@code attrib} is a GrismMap attribute
     */
    private boolean clearGrism(String attrib) {
        // MODERN-JAVA(14): switch expression
        switch (attrib) {
            case "grismnr":
                checkClear1("GrismNR");
                clearIsSimple();
                nr = AST__BAD;
                break;
            case "grismnrp":
                checkClear1("GrismNRP");
                clearIsSimple();
                nrp = AST__BAD;
                break;
            case "grismwaver":
                checkClear1("GrismWaveR");
                clearIsSimple();
                waver = AST__BAD;
                break;
            case "grismalpha":
                checkClear1("GrismAlpha");
                clearIsSimple();
                alpha = AST__BAD;
                break;
            case "grismg":
                checkClear1("GrismG");
                clearIsSimple();
                g = AST__BAD;
                break;
            case "grismm":
                checkClear1("GrismM");
                clearIsSimple();
                m = UNSET_M;
                break;
            case "grismeps":
                checkClear1("GrismEps");
                clearIsSimple();
                eps = AST__BAD;
                break;
            case "grismtheta":
                checkClear1("GrismTheta");
                clearIsSimple();
                theta = AST__BAD;
                break;
            default:
                return false;
        }
        updateConstants();
        return true;
    }

    /** C's {@code GetAttrib}: each attribute formatted with {@code %.17g}, GrismM as a double. */
    @Override
    String getAttrib(String attrib) {
        double dval;
        // MODERN-JAVA(14): switch expression
        switch (attrib) {
            case "grismnr":
                dval = getGrismNRValue();
                break;
            case "grismnrp":
                dval = getGrismNRPValue();
                break;
            case "grismwaver":
                dval = getGrismWaveRValue();
                break;
            case "grismalpha":
                dval = getGrismAlphaValue();
                break;
            case "grismg":
                dval = getGrismGValue();
                break;
            case "grismm":
                dval = getGrismMValue();
                break;
            case "grismeps":
                dval = getGrismEpsValue();
                break;
            case "grismtheta":
                dval = getGrismThetaValue();
                break;
            default:
                return super.getAttrib(attrib);
        }
        return CFormat.format("%.17g", dval);
    }

    /**
     * C's {@code SetAttrib}: every attribute's value is read as a double, GrismM's then converted
     * to an integer by truncation, as C's assignment of a double to an {@code int} does.
     */
    @Override
    void setAttrib(String attrib, String value) {
        Double dval = CScan.scanDouble(value);
        if (dval == null) {
            super.setAttrib(attrib, value);
            return;
        }
        double v = dval;
        // MODERN-JAVA(14): switch expression
        switch (attrib) {
            case "grismnr":
                setGrismNRValue(v);
                break;
            case "grismnrp":
                setGrismNRPValue(v);
                break;
            case "grismwaver":
                setGrismWaveRValue(v);
                break;
            case "grismalpha":
                setGrismAlphaValue(v);
                break;
            case "grismg":
                setGrismGValue(v);
                break;
            case "grismm":
                setGrismMValue((int) v);
                break;
            case "grismeps":
                setGrismEpsValue(v);
                break;
            case "grismtheta":
                setGrismThetaValue(v);
                break;
            default:
                super.setAttrib(attrib, value);
                break;
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        // MODERN-JAVA(14): switch expression
        switch (attrib) {
            case "grismnr":
                return testGrismNR();
            case "grismnrp":
                return testGrismNRP();
            case "grismwaver":
                return testGrismWaveR();
            case "grismalpha":
                return testGrismAlpha();
            case "grismg":
                return testGrismG();
            case "grismm":
                return testGrismM();
            case "grismeps":
                return testGrismEps();
            case "grismtheta":
                return testGrismTheta();
            default:
                return super.testAttrib(attrib);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        if (!clearGrism(attrib)) {
            super.clearAttrib(attrib);
        }
    }
}
