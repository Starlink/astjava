package uk.ac.starlink.ast;

/**
 * A Mapping that has no effect on the coordinates given to it: they are copied. This is useful
 * where a Mapping is needed, for example to pass to another method, but it should have no effect.
 * The numbers of input and output coordinates are equal and fixed when the UnitMap is created.
 * Inverting a UnitMap has no effect on how it behaves.
 *
 * <p>Ported from Rust {@code mappings/unit.rs} and C {@code unitmap.c}.
 */
public class UnitMap extends Mapping {

    /**
     * Creates a UnitMap.
     *
     * @param ncoord the number of input and output coordinates
     * @throws AstException with status {@code AST__BADNI} if {@code ncoord} is negative
     */
    public UnitMap(int ncoord) {
        super(ncoord, ncoord, true, true);
    }

    /** Loads a UnitMap from its description, as C's {@code astLoadUnitMap} does. */
    UnitMap(NativeNode node) {
        super(node);
    }

    /**
     * unitmap.c {@code Equal}: {@code that} is a UnitMap with the same number of inputs; the
     * Invert attributes play no part.
     */
    @Override
    boolean equalTo(AstObject that) {
        return that instanceof UnitMap && ((UnitMap) that).getNin() == getNin();
    }

    /**
     * unitmap.c {@code MapMerge}: alone, the UnitMap is applied forward; in series it is removed
     * from the list; in parallel it is joined with the UnitMaps on either side of it into one, or,
     * with none beside it, applied forward. The UnitMap's own Invert attribute is not changed.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        if (list.size() == 1) {
            if (list.invert(0)) {
                list.setInvert(0, false);
                return 0;
            }
            return -1;
        }
        if (series) {
            list.removeAt(where);
            return where;
        }
        int i1 = where;
        int i2 = where;
        int nin = list.map(where).getNin();
        // C compares class names, so a subclass of UnitMap does not join the run.
        while (i1 - 1 >= 0 && list.map(i1 - 1).getClass() == UnitMap.class) {
            i1--;
            nin += list.map(i1).getNin();
        }
        while (i2 + 1 < list.size() && list.map(i2 + 1).getClass() == UnitMap.class) {
            i2++;
            nin += list.map(i2).getNin();
        }
        if (i1 == i2) {
            if (list.invert(where)) {
                list.setInvert(where, false);
                return where;
            }
            return -1;
        }
        list.replaceRangeWith(i1, i2, new UnitMap(nin));
        return i1;
    }

    @Override
    String dumpComment() {
        return "Unit (null) Mapping";
    }

    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
    }

    @Override
    boolean isLinear() {
        return true;
    }

    /** The rate of change: one where the output is the input, zero elsewhere ({@code Rate}, unitmap.c). */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        return ax1 == ax2 ? 1.0 : 0.0;
    }

    /**
     * Splits off a UnitMap of the chosen inputs, as C's {@code MapSplit} (unitmap.c) does: any
     * selection of inputs, a repeated one included, gives a UnitMap with as many axes; an input
     * that does not exist gives no split.
     */
    @Override
    MapSplit splitOff(int[] in) {
        if (in.length == 0 || !MapSplit.allInputs(in, getNin())) {
            return null;
        }
        return new MapSplit(in.clone(), new UnitMap(in.length));
    }

    /** Copies the input, with a NaN, which is a bad value, made {@link #AST__BAD}. */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            for (int p = 0; p < npoint; p++) {
                double v = a[p];
                o[p] = Double.isNaN(v) ? AST__BAD : v;
            }
        }
    }

    /** The NaN protocol carries bad values as NaN, so this is a plain copy. */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        for (int c = 0; c < in.length; c++) {
            System.arraycopy(in[c], 0, out[c], 0, npoint);
        }
    }
}
