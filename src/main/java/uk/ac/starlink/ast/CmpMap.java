package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A Mapping which connects two component Mappings, of any class, to form a more complex Mapping.
 * The connection is either in series, where the first Mapping transforms the coordinates of each
 * point and the second is then applied to the result, or in parallel, where the first Mapping
 * transforms the earlier coordinates of each point and the second simultaneously transforms the
 * later ones. In series, the inverse transformation applies the components in the reverse order
 * and the reverse direction; in parallel, it applies each to the same coordinates in the reverse
 * direction. A CmpMap is itself a Mapping, so it can be a component of further CmpMaps, and
 * Mappings of any complexity can be built from simple ones.
 *
 * <p>A CmpMap does not copy its components: it holds the Mappings it is given, as C's holds
 * cloned pointers to them, and {@link #copy} copies them. Being shared, a component's attributes
 * that C makes immutable once cloned (such as a ZoomMap's Zoom) can no longer be changed. It records each component's Invert
 * attribute when it is made, and applies each component in that orientation whatever is later
 * done to the Mapping it shares; the Invert attribute of a component therefore does not change the
 * CmpMap. Its other attributes do, so a component should not be changed while the CmpMap is in
 * use. Only the CmpMap's own Report attribute is used: its components do not report.
 *
 * <p>Ported from Rust {@code mappings/cmpmap.rs} and C {@code cmpmap.c}.
 */
public class CmpMap extends Mapping {

    /** The most points cmpmap.c {@code Transform} passes through a series at once. */
    private static final int SERIES_BATCH = 8192;

    /** The first component. */
    private Mapping map1;

    /** The second component. */
    private Mapping map2;

    /** Whether the components are applied in series (otherwise in parallel). */
    private final boolean series;

    /** The Invert attribute of the first component when the CmpMap was made (C's {@code invert1}). */
    private final boolean invert1;

    /** The Invert attribute of the second component when the CmpMap was made (C's {@code invert2}). */
    private final boolean invert2;

    /**
     * Creates a CmpMap.
     *
     * <p>In series, the number of output coordinates of {@code map1} must equal the number of
     * input coordinates of {@code map2}; in parallel there is no restriction. Each direction of
     * the CmpMap is defined when it is defined for both components. A CmpMap is made even when it
     * cannot transform in either direction. A FrameSet given as a component is replaced by its
     * Mapping from its base Frame to its current Frame.
     *
     * @param map1 the first component Mapping
     * @param map2 the second component Mapping
     * @param series true to connect the Mappings in series, false to connect them in parallel
     * @throws AstException with status {@code AST__INNCO} if, in series, the numbers of
     *     coordinates do not match
     */
    public CmpMap(Mapping map1, Mapping map2, boolean series) {
        this(map1, map2, series, true);
    }

    /** Creates a CmpMap, marking its components shared if {@code share}. */
    private CmpMap(Mapping map1, Mapping map2, boolean series, boolean share) {
        super(
                count(map1, map2, series, true),
                count(map1, map2, series, false),
                map1.getTranForward() && map2.getTranForward(),
                map1.getTranInverse() && map2.getTranInverse());
        this.map1 = component(map1, share);
        this.map2 = component(map2, share);
        this.series = series;
        invert1 = this.map1.getInvert();
        invert2 = this.map2.getInvert();
    }

    /**
     * The Mapping a CmpMap holds for {@code map} (C's {@code astInitCmpMap}): for a FrameSet, its
     * Mapping from its base Frame to its current Frame, which only the CmpMap refers to; for any
     * other Mapping, the Mapping itself, marked shared if {@code share}.
     */
    private static Mapping component(Mapping map, boolean share) {
        if (map instanceof FrameSet) {
            return ((FrameSet) map).getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        }
        if (share) {
            map.markShared();
        }
        return map;
    }

    /**
     * A CmpMap made within a computation, which C annuls before returning, so that its components'
     * reference counts return to what they were: the components are not marked shared. The CmpMap
     * must not be returned to a caller, other than as a copy.
     */
    static CmpMap transientCmpMap(Mapping map1, Mapping map2, boolean series) {
        return new CmpMap(map1, map2, series, false);
    }

    /**
     * The number of input coordinates of a CmpMap of {@code map1} and {@code map2} if {@code in},
     * otherwise of output coordinates, after the check C's {@code astInitCmpMap} makes of a series.
     */
    private static int count(Mapping map1, Mapping map2, boolean series, boolean in) {
        Objects.requireNonNull(map1, "map1");
        Objects.requireNonNull(map2, "map2");
        if (series) {
            int nout1 = map1.getNout();
            int nin2 = map2.getNin();
            if (nout1 != nin2) {
                throw Ast.err(
                        AstException.AST__INNCO,
                        "astInitCmpMap(CmpMap): The number of output coordinates per point (" + nout1
                                + ") for the first Mapping supplied does not match the number of input coordinates ("
                                + nin2 + ") for the second Mapping.");
            }
            return in ? map1.getNin() : map2.getNout();
        }
        return in ? map1.getNin() + map2.getNin() : map1.getNout() + map2.getNout();
    }

    /**
     * Loads a CmpMap from its description, as C's {@code astLoadCmpMap} does: {@code Series}
     * (true if absent), {@code InvA} and {@code InvB} (false if absent) and the components {@code
     * MapA} and {@code MapB}.
     *
     * @throws AstException with status {@code AST__LDERR} if a component is missing or is not a
     *     Mapping
     */
    CmpMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("CmpMap");
        series = part.readInt("Series", 1) != 0;
        invert1 = part.readInt("InvA", 0) != 0;
        invert2 = part.readInt("InvB", 0) != 0;
        map1 = component(part, "MapA");
        map2 = component(part, "MapB");
    }

    /** The component Mapping {@code key} describes. */
    private static Mapping component(NativeNode part, String key) {
        NativeNode desc = part.readObject(key);
        if (desc == null) {
            throw Ast.err(
                    AstException.AST__LDERR, "astLoadCmpMap(CmpMap): The component Mapping " + key + " is missing.");
        }
        AstObject obj = ObjectRegistry.load(desc);
        if (!(obj instanceof Mapping)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadCmpMap(CmpMap): The component " + key + " is a " + obj.astClass() + ", not a Mapping.");
        }
        return (Mapping) obj;
    }

    @Override
    String dumpComment() {
        return "Compound Mapping";
    }

    /**
     * Writes {@code Series} (set when the components are in parallel), {@code InvA} and {@code
     * InvB} (set when true), the Invert values recorded when the CmpMap was made, and the
     * components, each with its own Invert attribute as it now is, as C's {@code Dump} (cmpmap.c)
     * does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeInt(
                "Series",
                !series,
                false,
                series ? 1 : 0,
                series ? "Component Mappings applied in series" : "Component Mappings applied in parallel");
        node.writeInt(
                "InvA",
                invert1,
                false,
                invert1 ? 1 : 0,
                invert1 ? "First Mapping used in inverse direction" : "First Mapping used in forward direction");
        node.writeInt(
                "InvB",
                invert2,
                false,
                invert2 ? 1 : 0,
                invert2 ? "Second Mapping used in inverse direction" : "Second Mapping used in forward direction");
        node.writeObject("MapA", true, true, NativeNode.of(map1), "First component Mapping");
        node.writeObject("MapB", true, true, NativeNode.of(map2), "Second component Mapping");
    }

    /** Gives the copy copies of the components, as C's {@code Copy} (cmpmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        map1 = (Mapping) map1.copy();
        map2 = (Mapping) map2.copy();
    }

    /** Whether the CmpMap is linear: true when both its components are (cmpmap.c {@code GetIsLinear}). */
    @Override
    boolean isLinear() {
        return map1.isLinear() && map2.isLinear();
    }

    // ------------------------------------------------------------------
    // Decomposition and splitting
    // ------------------------------------------------------------------

    /**
     * The components, as C's {@code Decompose} (cmpmap.c) gives them: an inverted series gives
     * them in reverse order with their recorded Invert values reversed; an inverted parallel
     * CmpMap gives them in order with the Invert values reversed.
     */
    @Override
    Mapping[] decomposeInto(boolean[] flags) {
        boolean inv = getInvert();
        flags[0] = series;
        if (series && inv) {
            flags[1] = !invert2;
            flags[2] = !invert1;
            return new Mapping[] {map2, map1};
        }
        flags[1] = inv != invert1;
        flags[2] = inv != invert2;
        return new Mapping[] {map1, map2};
    }

    /**
     * Appends the components, as C's {@code MapList} (cmpmap.c) does, when the CmpMap combines them
     * as the list requires and may be simplified: each with its recorded Invert value, reversed if
     * the CmpMap is applied inverted, and in reverse order for an inverted series. Otherwise the
     * CmpMap is appended whole.
     */
    @Override
    boolean mapList(boolean series, boolean invert, List<Mapping> maps, List<Boolean> inverts) {
        if (this.series != series || doNotSimplify()) {
            return super.mapList(series, invert, maps, inverts);
        }
        boolean inv1 = invert != invert1;
        boolean inv2 = invert != invert2;
        boolean r1;
        boolean r2;
        if (series && invert) {
            r1 = map2.mapList(series, inv2, maps, inverts);
            r2 = map1.mapList(series, inv1, maps, inverts);
        } else {
            r1 = map1.mapList(series, inv1, maps, inverts);
            r2 = map2.mapList(series, inv2, maps, inverts);
        }
        return invert || r1 || r2;
    }

    /** C's {@code MapSplit} (cmpmap.c): {@link #mapSplit0} allowing the inverse search. */
    @Override
    MapSplit splitOff(int[] in) {
        return mapSplit0(in, false);
    }

    /**
     * C's {@code MapSplit0} (cmpmap.c). The CmpMap is decomposed into the Mappings it applies in
     * series, or in parallel, with {@code astMapList}, and each, given the Invert value it had when
     * it was combined for the duration, is split ({@link #mapSplit1}): in series, the outputs split
     * from one are the inputs chosen from the next; in parallel, the chosen inputs, put in order by
     * a PermMap if necessary, are shared among the Mappings they feed. If that fails, and unless
     * {@code reentry}, the inverse is searched ({@link #mapSplit2}).
     */
    private MapSplit mapSplit0(int[] in, boolean reentry) {
        int npin = getNin();
        int npout = getNout();
        MapSplit result = null;
        if (MapSplit.allInputs(in, npin)) {
            List<Mapping> maps = new ArrayList<>();
            List<Boolean> inverts = new ArrayList<>();
            mapList(series, getInvert(), maps, inverts);
            int nmap = maps.size();
            // C compares the pointers: the CmpMap itself was not decomposed.
            @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
            boolean whole = nmap == 1 && maps.get(0) == this;
            if (whole) {
                result = null;
            } else if (series) {
                result = splitSeries(in, maps, inverts);
            } else {
                result = splitParallel(in, maps, inverts, npout);
            }
        }
        if (result == null && !reentry) {
            result = mapSplit2(this, in);
        }
        return result;
    }

    /** The series part of {@link #mapSplit0}: the split of each Mapping feeds the next. */
    private static MapSplit splitSeries(int[] in, List<Mapping> maps, List<Boolean> inverts) {
        int[] cin = in.length == 0 ? null : in.clone();
        Mapping map = null;
        for (int imap = 0; imap < maps.size() && cin != null; imap++) {
            Mapping m = maps.get(imap);
            MapSplit split = withInvert(m, inverts.get(imap), cin);
            if (split != null) {
                cin = split.outputs;
                map = map == null ? split.map : transientCmpMap(map, split.map, true);
            } else {
                cin = null;
            }
        }
        return cin == null ? null : new MapSplit(cin, map);
    }

    /**
     * The parallel part of {@link #mapSplit0}: the chosen inputs, in ascending order, are shared
     * among the Mappings whose inputs they are, each Mapping from which inputs are chosen must
     * split, and a PermMap puts the inputs in that order first if they were not.
     */
    // C's bubble sort swaps elements, which this rule takes for a copy.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private static MapSplit splitParallel(int[] in, List<Mapping> maps, List<Boolean> inverts, int npout) {
        int nin = in.length;
        // Sort the indices of the chosen inputs by input, as C's bubble sort does.
        int[] outp = new int[nin];
        for (int i = 0; i < nin; i++) {
            outp[i] = i;
        }
        for (int j = nin - 1; j > 0; j--) {
            for (int i = 0; i < j; i++) {
                if (in[outp[i]] > in[outp[i + 1]]) {
                    int t = outp[i];
                    outp[i] = outp[i + 1];
                    outp[i + 1] = t;
                }
            }
        }
        int[] inp = new int[nin];
        boolean doperm = false;
        for (int i = 0; i < nin; i++) {
            if (outp[i] != i) {
                doperm = true;
            }
            inp[outp[i]] = i;
        }
        PermMap pmap = doperm ? PermMap.fromInternal(nin, inp, nin, outp, null) : null;
        for (int i = 0; i < nin; i++) {
            inp[i] = in[outp[i]];
        }
        int itop = -1;
        int ibotout = 0;
        int iin = 0;
        int ncout = 0;
        int[] cout = new int[npout];
        int[] cin = new int[nin];
        Mapping map = null;
        for (int imap = 0; imap < maps.size(); imap++) {
            Mapping m = maps.get(imap);
            boolean oldInv = m.getInvert();
            m.setInvert(inverts.get(imap));
            try {
                int ibot = itop + 1;
                itop += m.getNin();
                int ncin = 0;
                while (iin < nin && inp[iin] <= itop) {
                    cin[ncin++] = inp[iin++] - ibot;
                }
                if (ncin > 0) {
                    MapSplit split = mapSplit1(m, Arrays.copyOf(cin, ncin));
                    if (split == null) {
                        return null;
                    }
                    int naout = split.map.getNout();
                    for (int iout = 0; iout < naout; iout++) {
                        cout[ncout++] = split.outputs[iout] + ibotout;
                    }
                    map = map == null ? split.map : transientCmpMap(map, split.map, false);
                }
                ibotout += m.getNout();
            } finally {
                m.setInvert(oldInv);
            }
        }
        if (ncout == 0) {
            return null;
        }
        if (doperm) {
            map = transientCmpMap(pmap, map, true);
        }
        return new MapSplit(Arrays.copyOf(cout, ncout), map);
    }

    /**
     * {@link #mapSplit1} of a component with its Invert attribute set, for the duration, to the
     * value it had when it was combined, as C's {@code MapSplit0} sets it and then sets it back
     * (so that, as in C, the attribute is left set).
     */
    private static MapSplit withInvert(Mapping m, boolean invert, int[] in) {
        boolean oldInv = m.getInvert();
        m.setInvert(invert);
        try {
            return mapSplit1(m, in);
        } finally {
            m.setInvert(oldInv);
        }
    }

    /**
     * C's {@code MapSplit1} (cmpmap.c): a Mapping's split ({@code astMapSplit}), or, if there is
     * none, the split its inverse gives ({@link #mapSplit2}).
     */
    private static MapSplit mapSplit1(Mapping m, int[] in) {
        MapSplit result = m.mapSplit(in);
        return result != null ? result : mapSplit2(m, in);
    }

    /**
     * C's {@code MapSplit2} (cmpmap.c): searches the inverse of a Mapping for a set of its outputs
     * which feeds exactly the chosen inputs, in the order chosen, trying every subset of fewer
     * than all the outputs in turn, in every order; the split is then the inverse of the split of
     * the inverse. A CmpMap's inverse is split without searching its own inverse again.
     */
    private static MapSplit mapSplit2(Mapping m, int[] in) {
        int nin = in.length;
        int nout = m.getNout();
        Mapping this2 = (Mapping) m.copy();
        this2.invert();
        boolean iscmp = m instanceof CmpMap;
        int[] out = new int[nout];
        for (int mout = 1; mout < nout; mout++) {
            Arrays.fill(out, 0, mout, 0);
            while (true) {
                boolean ok = true;
                for (int i = 1; i < mout && ok; i++) {
                    for (int j = 0; j < i; j++) {
                        if (out[i] == out[j]) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok) {
                    int[] sel = Arrays.copyOf(out, mout);
                    MapSplit result2 = iscmp ? ((CmpMap) this2).mapSplit0(sel, true) : this2.mapSplit(sel);
                    if (result2 != null
                            && result2.map.getNout() == nin
                            && Arrays.equals(Arrays.copyOf(result2.outputs, nin), in)) {
                        result2.map.invert();
                        return new MapSplit(sel, result2.map);
                    }
                }
                // Step to the next subset, the first index fastest.
                int i = 0;
                out[i]++;
                while (out[i] == nout) {
                    out[i++] = 0;
                    if (i < mout) {
                        out[i]++;
                    } else {
                        break;
                    }
                }
                if (i == mout) {
                    break;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    /** The first component, shared with the CmpMap. */
    Mapping getMap1() {
        return map1;
    }

    /** The second component, shared with the CmpMap. */
    Mapping getMap2() {
        return map2;
    }

    /** Whether the components are applied in series, rather than in parallel. */
    boolean isSeries() {
        return series;
    }

    /** The Invert value the CmpMap applies its first component with. */
    boolean getInvert1() {
        return invert1;
    }

    /** The Invert value the CmpMap applies its second component with. */
    boolean getInvert2() {
        return invert2;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        apply(in, npoint, forward, out, false);
    }

    /** NaN protocol: each component transforms through its own {@link #transformIntoNan}. */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        apply(in, npoint, forward, out, true);
    }

    /** One component's transformation, through the NaN protocol if {@code nan}. */
    private static void stage(Mapping map, double[][] in, int npoint, boolean forward, double[][] out, boolean nan) {
        if (nan) {
            map.transformIntoNan(in, npoint, forward, out);
        } else {
            map.transformInto(in, npoint, forward, out);
        }
    }

    /**
     * Applies the components as C's {@code Transform} (cmpmap.c) does. Each component is applied
     * in the direction that, with its Invert attribute as it now is, gives the orientation recorded
     * when the CmpMap was made. In series, the points pass through the two components a batch at
     * a time, so that nested series do not each hold every point at once; in parallel, each
     * component transforms its own coordinates.
     */
    private void apply(double[][] in, int npoint, boolean forward, double[][] out, boolean nan) {
        boolean fwd = effectiveForward(forward);
        boolean forward1 = fwd != (invert1 != map1.getInvert());
        boolean forward2 = fwd != (invert2 != map2.getInvert());
        if (series) {
            Mapping first = fwd ? map1 : map2;
            Mapping second = fwd ? map2 : map1;
            boolean dir1 = fwd ? forward1 : forward2;
            boolean dir2 = fwd ? forward2 : forward1;
            applySeries(first, dir1, second, dir2, in, npoint, out, nan);
        } else {
            int nin1 = forward1 ? map1.getNin() : map1.getNout();
            int nout1 = forward1 ? map1.getNout() : map1.getNin();
            stage(map1, Arrays.copyOf(in, nin1), npoint, forward1, Arrays.copyOf(out, nout1), nan);
            stage(
                    map2,
                    Arrays.copyOfRange(in, nin1, in.length),
                    npoint,
                    forward2,
                    Arrays.copyOfRange(out, nout1, out.length),
                    nan);
        }
    }

    /**
     * Applies {@code first} in direction {@code dir1} and then {@code second} in direction {@code
     * dir2}, at most {@link #SERIES_BATCH} points at a time. Zero points still run both stages, so
     * that their checks apply.
     */
    private static void applySeries(
            Mapping first,
            boolean dir1,
            Mapping second,
            boolean dir2,
            double[][] in,
            int npoint,
            double[][] out,
            boolean nan) {
        int nmid = dir1 ? first.getNout() : first.getNin();
        if (npoint <= SERIES_BATCH) {
            double[][] mid = new double[nmid][npoint];
            stage(first, in, npoint, dir1, mid, nan);
            stage(second, mid, npoint, dir2, out, nan);
            return;
        }
        int nout = dir2 ? second.getNout() : second.getNin();
        double[][] inBatch = new double[in.length][SERIES_BATCH];
        double[][] mid = new double[nmid][SERIES_BATCH];
        double[][] outBatch = new double[nout][SERIES_BATCH];
        for (int start = 0; start < npoint; start += SERIES_BATCH) {
            int np = Math.min(SERIES_BATCH, npoint - start);
            for (int c = 0; c < in.length; c++) {
                System.arraycopy(in[c], start, inBatch[c], 0, np);
            }
            stage(first, inBatch, np, dir1, mid, nan);
            stage(second, mid, np, dir2, outBatch, nan);
            for (int c = 0; c < nout; c++) {
                System.arraycopy(outBatch[c], 0, out[c], start, np);
            }
        }
    }
}
