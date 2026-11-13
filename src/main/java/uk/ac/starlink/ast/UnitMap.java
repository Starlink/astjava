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
