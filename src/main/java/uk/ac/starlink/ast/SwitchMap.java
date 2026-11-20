package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A Mapping which represents a set of alternate Mappings, each of which is used to transform
 * positions within a particular region of the input or output coordinate system of the SwitchMap.
 *
 * <p>A SwitchMap can encapsulate any number of Mappings, but they must all have the same number of
 * inputs (Nin attribute value) and the same number of outputs (Nout attribute value). The
 * SwitchMap itself inherits these same values for its Nin and Nout attributes. Each of these
 * Mappings represents a "route" through the switch, and is referred to as a "route" Mapping below.
 * Each route Mapping transforms positions between the input and output coordinate space of the
 * entire SwitchMap, but only one Mapping will be used to transform any given position. The
 * selection of the appropriate route Mapping to use with any given input position is made by
 * another Mapping, called the "selector" Mapping. Each SwitchMap encapsulates two selector
 * Mappings in addition to its route Mappings; one for use with the SwitchMap's forward
 * transformation (called the "forward selector Mapping"), and one for use with the SwitchMap's
 * inverse transformation (called the "inverse selector Mapping"). The forward selector Mapping
 * must have the same number of inputs as the route Mappings, but should have only one output.
 * Likewise, the inverse selector Mapping must have the same number of outputs as the route
 * Mappings, but should have only one input.
 *
 * <p>When the SwitchMap is used to transform a position in the forward direction (from input to
 * output), each supplied input position is first transformed by the forward transformation of the
 * forward selector Mapping. This produces a single output value for each input position referred
 * to as the selector value. The nearest integer to the selector value is found, and is used to
 * index the array of route Mappings (the first supplied route Mapping has index 1, the second
 * route Mapping has index 2, etc). If the nearest integer to the selector value is less than 1 or
 * greater than the number of route Mappings, then the SwitchMap output position is set to a value
 * of {@link #AST__BAD} on every axis. Otherwise, the forward transformation of the selected route
 * Mapping is used to transform the supplied input position to produce the SwitchMap output
 * position.
 *
 * <p>When the SwitchMap is used to transform a position in the inverse direction (from "output" to
 * "input"), each supplied "output" position is first transformed by the inverse transformation of
 * the inverse selector Mapping. This produces a selector value for each "output" position. Again,
 * the nearest integer to the selector value is found, and is used to index the array of route
 * Mappings. If this selector index value is within the bounds of the array of route Mappings, then
 * the inverse transformation of the selected route Mapping is used to transform the supplied
 * "output" position to produce the SwitchMap "input" position. If the selector index value is
 * outside the bounds of the array of route Mappings, then the SwitchMap "input" position is set to
 * a value of {@link #AST__BAD} on every axis.
 *
 * <p>In practice, appropriate selector Mappings should be chosen to associate a different route
 * Mapping with each region of coordinate space. Note that the {@link SelectorMap} class of Mapping
 * is particularly appropriate for this purpose.
 *
 * <p>If a compound Mapping contains a SwitchMap in series with its own inverse, the combination of
 * the two adjacent SwitchMaps will be replaced by a UnitMap when the compound Mapping is
 * simplified using {@link #simplify}.
 *
 * <p>A SwitchMap does not copy the Mappings it is given: it holds them, as C's holds cloned
 * pointers to them, and {@link #copy} copies them. Being shared, their attributes that C makes
 * immutable once cloned (such as a ZoomMap's Zoom) can no longer be changed. It records the Invert
 * attribute of each when it is made, and applies each in that orientation whatever is later done
 * to the Mapping it shares; their other attributes do change the SwitchMap, so they should not be
 * changed while the SwitchMap is in use.
 *
 * <p>Ported from Rust {@code mappings/switchmap.rs} and C {@code switchmap.c}.
 */
public class SwitchMap extends Mapping {

    /** The forward selector Mapping, or null if there is none (C's {@code fsmap}). */
    private Mapping fsmap;

    /** The inverse selector Mapping, or null if there is none (C's {@code ismap}). */
    private Mapping ismap;

    /** The route Mappings (C's {@code routemap}). */
    private Mapping[] routemap;

    /** The Invert attribute of the forward selector when the SwitchMap was made (C's {@code fsinv}). */
    private final boolean fsinv;

    /** The Invert attribute of the inverse selector when the SwitchMap was made (C's {@code isinv}). */
    private final boolean isinv;

    /** The Invert attribute of each route Mapping when the SwitchMap was made (C's {@code routeinv}). */
    private boolean[] routeinv;

    /**
     * Creates a SwitchMap.
     *
     * @param fsmap the forward selector Mapping. This must have a defined forward transformation,
     *     but need not have a defined inverse transformation. It must have one output, and the
     *     number of inputs must match the number of inputs of each of the supplied route Mappings.
     *     Null may be supplied, in which case the SwitchMap will have an undefined forward
     *     Mapping.
     * @param ismap the inverse selector Mapping. This must have a defined inverse transformation,
     *     but need not have a defined forward transformation. It must have one input, and the
     *     number of outputs must match the number of outputs of each of the supplied route
     *     Mappings. Null may be supplied, in which case the SwitchMap will have an undefined
     *     inverse Mapping.
     * @param routemaps the route Mappings. All the supplied route Mappings must have common
     *     values for the Nin and Nout attributes, and these values define the number of inputs
     *     and outputs of the SwitchMap.
     * @throws NullPointerException if {@code routemaps} is null
     * @throws AstException with status {@code AST__BDPAR} if no route Mappings are given, {@code
     *     AST__OBJIN} if one is null, {@code AST__BADNI} or {@code AST__BADNO} if the numbers of
     *     coordinates do not match, or {@code AST__INTRD} if a selector cannot transform in the
     *     direction it is used in or neither selector is given
     */
    public SwitchMap(Mapping fsmap, Mapping ismap, Mapping[] routemaps) {
        this(fsmap, ismap, routemaps, check(fsmap, ismap, routemaps, " Bad"));
    }

    /**
     * C's {@code astSwitchMap_} and {@code astInitSwitchMap} (switchmap.c), as the library itself
     * makes a SwitchMap: shares the Mappings and records their Invert attributes.
     *
     * @param library distinguishes this constructor from the public one
     */
    SwitchMap(Mapping fsmap, Mapping ismap, Mapping[] routemaps, boolean library) {
        this(fsmap, ismap, routemaps, check(fsmap, ismap, routemaps, "Bad"));
    }

    /** Makes the SwitchMap once {@link #check} has given its numbers of inputs and outputs. */
    private SwitchMap(Mapping fsmap, Mapping ismap, Mapping[] routemaps, int[] counts) {
        super(counts[0], counts[1], fsmap != null, ismap != null);
        this.fsmap = fsmap;
        this.ismap = ismap;
        fsinv = fsmap != null && fsmap.getInvert();
        isinv = ismap != null && ismap.getInvert();
        if (fsmap != null) {
            fsmap.markShared();
        }
        if (ismap != null) {
            ismap.markShared();
        }
        routemap = routemaps.clone();
        routeinv = new boolean[routemap.length];
        for (int i = 0; i < routemap.length; i++) {
            routemap[i].markShared();
            routeinv[i] = routemap[i].getInvert();
        }
    }

    /**
     * The numbers of inputs and outputs of a SwitchMap of these Mappings, after the checks C's
     * {@code astSwitchMap} and {@code astInitSwitchMap} make, in their order: the first error
     * found is the one reported.
     *
     * @param bad the start of the message for no route Mappings, which has an extra space when
     *     the public function ({@code astSwitchMapId_}) reports it
     */
    private static int[] check(Mapping fsmap, Mapping ismap, Mapping[] routemaps, String bad) {
        Objects.requireNonNull(routemaps, "routemaps");
        int nroute = routemaps.length;
        if (nroute <= 0) {
            throw Ast.err(
                    AstException.AST__BDPAR,
                    "astSwitchMap(SwitchMap): " + bad + " number of route Mappings (" + nroute + ") specified.");
        }
        for (Mapping m : routemaps) {
            if (m == null) {
                throw Ast.err(AstException.AST__OBJIN, "Invalid Object pointer given (value is zero).");
            }
        }
        int nin = routemaps[0].getNin();
        int nout = routemaps[0].getNout();
        for (int i = 1; i < nroute; i++) {
            if (nin != routemaps[i].getNin()) {
                throw Ast.err(
                        AstException.AST__BADNI,
                        "astInitSwitchMap(SwitchMap): Route Mapping number " + (i + 1) + " has "
                                + routemaps[i].getNin() + " input(s) but the first route Mapping has " + nin
                                + " input(s).");
            } else if (nout != routemaps[i].getNout()) {
                // C's message gives the numbers of inputs, not outputs.
                throw Ast.err(
                        AstException.AST__BADNO,
                        "astInitSwitchMap(SwitchMap): Route Mapping number " + (i + 1) + " has "
                                + routemaps[i].getNin() + " output(s) but the first route Mapping has " + nin
                                + " output(s).");
            }
        }
        if (fsmap != null) {
            if (!fsmap.getTranForward()) {
                throw Ast.err(
                        AstException.AST__INTRD,
                        "astInitSwitchMap(SwitchMap): The forward selector Mapping is not able to transform "
                                + "coordinates in the forward direction.");
            } else if (fsmap.getNin() != nin) {
                throw Ast.err(
                        AstException.AST__BADNI,
                        "astInitSwitchMap(SwitchMap): The forward selector Mapping has " + fsmap.getNin()
                                + " input(s) but the SwitchMap has " + nin + " input(s).");
            } else if (fsmap.getNout() != 1) {
                throw Ast.err(
                        AstException.AST__BADNO,
                        "astInitSwitchMap(SwitchMap): The forward selector Mapping has " + fsmap.getNout()
                                + " outputs but should only have 1.");
            }
        }
        if (ismap != null) {
            if (!ismap.getTranInverse()) {
                throw Ast.err(
                        AstException.AST__INTRD,
                        "astInitSwitchMap(SwitchMap): The inverse selector Mapping is not able to transform "
                                + "coordinates in the inverse direction.");
            } else if (nout != ismap.getNout()) {
                throw Ast.err(
                        AstException.AST__BADNO,
                        "astInitSwitchMap(SwitchMap): The inverse selector Mapping has " + ismap.getNout()
                                + " output(s) but the SwitchMap has " + nout + " output(s).");
            } else if (ismap.getNin() != 1) {
                throw Ast.err(
                        AstException.AST__BADNI,
                        "astInitSwitchMap(SwitchMap): The inverse selector Mapping has " + ismap.getNin()
                                + " inputs but should only have 1.");
            }
        }
        if (fsmap == null && ismap == null) {
            throw Ast.err(AstException.AST__INTRD, "astInitSwitchMap(SwitchMap): No selector Mappings supplied.");
        }
        return new int[] {nin, nout};
    }

    /**
     * Loads a SwitchMap from its description, as C's {@code astLoadSwitchMap} does: {@code FSMap}
     * and {@code FSInv} (false if absent), {@code ISMap} and {@code ISInv}, then {@code RMap1},
     * {@code RInv1}, {@code RMap2} and so on until a route Mapping is missing. As in C, an absent
     * {@code ISInv} takes the value of {@code FSInv}, and an absent {@code RInv<n>} is false.
     *
     * @throws AstException with status {@code AST__LDERR} if a component is not a Mapping
     */
    SwitchMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("SwitchMap");
        fsmap = component(part, "FSMap");
        fsinv = part.readInt("FSInv", 0) != 0;
        ismap = component(part, "ISMap");
        isinv = part.readInt("ISInv", fsinv ? 1 : 0) != 0;
        List<Mapping> maps = new ArrayList<>();
        List<Boolean> invs = new ArrayList<>();
        while (true) {
            Mapping rmap = component(part, "RMap" + (maps.size() + 1));
            if (rmap == null) {
                break;
            }
            invs.add(part.readInt("RInv" + (maps.size() + 1), 0) != 0);
            maps.add(rmap);
        }
        routemap = maps.toArray(new Mapping[0]);
        routeinv = new boolean[invs.size()];
        for (int i = 0; i < routeinv.length; i++) {
            routeinv[i] = invs.get(i);
        }
    }

    /** The Mapping {@code key} describes, or null if there is none. */
    private static Mapping component(NativeNode part, String key) {
        NativeNode desc = part.readObject(key);
        if (desc == null) {
            return null;
        }
        AstObject obj = ObjectRegistry.loadNested(desc, key);
        if (!(obj instanceof Mapping)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadSwitchMap(SwitchMap): The component " + key + " is a " + obj.astClass()
                            + ", not a Mapping.");
        }
        return (Mapping) obj;
    }

    @Override
    String dumpComment() {
        return "Alternate regionalised Mapping";
    }

    /**
     * Writes each selector there is with its Invert value recorded when the SwitchMap was made
     * (set when true), and then each route Mapping with its recorded Invert value, as C's {@code
     * Dump} (switchmap.c) does; each Mapping is written with its own Invert attribute as it now
     * is. As in C, a SwitchMap whose forward selector was recorded inverted and whose inverse
     * selector was not loads back with its inverse selector inverted, since {@code ISInv} is
     * written only when true and the loader takes its absence as {@code FSInv}.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        if (fsmap != null) {
            node.writeObject("FSMap", true, true, NativeNode.of(fsmap), "Forward selector Mapping");
            node.writeInt(
                    "FSInv",
                    fsinv,
                    false,
                    fsinv ? 1 : 0,
                    fsinv ? "Fwd selector used in inverse direction" : "Fwd selector used in forward direction");
        }
        if (ismap != null) {
            node.writeObject("ISMap", true, true, NativeNode.of(ismap), "Inverse selector Mapping");
            node.writeInt(
                    "ISInv",
                    isinv,
                    false,
                    isinv ? 1 : 0,
                    isinv ? "Inv selector used in inverse direction" : "Inv selector used in forward direction");
        }
        for (int i = 0; i < routemap.length; i++) {
            node.writeObject("RMap" + (i + 1), true, true, NativeNode.of(routemap[i]), "Route Mapping");
            boolean inv = routeinv[i];
            node.writeInt(
                    "RInv" + (i + 1),
                    inv,
                    false,
                    inv ? 1 : 0,
                    inv ? "Route Mapping used in inverse direction" : "Route Mapping used in forward direction");
        }
    }

    /** Gives the copy copies of the selectors and route Mappings, as C's {@code Copy} (switchmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (fsmap != null) {
            fsmap = (Mapping) fsmap.copy();
        }
        if (ismap != null) {
            ismap = (Mapping) ismap.copy();
        }
        Mapping[] maps = new Mapping[routemap.length];
        for (int i = 0; i < maps.length; i++) {
            maps[i] = (Mapping) routemap[i].copy();
        }
        routemap = maps;
        routeinv = routeinv.clone();
    }

    /**
     * switchmap.c {@code RemoveRegions}: removes the Regions from each route Mapping and each
     * selector. A Mapping that changed and is now a Frame becomes a UnitMap; one that did not change
     * is kept as it is, a Frame included. When none changed it is the SwitchMap itself; otherwise it
     * is a copy of the SwitchMap holding the new Mappings, sharing with the original those that did
     * not change, as C's copy holds clones of them.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping removeRegions() {
        boolean changed = false;
        Mapping[] newroutes = new Mapping[routemap.length];
        for (int i = 0; i < routemap.length; i++) {
            newroutes[i] = routemap[i].removeRegions();
            if (newroutes[i] != routemap[i]) {
                changed = true;
                newroutes[i] = frameToUnitMap(newroutes[i]);
            }
        }
        Mapping newfsmap = null;
        if (fsmap != null) {
            newfsmap = fsmap.removeRegions();
            if (newfsmap != fsmap) {
                changed = true;
                newfsmap = frameToUnitMap(newfsmap);
            }
        }
        Mapping newismap = null;
        if (ismap != null) {
            newismap = ismap.removeRegions();
            if (newismap != ismap) {
                changed = true;
                newismap = frameToUnitMap(newismap);
            }
        }
        if (!changed) {
            return this;
        }
        SwitchMap result = (SwitchMap) copy();
        for (int i = 0; i < routemap.length; i++) {
            result.routemap[i] = keptComponent(newroutes[i], routemap[i]);
        }
        if (newfsmap != null) {
            result.fsmap = keptComponent(newfsmap, fsmap);
        }
        if (newismap != null) {
            result.ismap = keptComponent(newismap, ismap);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Selectors and routes
    // ------------------------------------------------------------------

    /**
     * C's {@code GetSelector} (switchmap.c): the selector Mapping a transformation in direction
     * {@code fwd} of the SwitchMap as it now is uses, or null if there is none. Its Invert
     * attribute is set to the value recorded when the SwitchMap was made, and inverted if the
     * SwitchMap is inverted, so that applied in direction {@code fwd} it gives the selector value;
     * {@code saved[0]} receives the attribute as it was, for {@link Mapping#restoreInvert}.
     */
    private Mapping getSelector(boolean fwd, int[] saved) {
        boolean swinv = getInvert();
        Mapping ret;
        if ((!swinv && !fwd) || (swinv && fwd)) {
            ret = ismap;
            if (ret != null) {
                saved[0] = ret.swapInvert(isinv);
            }
        } else {
            ret = fsmap;
            if (ret != null) {
                saved[0] = ret.swapInvert(fsinv);
            }
        }
        if (ret != null && swinv) {
            ret.invert();
        }
        return ret;
    }

    /**
     * C's {@code GetRoute} (switchmap.c): the route Mapping the selector value {@code sel} picks,
     * or null if it is bad or picks none. Its Invert attribute is set to the value recorded when
     * the SwitchMap was made, and inverted if the SwitchMap is inverted, so that it transforms in
     * the direction the SwitchMap does; {@code saved[0]} receives the attribute as it was, for
     * {@link Mapping#restoreInvert}.
     */
    private Mapping getRoute(double sel, int[] saved) {
        if (sel == AST__BAD) {
            return null;
        }
        int rindex = routeIndex(sel);
        if (rindex < 0 || rindex >= routemap.length) {
            return null;
        }
        Mapping ret = routemap[rindex];
        saved[0] = ret.swapInvert(routeinv[rindex]);
        if (getInvert()) {
            ret.invert();
        }
        return ret;
    }

    /**
     * The zero-based index of the route a good selector value picks, as C computes it: {@code
     * (int) round(sel) - 1}, which may be outside the routes. C converts without a range check, so
     * a value beyond the range of {@code int}, an infinity or a NaN makes the conversion undefined,
     * as does the {@code - 1} after a conversion giving {@code INT_MIN}; Java's conversion is
     * defined, giving 0 for a NaN and the nearest {@code int} for any other value, and {@code
     * Integer.MIN_VALUE - 1} wraps to {@code Integer.MAX_VALUE}, so each of these gives an index
     * outside the routes, which picks no route, as C's documentation says of an index below 1 or
     * above the number of routes.
     */
    private static int routeIndex(double sel) {
        return (int) CMath.roundHalfAway(sel) - 1;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * C's {@code Transform} (switchmap.c): the selector Mapping for the direction gives a selector
     * value for each point, and each route Mapping transforms, in one call, the points whose
     * selector value picks it. A point whose selector value is bad or picks no route is bad on
     * every output.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        int ncin = forward ? getNin() : getNout();
        int ncout = forward ? getNout() : getNin();
        int nroute = routemap.length;
        int[] selinv = new int[1];
        Mapping selmap = getSelector(forward, selinv);
        try {
            double[] sel = selmap.transform(in, npoint, forward)[0];
            int[] popmap = new int[nroute];
            for (int p = 0; p < npoint; p++) {
                if (sel[p] != AST__BAD) {
                    int rindex = routeIndex(sel[p]);
                    if (rindex >= 0 && rindex < nroute) {
                        popmap[rindex]++;
                    }
                }
            }
            int totpop = 0;
            for (int pop : popmap) {
                totpop += pop;
            }
            if (totpop < npoint) {
                for (int j = 0; j < ncout; j++) {
                    for (int p = 0; p < npoint; p++) {
                        out[j][p] = AST__BAD;
                    }
                }
            }
            for (int iroute = 0; iroute < nroute; iroute++) {
                if (popmap[iroute] > 0) {
                    applyRoute(iroute, popmap[iroute], sel, in, npoint, forward, ncin, ncout, out);
                }
            }
        } finally {
            selmap.restoreInvert(selinv[0]);
        }
    }

    /**
     * Transforms with route {@code iroute} the {@code pop} points whose selector value picks it,
     * gathered in order, and puts the results at those points of {@code out}.
     */
    private void applyRoute(
            int iroute,
            int pop,
            double[] sel,
            double[][] in,
            int npoint,
            boolean forward,
            int ncin,
            int ncout,
            double[][] out) {
        int[] rinv = new int[1];
        Mapping rmap = getRoute(iroute + 1, rinv);
        try {
            double[][] sub = new double[ncin][pop];
            int k = 0;
            for (int p = 0; p < npoint; p++) {
                if (sel[p] != AST__BAD && routeIndex(sel[p]) == iroute) {
                    for (int j = 0; j < ncin; j++) {
                        sub[j][k] = in[j][p];
                    }
                    k++;
                }
            }
            double[][] res = rmap.transform(sub, pop, forward);
            k = 0;
            for (int p = 0; p < npoint; p++) {
                if (sel[p] != AST__BAD && routeIndex(sel[p]) == iroute) {
                    for (int j = 0; j < ncout; j++) {
                        out[j][p] = res[j][k];
                    }
                    k++;
                }
            }
        } finally {
            rmap.restoreInvert(rinv[0]);
        }
    }

    /**
     * C's {@code Rate} (switchmap.c): the rate of the route Mapping the forward selector of the
     * SwitchMap as it now is picks at {@code at}, in the orientation the SwitchMap applies it; bad
     * if there is no forward selector or it picks no route.
     */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        int[] fsinvOld = new int[1];
        Mapping smap = getSelector(true, fsinvOld);
        if (smap == null) {
            return AST__BAD;
        }
        try {
            int nin = smap.getNin();
            double[][] pos = new double[nin][1];
            for (int i = 0; i < nin; i++) {
                pos[i][0] = at[i];
            }
            double sel = smap.transform(pos, 1, true)[0][0];
            int[] rinv = new int[1];
            Mapping rmap = getRoute(sel, rinv);
            if (rmap == null) {
                return AST__BAD;
            }
            try {
                return rmap.rateAt(at, ax1, ax2);
            } finally {
                rmap.restoreInvert(rinv[0]);
            }
        } finally {
            smap.restoreInvert(fsinvOld[0]);
        }
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * switchmap.c {@code Equal}: {@code that} is a SwitchMap with the same number of route
     * Mappings, whose forward selectors, inverse selectors and routes, each as the two SwitchMaps
     * now apply it ({@link #getSelector}, {@link #getRoute}), are equal ({@link #astEqual}) or
     * both absent. The Invert attributes are set back in C's order, the second SwitchMap's first,
     * so that a Mapping both SwitchMaps share is left as it was.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof SwitchMap)) {
            return false;
        }
        SwitchMap other = (SwitchMap) that;
        int nroute = routemap.length;
        if (other.routemap.length != nroute) {
            return false;
        }
        boolean result = false;
        int[] fsinv1 = new int[1];
        int[] fsinv2 = new int[1];
        Mapping fsmap1 = getSelector(true, fsinv1);
        Mapping fsmap2 = other.getSelector(true, fsinv2);
        try {
            if (sameSelector(fsmap1, fsmap2)) {
                int[] isinv1 = new int[1];
                int[] isinv2 = new int[1];
                Mapping ismap1 = getSelector(false, isinv1);
                Mapping ismap2 = other.getSelector(false, isinv2);
                try {
                    if (sameSelector(ismap1, ismap2)) {
                        result = true;
                        for (int i = 0; result && i < nroute; i++) {
                            int[] rinv1 = new int[1];
                            int[] rinv2 = new int[1];
                            Mapping rmap1 = getRoute(i + 1, rinv1);
                            Mapping rmap2 = other.getRoute(i + 1, rinv2);
                            try {
                                result = rmap1.astEqual(rmap2);
                            } finally {
                                rmap2.restoreInvert(rinv2[0]);
                                rmap1.restoreInvert(rinv1[0]);
                            }
                        }
                    }
                } finally {
                    if (ismap2 != null) {
                        ismap2.restoreInvert(isinv2[0]);
                    }
                    if (ismap1 != null) {
                        ismap1.restoreInvert(isinv1[0]);
                    }
                }
            }
        } finally {
            if (fsmap2 != null) {
                fsmap2.restoreInvert(fsinv2[0]);
            }
            if (fsmap1 != null) {
                fsmap1.restoreInvert(fsinv1[0]);
            }
        }
        return result;
    }

    /** Whether two selectors are both absent, or both present and equal ({@link #astEqual}). */
    private static boolean sameSelector(Mapping a, Mapping b) {
        return (a == null && b == null) || (a != null && b != null && a.astEqual(b));
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * switchmap.c {@code MapMerge}. With the selectors and route Mappings set, for the duration,
     * to the Invert values recorded when the SwitchMap was made: in series, the SwitchMap after it
     * in the list, or failing that the one before it, is inverted and compared with it ({@link
     * #astEqual}, each with its own Invert attribute), and if they are equal the pair is replaced
     * by a UnitMap of the lower one's number of inputs. Otherwise, a SwitchMap the list applies
     * inverted is replaced by the forward SwitchMap of its selectors inverted and exchanged and its
     * route Mappings inverted; one it applies forward is replaced, if simplifying any of its
     * Mappings gives a different Mapping, by the SwitchMap of the simplified Mappings.
     *
     * <p>As C does, the Invert attributes are set and then restored in the same order, the forward
     * selector, the inverse selector, then the routes in turn, so a Mapping held twice is left with
     * the value set for the first of its places rather than its own.
     */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        int nroute = routemap.length;
        int fsinvOld = fsmap != null ? fsmap.swapInvert(fsinv) : 0;
        int isinvOld = ismap != null ? ismap.swapInvert(isinv) : 0;
        int[] rinvOld = new int[nroute];
        for (int i = 0; i < nroute; i++) {
            rinvOld[i] = routemap[i].swapInvert(routeinv[i]);
        }
        try {
            int result = -1;
            if (series) {
                result = cancelWithNeighbour(this, list, where, SwitchMap.class);
            }
            if (result == -1) {
                if (list.invert(where)) {
                    if (fsmap != null) {
                        fsmap.setInvert(!fsinv);
                    }
                    if (ismap != null) {
                        ismap.setInvert(!isinv);
                    }
                    for (int i = 0; i < nroute; i++) {
                        routemap[i].setInvert(!routeinv[i]);
                    }
                    list.set(where, new SwitchMap(ismap, fsmap, routemap, true), false);
                    result = where;
                } else {
                    Mapping sfsmap = fsmap != null ? fsmap.simplify() : null;
                    Mapping sismap = ismap != null ? ismap.simplify() : null;
                    boolean simp = sfsmap != fsmap || sismap != ismap;
                    Mapping[] srmap = new Mapping[nroute];
                    for (int i = 0; i < nroute; i++) {
                        srmap[i] = routemap[i].simplify();
                        simp = simp || srmap[i] != routemap[i];
                    }
                    if (simp) {
                        list.set(where, new SwitchMap(sfsmap, sismap, srmap, true), list.invert(where));
                        result = where;
                    }
                }
            }
            return result;
        } finally {
            if (fsmap != null) {
                fsmap.restoreInvert(fsinvOld);
            }
            if (ismap != null) {
                ismap.restoreInvert(isinvOld);
            }
            for (int i = 0; i < nroute; i++) {
                routemap[i].restoreInvert(rinvOld[i]);
            }
        }
    }
}
