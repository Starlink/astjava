package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * A non-linear Mapping which transforms 2-dimensional positions to correct for the radial
 * distortion introduced by some cameras and telescopes. This can take the form either of
 * pincushion or barrel distortion, and is characterised by a single distortion coefficient.
 *
 * <p>A PcdMap is specified by giving this distortion coefficient and the coordinates of the centre
 * of the radial distortion. The forward transformation of a PcdMap applies the distortion:
 *
 * <pre>
 *    RD = R * ( 1 + C * R * R )
 * </pre>
 *
 * <p>where R is the undistorted radial distance from the distortion centre (specified by attribute
 * PcdCen), RD is the radial distance from the same centre in the presence of distortion, and C is
 * the distortion coefficient (given by attribute Disco).
 *
 * <p>The inverse transformation of a PcdMap removes the distortion produced by the forward
 * transformation. The expression used to derive R from RD is an approximate inverse of the
 * expression above, the one SLALIB's {@code sla_UNPCD} uses.
 *
 * <p>Disco and PcdCen(1), PcdCen(2) have defaults of zero. The constructor sets all three unless
 * given {@link #AST__BAD}. Once the PcdMap is held by another Object, such as a CmpMap, none of them
 * can be set or cleared.
 *
 * <p>Ported from Rust {@code mappings/pcdmap.rs} and C {@code pcdmap.c}.
 */
public class PcdMap extends Mapping {

    /** C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    /** C's {@code DBL_MIN}. */
    private static final double DBL_MIN = Double.MIN_NORMAL;

    /** Disco, {@link #AST__BAD} while unset (C's {@code disco}). */
    private double disco = AST__BAD;

    /** PcdCen for axes 1 and 2, each {@link #AST__BAD} while unset (C's {@code pcdcen}). */
    private double[] centre = {AST__BAD, AST__BAD};

    /**
     * Creates a PcdMap, as C's {@code astPcdMap} does: each value given is stored as set, so that
     * only {@link #AST__BAD} leaves an attribute unset.
     *
     * @param disco the distortion coefficient: negative for barrel distortion, positive for
     *     pincushion distortion, zero for none
     * @param pcdcen the coordinates of the centre of the distortion, two values
     * @throws NullPointerException if {@code pcdcen} is null
     * @throws IllegalArgumentException if {@code pcdcen} has fewer than two values
     */
    public PcdMap(double disco, double[] pcdcen) {
        super(2, 2, true, true);
        Objects.requireNonNull(pcdcen, "Supplied array is null");
        if (pcdcen.length < 2) {
            throw new IllegalArgumentException("Supplied array has only " + pcdcen.length + " elements (needs 2)");
        }
        this.centre[0] = pcdcen[0];
        this.centre[1] = pcdcen[1];
        this.disco = disco;
    }

    /**
     * Loads a PcdMap from its description, as C's {@code astLoadPcdMap} does: each attribute read
     * is set through its private setter, which, given the value just read, keeps the record of
     * simplification the Mapping part set up.
     */
    PcdMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("PcdMap");
        centre[0] = part.readDouble("PcdCn0", AST__BAD);
        if (testPcdCenValue(0)) {
            setPcdCenValue(0, centre[0]);
        }
        centre[1] = part.readDouble("PcdCn1", AST__BAD);
        if (testPcdCenValue(1)) {
            setPcdCenValue(1, centre[1]);
        }
        disco = part.readDouble("Disco", AST__BAD);
        if (testDiscoValue()) {
            setDiscoValue(disco);
        }
    }

    /** Gives the copy its own centre, as C's byte-by-byte copy does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        centre = centre.clone();
    }

    @Override
    String dumpComment() {
        return "Apply pincushion distortion";
    }

    /**
     * Writes the centre and the coefficient, each as a comment holding its default when it is
     * unset, as C's {@code Dump} (pcdmap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeDouble("PcdCn0", testPcdCenValue(0), true, getPcdCenValue(0), "Distortion centre on first axis");
        node.writeDouble("PcdCn1", testPcdCenValue(1), true, getPcdCenValue(1), "Distortion centre on second axis");
        node.writeDouble("Disco", testDiscoValue(), true, getDiscoValue(), "Distortion coefficient");
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * C's {@code Transform} (pcdmap.c): the forward transformation moves each point along the line
     * from the centre by the factor {@code 1 + C * R * R}; the inverse moves it back by the
     * approximate inverse factor of SLALIB's {@code sla_UNPCD}. A point with a bad coordinate
     * gives bad values on both axes; a coordinate that is not finite is bad (C tests only for
     * {@link #AST__BAD}).
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        double c = getDiscoValue();
        double cen0 = getPcdCenValue(0);
        double cen1 = getPcdCenValue(1);
        double[] x = in[0];
        double[] y = in[1];
        double[] xo = out[0];
        double[] yo = out[1];
        if (effectiveForward(forward)) {
            for (int p = 0; p < npoint; p++) {
                if (!isBad(x[p]) && !isBad(y[p])) {
                    double dx = x[p] - cen0;
                    double dy = y[p] - cen1;
                    double f = 1.0 + c * (dx * dx + dy * dy);
                    xo[p] = dx * f + cen0;
                    yo[p] = dy * f + cen1;
                } else {
                    xo[p] = AST__BAD;
                    yo[p] = AST__BAD;
                }
            }
        } else {
            for (int p = 0; p < npoint; p++) {
                if (!isBad(x[p]) && !isBad(y[p])) {
                    double dxp = x[p] - cen0;
                    double dyp = y[p] - cen1;
                    double f = unpcdFactor(c * (dxp * dxp + dyp * dyp));
                    xo[p] = dxp * f + cen0;
                    yo[p] = dyp * f + cen1;
                } else {
                    xo[p] = AST__BAD;
                    yo[p] = AST__BAD;
                }
            }
        }
    }

    /**
     * The factor C's inverse {@code Transform} (pcdmap.c), after SLALIB's {@code sla_UNPCD},
     * multiplies a distorted offset from the centre by to remove the distortion, given {@code cr2},
     * the coefficient times the square of the distorted radius.
     */
    private static double unpcdFactor(double cr2) {
        double a = (2.0 * cr2 + 1.0) / (3.0 * cr2 + 1.0);
        double cr2a2 = cr2 * a * a;
        return (2.0 * cr2a2 * a + 1.0) / (3.0 * cr2a2 + 1.0);
    }

    /**
     * NaN protocol: {@link #transformInto} without the per-point checks of bad input, through
     * whose arithmetic NaN propagates to both outputs.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        double c = getDiscoValue();
        double cen0 = getPcdCenValue(0);
        double cen1 = getPcdCenValue(1);
        double[] x = in[0];
        double[] y = in[1];
        double[] xo = out[0];
        double[] yo = out[1];
        if (effectiveForward(forward)) {
            for (int p = 0; p < npoint; p++) {
                double dx = x[p] - cen0;
                double dy = y[p] - cen1;
                double f = 1.0 + c * (dx * dx + dy * dy);
                xo[p] = dx * f + cen0;
                yo[p] = dy * f + cen1;
            }
        } else {
            for (int p = 0; p < npoint; p++) {
                double dxp = x[p] - cen0;
                double dyp = y[p] - cen1;
                double f = unpcdFactor(c * (dxp * dxp + dyp * dyp));
                xo[p] = dxp * f + cen0;
                yo[p] = dyp * f + cen1;
            }
        }
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * pcdmap.c {@code Equal}: {@code that} is a PcdMap with the same Invert attribute, whose
     * centre and coefficient are, to within {@code astEQUAL}, this one's. C compares the
     * attributes as stored, so an unset attribute differs from one set to its default.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof PcdMap)) {
            return false;
        }
        PcdMap other = (PcdMap) that;
        return getInvert() == other.getInvert()
                && MappingHelpers.astEqual(centre[0], other.centre[0])
                && MappingHelpers.astEqual(centre[1], other.centre[1])
                && MappingHelpers.astEqual(disco, other.disco);
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * pcdmap.c's own {@code EQUAL}: whether two values are equal to within {@code 1e5} times the
     * larger of their scaled sum and {@code DBL_MIN}; two bad values are equal, and a bad value and
     * a good one are not. Unlike {@code astEQUAL}, whose floor is {@code 1e-12}, it finds only
     * values below about {@code 2e-303} equal to zero.
     */
    private static boolean equal(double aa, double bb) {
        if (aa == AST__BAD) {
            return bb == AST__BAD;
        }
        return bb != AST__BAD
                && Math.abs(aa - bb) <= 1.0e5 * Math.max((Math.abs(aa) + Math.abs(bb)) * DBL_EPSILON, DBL_MIN);
    }

    /** Whether the class of {@code m} is exactly {@code cls}, as C's comparison of class names tests. */
    private static boolean isClass(Mapping m, Class<?> cls) {
        return m.getClass() == cls;
    }

    /**
     * pcdmap.c {@code MapMerge}. A PcdMap whose coefficient is zero (by pcdmap.c's {@link #equal})
     * is replaced by a UnitMap, in series or in parallel, and nothing else is done. Otherwise, in
     * series only, the PcdMap is merged with the Mapping before it or, failing that, the one after
     * it, as {@link #canMerge} decides: with a PcdMap to give a UnitMap, with a UnitMap to give the
     * PcdMap itself, applied as before. Failing that, it is swapped past a neighbouring ZoomMap or
     * PermMap ({@link #swap}).
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        if (equal(getDiscoValue(), 0.0)) {
            list.set(where, new UnitMap(2), false);
            return where;
        }
        if (!series) {
            return -1;
        }
        Mapping lower = where > 0 ? list.map(where - 1) : null;
        Mapping upper = where < list.size() - 1 ? list.map(where + 1) : null;
        Mapping nclass;
        int i1;
        if (lower != null && canMerge(lower, list.map(where), list.invert(where - 1), list.invert(where))) {
            nclass = lower;
            i1 = where - 1;
        } else if (upper != null && canMerge(list.map(where), upper, list.invert(where), list.invert(where + 1))) {
            nclass = upper;
            i1 = where;
        } else {
            return swap(list, where, lower, upper, series);
        }
        if (isClass(nclass, PcdMap.class)) {
            list.replacePairWith(i1, new UnitMap(2), false);
        } else {
            list.replacePairWith(i1, list.map(where), list.invert(where));
        }
        return i1;
    }

    /**
     * C's {@code CanMerge} (pcdmap.c): whether {@code map1} followed by {@code map2}, applied with
     * the Invert values {@code inv1} and {@code inv2}, one of which is a PcdMap, can be merged:
     * the other is a UnitMap, or a PcdMap applied in the other direction whose coefficient and
     * centre, as their getters give them, are equal by pcdmap.c's {@link #equal}. C sets each
     * Mapping's Invert attribute to the value given while it looks at them; nothing it reads
     * depends on it, so here the values are compared directly.
     */
    private static boolean canMerge(Mapping map1, Mapping map2, boolean inv1, boolean inv2) {
        PcdMap pcd;
        Mapping nopcd;
        boolean pcdInv;
        boolean nopcdInv;
        if (isClass(map1, PcdMap.class)) {
            pcd = (PcdMap) map1;
            nopcd = map2;
            pcdInv = inv1;
            nopcdInv = inv2;
        } else if (isClass(map2, PcdMap.class)) {
            pcd = (PcdMap) map2;
            nopcd = map1;
            pcdInv = inv2;
            nopcdInv = inv1;
        } else {
            // C's "unusable": neither is a PcdMap.
            return false;
        }
        if (isClass(nopcd, UnitMap.class)) {
            return true;
        }
        if (isClass(nopcd, PcdMap.class)) {
            PcdMap pcd2 = (PcdMap) nopcd;
            return equal(pcd.getDiscoValue(), pcd2.getDiscoValue())
                    && equal(pcd.getPcdCenValue(0), pcd2.getPcdCenValue(0))
                    && equal(pcd.getPcdCenValue(1), pcd2.getPcdCenValue(1))
                    && pcdInv != nopcdInv;
        }
        return false;
    }

    /**
     * C's {@code CanSwap} (pcdmap.c): whether {@code map1} followed by {@code map2}, applied with
     * the Invert values {@code inv1} and {@code inv2}, one of which is a PcdMap, can be swapped:
     * the other is a ZoomMap, or a PermMap of two inputs and two outputs that exchanges them in
     * both directions (found by C's {@code PermGet}, with each Mapping's Invert attribute set to
     * the value given while it looks).
     */
    private static boolean canSwap(Mapping map1, Mapping map2, boolean inv1, boolean inv2) {
        int invert0 = map1.swapInvert(inv1);
        int invert1 = map2.swapInvert(inv2);
        try {
            Mapping nopcd = isClass(map1, PcdMap.class) ? map2 : map1;
            if (isClass(nopcd, ZoomMap.class)) {
                return true;
            }
            if (isClass(nopcd, PermMap.class) && nopcd.getNin() == 2 && nopcd.getNout() == 2) {
                WinMapMerge.PermParts perm = WinMapMerge.permGet((PermMap) nopcd);
                int[] outperm = perm.outperm;
                int[] inperm = perm.inperm;
                return outperm[0] == 1 && outperm[1] == 0 && inperm[0] == 1 && inperm[1] == 0;
            }
            return false;
        } finally {
            map1.restoreInvert(invert0);
            map2.restoreInvert(invert1);
        }
    }

    /**
     * The swap part of pcdmap.c {@code MapMerge}, in series, when the PcdMap at {@code where}
     * merges with neither neighbour. On each side whose neighbour it can be swapped past ({@link
     * #canSwap}), the Mappings up to the nearest UnitMap or PcdMap are counted, stopping at any that
     * is not a ZoomMap or PermMap. The PcdMap is swapped towards the nearer such Mapping (the upper
     * one on a tie), and the list from the new neighbours on is offered to the Mapping beginning
     * it, so that the PcdMap merges at once. With no such Mapping, the swap with the lower and then
     * the upper neighbour is made on copies, and kept only if one of the two swapped Mappings
     * simplifies to a Mapping of another class.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    private static int swap(MergeList list, int where, Mapping lower, Mapping upper, boolean series) {
        int nmap = list.size();
        boolean swaphi = false;
        if (where + 1 < nmap) {
            swaphi = canSwap(list.map(where), list.map(where + 1), list.invert(where), list.invert(where + 1));
        }
        int nstep2 = -1;
        if (swaphi) {
            for (int i2 = where + 1; i2 < nmap; i2++) {
                Mapping m = list.map(i2);
                if (isClass(m, UnitMap.class) || isClass(m, PcdMap.class)) {
                    nstep2 = i2 - where - 1;
                    break;
                }
                if (!isClass(m, ZoomMap.class) && !isClass(m, PermMap.class)) {
                    break;
                }
            }
        }

        boolean swaplo = false;
        if (where > 0) {
            swaplo = canSwap(list.map(where - 1), list.map(where), list.invert(where - 1), list.invert(where));
        }
        int nstep1 = -1;
        if (swaplo) {
            for (int i1 = where - 1; i1 >= 0; i1--) {
                Mapping m = list.map(i1);
                if (isClass(m, UnitMap.class) || isClass(m, PcdMap.class)) {
                    nstep1 = where - 1 - i1;
                    break;
                }
                if (!isClass(m, ZoomMap.class) && !isClass(m, PermMap.class)) {
                    break;
                }
            }
        }

        Mapping nclass;
        int i1;
        if (nstep1 != -1 && (nstep2 == -1 || nstep2 > nstep1)) {
            nclass = lower;
            i1 = where - 1;
        } else if (nstep2 != -1) {
            nclass = upper;
            i1 = where;
        } else {
            nclass = null;
            i1 = -1;
        }

        if (nclass != null) {
            swapPair(list, i1, where - i1, nclass);
            if (where == i1 && where + 1 < list.size()) {
                list.mergeSubrange(where + 1, 0, series);
            } else if (where - 2 >= 0) {
                list.mergeSubrange(where - 2, 0, series);
            }
            return i1;
        }

        if (swaphi || swaplo) {
            for (int i = 0; i < 2; i++) {
                Mapping neighbour;
                int j1;
                if (i == 0 && swaplo) {
                    neighbour = lower;
                    j1 = where - 1;
                } else if (i == 1 && swaphi) {
                    neighbour = upper;
                    j1 = where;
                } else {
                    continue;
                }
                MergeList mc = new MergeList(
                        Arrays.asList((Mapping) list.map(j1).copy(), (Mapping)
                                list.map(j1 + 1).copy()),
                        Arrays.asList(list.invert(j1), list.invert(j1 + 1)));
                swapPair(mc, 0, where - j1, neighbour);
                Mapping smc0 = mc.map(0).simplify();
                Mapping smc1 = mc.map(1).simplify();
                if (smc0.getClass() != mc.map(0).getClass()
                        || smc1.getClass() != mc.map(1).getClass()) {
                    list.set(j1, mc.map(0), mc.invert(0));
                    list.set(j1 + 1, mc.map(1), mc.invert(1));
                    return j1;
                }
            }
        }
        return -1;
    }

    /**
     * Swaps the PcdMap at {@code base + ipc} with the Mapping {@code nclass} beside it, by C's
     * {@code PcdZoom} or {@code PcdPerm}.
     */
    private static void swapPair(MergeList maps, int base, int ipc, Mapping nclass) {
        if (isClass(nclass, ZoomMap.class)) {
            pcdZoom(maps, base, ipc);
        } else if (isClass(nclass, PermMap.class)) {
            pcdPerm(maps, base, ipc);
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astMapMerge(PcdMap): Cannot swap a PcdMap with a " + nclass.astClass()
                            + " (internal AST programming error).");
        }
    }

    /**
     * C's {@code PcdZoom} (pcdmap.c): swaps a PcdMap and a ZoomMap applied in series, the entries
     * at {@code base} and {@code base + 1}, the PcdMap at {@code base + ipc}. The ZoomMap's factor
     * is its Zoom attribute, or the reciprocal when the list applies it inverted; the new ZoomMap
     * has that factor and is applied forward. The new PcdMap's centre is the old one's multiplied
     * (PcdMap first) or divided (PcdMap second) by the factor, and its coefficient the old one's
     * divided or multiplied by the square of the factor; it is applied with the Invert value of
     * the old PcdMap, and its own Invert attribute is set when that value is.
     */
    private static void pcdZoom(MergeList maps, int base, int ipc) {
        PcdMap pm = (PcdMap) maps.map(base + ipc);
        ZoomMap zm = (ZoomMap) maps.map(base + 1 - ipc);
        boolean pinv = maps.invert(base + ipc);
        boolean zinv = maps.invert(base + 1 - ipc);
        int oldPinv = pm.swapInvert(pinv);
        int oldZinv = zm.swapInvert(zinv);
        double zoom;
        double disc;
        double xcen;
        double ycen;
        try {
            zoom = zm.getZoom();
            if (zinv) {
                zoom = 1.0 / zoom;
            }
            disc = pm.getDiscoValue();
            xcen = pm.getPcdCenValue(0);
            ycen = pm.getPcdCenValue(1);
        } finally {
            pm.restoreInvert(oldPinv);
            zm.restoreInvert(oldZinv);
        }
        ZoomMap zm2 = new ZoomMap(2, zoom);
        double[] cen = new double[2];
        if (ipc == 0) {
            cen[0] = xcen * zoom;
            cen[1] = ycen * zoom;
            disc = disc / (zoom * zoom);
        } else {
            cen[0] = xcen / zoom;
            cen[1] = ycen / zoom;
            disc = disc * zoom * zoom;
        }
        PcdMap pm2 = new PcdMap(disc, cen);
        if (pinv) {
            pm2.invert();
        }
        maps.set(base + 1 - ipc, pm2, pinv);
        maps.set(base + ipc, zm2, false);
    }

    /**
     * C's {@code PcdPerm} (pcdmap.c): swaps a PcdMap and a PermMap that exchanges the two axes,
     * applied in series, the entries at {@code base} and {@code base + 1}, the PcdMap at {@code
     * base + ipc}. The PermMap itself, shared as C clones it, moves to the PcdMap's place with the
     * Invert value the list applied it with; the new PcdMap has the old one's coefficient and its
     * centre with the axes exchanged, and is applied with the Invert value of the old PcdMap, its
     * own Invert attribute being set when that value is.
     */
    private static void pcdPerm(MergeList maps, int base, int ipc) {
        PcdMap pm = (PcdMap) maps.map(base + ipc);
        Mapping rm = maps.map(base + 1 - ipc);
        boolean pinv = maps.invert(base + ipc);
        boolean rinv = maps.invert(base + 1 - ipc);
        int oldPinv = pm.swapInvert(pinv);
        int oldRinv = rm.swapInvert(rinv);
        double disc;
        double xcen;
        double ycen;
        try {
            disc = pm.getDiscoValue();
            xcen = pm.getPcdCenValue(0);
            ycen = pm.getPcdCenValue(1);
        } finally {
            pm.restoreInvert(oldPinv);
            rm.restoreInvert(oldRinv);
        }
        PcdMap pm2 = new PcdMap(disc, new double[] {ycen, xcen});
        if (pinv) {
            pm2.invert();
        }
        maps.set(base + ipc, rm, rinv);
        maps.set(base + 1 - ipc, pm2, pinv);
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Disco attribute: the pincushion/barrel distortion coefficient. Positive values give
     * pincushion distortion, negative values barrel distortion. The forward transformation
     * applies the distortion and the inverse removes it; if the PcdMap is inverted, the forward
     * transformation removes the distortion and the inverse applies it, the distortion still being
     * given by the same value. Default 0, which gives no distortion.
     *
     * @return the distortion coefficient
     */
    public double getDisco() {
        return getD("Disco");
    }

    /**
     * Sets the Disco attribute. {@link #AST__BAD} leaves it unset.
     *
     * @param disco the distortion coefficient
     * @throws AstException with status {@code AST__IMMUT} if the PcdMap is held by another Object,
     *     such as a CmpMap
     */
    public void setDisco(double disco) {
        setD("Disco", disco);
    }

    /**
     * Gets the PcdCen attribute for an axis: the coordinate on that axis of the centre of the
     * pincushion/barrel distortion. Default 0.
     *
     * @param axis the axis, 1 or 2
     * @return the coordinate of the centre on the axis
     * @throws IndexOutOfBoundsException if {@code axis} is not 1 or 2
     */
    public double getPcdCen(int axis) {
        if (axis >= 1 && axis <= 2) {
            return getD("PcdCen(" + axis + ")");
        } else {
            throw new IndexOutOfBoundsException("axis value " + axis + " is not in the range 1..2");
        }
    }

    /**
     * Sets the PcdCen attribute for an axis. {@link #AST__BAD} leaves it unset. Through the
     * generic attribute methods, a setting or clearing of {@code PcdCen} without an axis applies to
     * both axes, and a value got or tested is that of axis 1.
     *
     * @param axis the axis, 1 or 2
     * @param pcdCen the coordinate of the centre on the axis
     * @throws IndexOutOfBoundsException if {@code axis} is not 1 or 2
     * @throws AstException with status {@code AST__IMMUT} if the PcdMap is held by another Object,
     *     such as a CmpMap
     */
    public void setPcdCen(int axis, double pcdCen) {
        if (axis >= 1 && axis <= 2) {
            setD("PcdCen(" + axis + ")", pcdCen);
        } else {
            throw new IndexOutOfBoundsException("axis value " + axis + " is not in the range 1..2");
        }
    }

    /** Disco, its default when unset (C's {@code astGetDisco}). */
    final double getDiscoValue() {
        return disco == AST__BAD ? 0.0 : disco;
    }

    private boolean testDiscoValue() {
        return disco != AST__BAD;
    }

    /**
     * C's private {@code SetDisco}, from {@code astMAKE_SET1}: a shared PcdMap refuses the change;
     * a value different from the one stored (unset being {@link #AST__BAD}) discards the record of
     * simplification; {@link #AST__BAD} leaves the attribute unset.
     */
    private void setDiscoValue(double value) {
        checkSet1("Disco");
        if (value != disco) {
            clearIsSimple();
        }
        disco = value;
    }

    /** C's {@code ClearDisco}, from {@code astMAKE_CLEAR1}. */
    private void clearDiscoValue() {
        checkClear1("Disco");
        clearIsSimple();
        disco = AST__BAD;
    }

    /**
     * The error C's {@code MAKE_GET}, {@code MAKE_SET}, {@code MAKE_TEST} and {@code MAKE_CLEAR}
     * (pcdmap.c) report for an axis index, from zero, other than 0 or 1.
     */
    private void checkAxis(int axis, String method) {
        if (axis < 0 || axis >= 2) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Index (" + (axis + 1)
                            + ") is invalid for attribute PcdCen - it should be in the range 1 to 2.");
        }
    }

    /** PcdCen for the axis {@code axis} (from zero), its default when unset (C's {@code GetPcdCen}). */
    final double getPcdCenValue(int axis) {
        checkAxis(axis, "astGetPcdCen");
        return centre[axis] == AST__BAD ? 0.0 : centre[axis];
    }

    private boolean testPcdCenValue(int axis) {
        checkAxis(axis, "astTestPcdCen");
        return centre[axis] != AST__BAD;
    }

    /**
     * C's private {@code SetPcdCen}, from pcdmap.c's {@code MAKE_SET}: an axis other than 0 or 1
     * is refused; then a shared PcdMap refuses the change; a value different from the one stored
     * discards the record of simplification; {@link #AST__BAD} leaves the axis unset.
     */
    private void setPcdCenValue(int axis, double value) {
        checkAxis(axis, "astSetPcdCen");
        checkSet1("PcdCen");
        if (value != centre[axis]) {
            clearIsSimple();
        }
        centre[axis] = value;
    }

    /** C's private {@code ClearPcdCen}, from pcdmap.c's {@code MAKE_CLEAR}. */
    private void clearPcdCenValue(int axis) {
        checkAxis(axis, "astClearPcdCen");
        checkClear1("PcdCen");
        clearIsSimple();
        centre[axis] = AST__BAD;
    }

    /**
     * The axis number in {@code attrib} if it is {@code pcdcen(n)}, as C's {@code "pcdcen(%d)%n"}
     * reads it, or null.
     */
    private static Integer pcdCenAxis(String attrib) {
        return Frame.axisArg(attrib, "pcdcen");
    }

    /**
     * C's {@code GetAttrib}: each value formatted with {@code %.17g}; {@code PcdCen} without an
     * axis gives axis 1.
     */
    @Override
    String getAttrib(String attrib) {
        double dval;
        Integer axis = pcdCenAxis(attrib);
        if ("disco".equals(attrib)) {
            dval = getDiscoValue();
        } else if (axis != null) {
            dval = getPcdCenValue(axis - 1);
        } else if ("pcdcen".equals(attrib)) {
            dval = getPcdCenValue(0);
        } else {
            return super.getAttrib(attrib);
        }
        return CFormat.format("%.17g", dval);
    }

    /** C's {@code SetAttrib}: a value read as a double; {@code PcdCen} without an axis sets both. */
    @Override
    void setAttrib(String attrib, String value) {
        Double dval = CScan.scanDouble(value);
        Integer axis = pcdCenAxis(attrib);
        if (dval != null && "disco".equals(attrib)) {
            setDiscoValue(dval);
        } else if (dval != null && axis != null) {
            setPcdCenValue(axis - 1, dval);
        } else if (dval != null && "pcdcen".equals(attrib)) {
            setPcdCenValue(0, dval);
            setPcdCenValue(1, dval);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    /** C's {@code TestAttrib}: {@code PcdCen} without an axis tests axis 1. */
    @Override
    boolean testAttrib(String attrib) {
        Integer axis = pcdCenAxis(attrib);
        if ("disco".equals(attrib)) {
            return testDiscoValue();
        } else if ("pcdcen".equals(attrib)) {
            return testPcdCenValue(0);
        } else if (axis != null) {
            return testPcdCenValue(axis - 1);
        } else {
            return super.testAttrib(attrib);
        }
    }

    /** C's {@code ClearAttrib}: {@code PcdCen} without an axis clears both. */
    @Override
    void clearAttrib(String attrib) {
        Integer axis = pcdCenAxis(attrib);
        if (axis != null) {
            clearPcdCenValue(axis - 1);
        } else if ("pcdcen".equals(attrib)) {
            clearPcdCenValue(0);
            clearPcdCenValue(1);
        } else if ("disco".equals(attrib)) {
            clearDiscoValue();
        } else {
            super.clearAttrib(attrib);
        }
    }
}
