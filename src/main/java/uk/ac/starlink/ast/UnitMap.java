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
