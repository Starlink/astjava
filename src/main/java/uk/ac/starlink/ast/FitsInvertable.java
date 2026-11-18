package uk.ac.starlink.ast;

/**
 * C's {@code MakeInvertable} (fitschan.c): makes a square CD matrix invertible where degenerate
 * grid axes allow it.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/invertable.rs}, following fitschan.c.
 */
final class FitsInvertable {

    private FitsInvertable() {}

    /**
     * Sets to 1 the diagonal element of each input of {@code fullmat} that affects no output and
     * whose grid axis spans a single pixel: such an input is always zero, so the outputs do not
     * change.
     *
     * <p>C reads a dimension for every input of the matrix, though its caller holds one per pixel
     * axis, fewer than the inputs when degenerate axes have been added, and so reads past the end
     * of the array; an input with no dimension is taken to be one whose dimension is not 1.
     *
     * @param fullmat the {@code n} by {@code n} matrix, indexed {@code [input][output]}
     * @param dim the dimension of each pixel axis, {@code AST__BAD} where unknown
     */
    static void makeInvertable(double[][] fullmat, int n, double[] dim) {
        for (int i = 0; i < n; i++) {
            boolean unused = true;
            for (int j = 0; j < n; j++) {
                if (fullmat[i][j] != 0.0) {
                    unused = false;
                    break;
                }
            }
            if (unused && i < dim.length && dim[i] == 1.0) {
                fullmat[i][i] = 1.0;
            }
        }
    }
}
