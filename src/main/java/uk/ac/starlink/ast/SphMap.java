package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A Mapping from 3-dimensional Cartesian coordinates to 2-dimensional spherical coordinates
 * (longitude and latitude on a unit sphere centred on the origin). It regards the input
 * coordinates as position vectors and finds their intersection with the sphere's surface. The
 * inverse transformation always gives points a unit distance from the origin (unit vectors).
 *
 * <p>The spherical coordinates are longitude (positive anti-clockwise looking from the positive
 * latitude pole) and latitude, in radians. The Cartesian coordinates are right-handed, with the x
 * axis (axis 1) at zero longitude and latitude, and the z axis (axis 3) at the positive latitude
 * pole. At either pole the longitude is the PolarLong attribute. A zero vector transforms to a
 * longitude and latitude of zero.
 *
 * <p>Ported from Rust {@code mappings/sphmap.rs} and C {@code sphmap.c}.
 */
public class SphMap extends Mapping {

    /** The UnitRadius attribute: -1 when unset (C's value), otherwise 0 or 1. */
    private int unitRadius = -1;

    /** The PolarLong attribute in radians: {@code AST__BAD} when unset (C's value). */
    private double polarLong = AST__BAD;

    /** Creates a SphMap with every attribute unset, taking its default. */
    public SphMap() {
        super(3, 2, true, true);
    }

    /**
     * Loads a SphMap from its description, as C's {@code astLoadSphMap} does: each attribute is
     * read raw and, when set, applied again through its setter, which then changes nothing.
     */
    SphMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("SphMap");
        unitRadius = part.readInt("UntRd", -1);
        if (unitRadius != -1) {
            storeUnitRadius(unitRadius != 0);
        }
        polarLong = part.readDouble("PlrLg", AST__BAD);
        if (polarLong != AST__BAD) {
            storePolarLong(polarLong);
        }
    }

    @Override
    String dumpComment() {
        return "Cartesian to Spherical mapping";
    }

    /**
     * Writes UnitRadius, not helpful, and PolarLong, helpful, each set when it is set, as C's
     * {@code Dump} (sphmap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        boolean ur = getUnitRadius();
        node.writeInt(
                "UntRd",
                testUnitRadius(),
                false,
                ur ? 1 : 0,
                ur ? "All input vectors have unit length" : "Input vectors do not all have unit length");
        node.writeDouble("PlrLg", testPolarLong(), true, getPolarLong(), "Polar longitude (rad.s)");
    }

    // ------------------------------------------------------------------
    // UnitRadius
    // ------------------------------------------------------------------

    /**
     * Gets the UnitRadius attribute: whether the 3-dimensional vectors supplied as input are known
     * always to have unit length, so that they lie on a unit sphere centred on the origin.
     *
     * <p>If true, a CmpMap of a SphMap applied in the forward direction followed by a similar
     * SphMap applied in the inverse direction may be simplified to a UnitMap, because the input
     * and output vectors both have unit length and therefore the same coordinate values. If false,
     * the output vector of such a CmpMap still has unit length but the input vector may not, so
     * the pair is not simplified. This is intended mainly for SphMaps in a sequence of Mappings
     * that projects a dataset onto the celestial sphere, which lets the SphMaps and the associated
     * sky projections cancel when two datasets are aligned using celestial coordinates. It has no
     * effect on the coordinates a SphMap transforms: the lengths of the input vectors are always
     * ignored. Default false.
     *
     * @return the UnitRadius attribute
     */
    public boolean getUnitRadius() {
        return unitRadius != -1 && unitRadius != 0;
    }

    /**
     * Sets the UnitRadius attribute. A value different from the one held discards the record that
     * the SphMap has been simplified.
     *
     * @param unitRadius the new value
     * @throws AstException with status {@code AST__IMMUT} if the SphMap is shared with a holder
     *     such as a CmpMap, which a copy is not
     */
    public void setUnitRadius(boolean unitRadius) {
        storeUnitRadius(unitRadius);
    }

    /** C's {@code SetUnitRadius}, an {@code astMAKE_SET1} attribute (sphmap.c:1396). */
    private void storeUnitRadius(boolean value) {
        checkSet1("UnitRadius");
        int v = value ? 1 : 0;
        if (v != unitRadius) {
            clearIsSimple();
        }
        unitRadius = v;
    }

    /** Whether the UnitRadius attribute is set. */
    boolean testUnitRadius() {
        return unitRadius != -1;
    }

    /**
     * Clears the UnitRadius attribute, which also discards the record of simplification; an
     * {@code astMAKE_CLEAR1} attribute (sphmap.c:1394).
     */
    void clearUnitRadius() {
        checkClear1("UnitRadius");
        clearIsSimple();
        unitRadius = -1;
    }

    // ------------------------------------------------------------------
    // PolarLong
    // ------------------------------------------------------------------

    /**
     * Gets the PolarLong attribute: the longitude, in radians, given when a Cartesian position at
     * either the north or the south pole is transformed to spherical coordinates. Default 0.
     *
     * @return the PolarLong attribute
     */
    public double getPolarLong() {
        return polarLong == AST__BAD ? 0.0 : polarLong;
    }

    /**
     * Sets the PolarLong attribute. {@link #AST__BAD} unsets it. A value different from the one
     * held discards the record that the SphMap has been simplified.
     *
     * @param polarLong the longitude at the poles, in radians
     * @throws AstException with status {@code AST__IMMUT} if the SphMap is shared with a holder
     *     such as a CmpMap, which a copy is not
     */
    public void setPolarLong(double polarLong) {
        storePolarLong(polarLong);
    }

    /** C's {@code SetPolarLong}, an {@code astMAKE_SET1} attribute (sphmap.c:1438). */
    private void storePolarLong(double value) {
        checkSet1("PolarLong");
        if (value != polarLong) {
            clearIsSimple();
        }
        polarLong = value;
    }

    /** Whether the PolarLong attribute is set. */
    boolean testPolarLong() {
        return polarLong != AST__BAD;
    }

    /**
     * Clears the PolarLong attribute, which also discards the record of simplification; an
     * {@code astMAKE_CLEAR1} attribute (sphmap.c:1436).
     */
    void clearPolarLong() {
        checkClear1("PolarLong");
        clearIsSimple();
        polarLong = AST__BAD;
    }

    // ------------------------------------------------------------------
    // Attribute strings (sphmap.c GetAttrib, SetAttrib, TestAttrib, ClearAttrib)
    // ------------------------------------------------------------------

    @Override
    String getAttrib(String attrib) {
        if ("unitradius".equals(attrib)) {
            return Integer.toString(getUnitRadius() ? 1 : 0);
        } else if ("polarlong".equals(attrib)) {
            return CFormat.format("%.15g", getPolarLong());
        }
        return super.getAttrib(attrib);
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        Double dval = CScan.scanDouble(value);
        if ("unitradius".equals(attrib) && ival != null) {
            setUnitRadius(ival != 0);
        } else if ("polarlong".equals(attrib) && dval != null) {
            setPolarLong(dval);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        if ("unitradius".equals(attrib)) {
            return testUnitRadius();
        } else if ("polarlong".equals(attrib)) {
            return testPolarLong();
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if ("unitradius".equals(attrib)) {
            clearUnitRadius();
        } else if ("polarlong".equals(attrib)) {
            clearPolarLong();
        } else {
            super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * Whether the vector ({@code x}, {@code y}, {@code z}) is at a pole: x and y effectively zero
     * relative to z, both below {@code |1000 z| * DBL_EPSILON} (sphmap.c {@code Transform}). When
     * z is zero the bound is zero and no point passes, so the zero vector goes to {@code palDcc2s},
     * which gives (0, 0).
     */
    private static boolean atPole(double x, double y, double z) {
        double mxerr = Math.abs(1000.0 * z) * Math.ulp(1.0);
        return Math.abs(x) < mxerr && Math.abs(y) < mxerr;
    }

    /** The latitude C gives at a pole for {@code z}: its sign, with BAD for the zero C never reaches. */
    private static double poleLatitude(double z) {
        if (z < 0.0) {
            return -Math.PI / 2;
        } else if (z > 0.0) {
            return Math.PI / 2;
        }
        return AST__BAD;
    }

    /**
     * Transforms as C's {@code Transform} (sphmap.c) does: forward, each vector to its longitude
     * and latitude, the PolarLong attribute at a pole; inverse, each longitude and latitude to a
     * unit vector. A bad input coordinate gives bad outputs.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        if (effectiveForward(forward)) {
            double polar = getPolarLong();
            double[] p0 = in[0];
            double[] p1 = in[1];
            double[] p2 = in[2];
            double[] q0 = out[0];
            double[] q1 = out[1];
            for (int i = 0; i < npoint; i++) {
                double x = p0[i];
                double y = p1[i];
                double z = p2[i];
                if (isBad(x) || isBad(y) || isBad(z)) {
                    q0[i] = AST__BAD;
                    q1[i] = AST__BAD;
                } else if (atPole(x, y, z)) {
                    double lat = poleLatitude(z);
                    q0[i] = lat == AST__BAD ? AST__BAD : polar;
                    q1[i] = lat;
                } else {
                    double[] ab = Pal.dcc2s(new double[] {x, y, z});
                    q0[i] = ab[0];
                    q1[i] = ab[1];
                }
            }
        } else {
            double[] q0 = in[0];
            double[] q1 = in[1];
            double[] p0 = out[0];
            double[] p1 = out[1];
            double[] p2 = out[2];
            for (int i = 0; i < npoint; i++) {
                if (isBad(q0[i]) || isBad(q1[i])) {
                    p0[i] = AST__BAD;
                    p1[i] = AST__BAD;
                    p2[i] = AST__BAD;
                } else {
                    double[] v = Pal.dcs2c(q0[i], q1[i]);
                    p0[i] = v[0];
                    p1[i] = v[1];
                    p2[i] = v[2];
                }
            }
        }
    }

    /**
     * NaN protocol: no bad-value tests. The pole test compares with a bound that is NaN when z is,
     * so a point with a NaN coordinate is never a pole and keeps the NaN results of {@code
     * palDcc2s}; the inverse is {@code palDcs2c} alone, through which NaN propagates.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        if (effectiveForward(forward)) {
            double polar = getPolarLong();
            for (int i = 0; i < npoint; i++) {
                double x = in[0][i];
                double y = in[1][i];
                double z = in[2][i];
                if (atPole(x, y, z)) {
                    out[0][i] = polar;
                    out[1][i] = poleLatitude(z);
                } else {
                    double[] ab = Pal.dcc2s(new double[] {x, y, z});
                    out[0][i] = ab[0];
                    out[1][i] = ab[1];
                }
            }
        } else {
            for (int i = 0; i < npoint; i++) {
                double[] v = Pal.dcs2c(in[0][i], in[1][i]);
                out[0][i] = v[0];
                out[1][i] = v[1];
                out[2][i] = v[2];
            }
        }
    }
}
