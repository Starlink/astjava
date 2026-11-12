package uk.ac.starlink.ast;

import java.util.Arrays;
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
     * cannot transform in either direction.
     *
     * @param map1 the first component Mapping
     * @param map2 the second component Mapping
     * @param series true to connect the Mappings in series, false to connect them in parallel
     * @throws AstException with status {@code AST__INNCO} if, in series, the numbers of
     *     coordinates do not match
     */
    public CmpMap(Mapping map1, Mapping map2, boolean series) {
        super(
                count(map1, map2, series, true),
                count(map1, map2, series, false),
                map1.getTranForward() && map2.getTranForward(),
                map1.getTranInverse() && map2.getTranInverse());
        this.map1 = map1;
        this.map2 = map2;
        map1.markShared();
        map2.markShared();
        this.series = series;
        invert1 = map1.getInvert();
        invert2 = map2.getInvert();
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
