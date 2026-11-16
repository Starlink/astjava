package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * Completion of a set of vectors with unit vectors orthogonal to it: C's {@code OrthVector} and
 * {@code OrthVectorSet} (fitschan.c), with which a FitsChan write invents the CD matrix columns of
 * degenerate grid axes.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/orth.rs}, following fitschan.c.
 */
final class FitsOrth {

    private FitsOrth() {}

    /**
     * C's {@code OrthVector}: a unit {@code n}-vector orthogonal to the {@code m} vectors {@code
     * in}, where {@code m < n}. The {@code m} components of largest column magnitude are found by
     * solving the equations that the dot products are zero with the component of the next largest
     * column set to 1 and the others to 0.
     *
     * @param in {@code m} vectors of {@code n} elements
     * @return the unit vector, or null if a supplied vector is null or the vectors span fewer than
     *     {@code m} axes
     */
    static double[] orthVector(int n, int m, double[][] in) {
        for (int i = 0; i < m; i++) {
            if (in[i] == null) {
                return null;
            }
        }
        double[] colmag = new double[n];
        int[] colperm = new int[n];
        for (int i = 0; i < n; i++) {
            colperm[i] = i;
            colmag[i] = 0.0;
            for (int j = 0; j < m; j++) {
                colmag[i] += in[j][i] * in[j][i];
            }
        }

        // A bubble sort of the column indices into order of decreasing magnitude.
        int ii = n;
        boolean done = false;
        while (!done) {
            done = true;
            for (int i = 1; i < ii; i++) {
                int ih = colperm[i];
                int il = colperm[i - 1];
                if (colmag[ih] > colmag[il]) {
                    colperm[i] = il;
                    colperm[i - 1] = ih;
                    done = false;
                }
            }
            ii--;
        }

        double[] mat = new double[m * m];
        double[] rhs = new double[m];
        int mel = 0;
        for (int i = 0; i < m; i++) {
            double[] d = in[i];
            for (int j = 0; j < m; j++) {
                mat[mel++] = d[colperm[j]];
            }
            rhs[i] = -d[colperm[m]];
        }
        if (!Pal.dmat(m, mat, rhs)) {
            return null;
        }

        double[] ret = new double[n];
        double sl = 0.0;
        for (int j = 0; j < m; j++) {
            sl += rhs[j] * rhs[j];
            ret[colperm[j]] = rhs[j];
        }
        sl += 1.0;
        ret[colperm[m]] = 1.0;
        for (int j = m + 1; j < n; j++) {
            ret[colperm[j]] = 0.0;
        }

        // A unit vector, with any negative zero made positive.
        sl = Math.sqrt(sl);
        for (int j = 0; j < n; j++) {
            ret[j] /= sl;
            if (ret[j] == 0.0) {
                ret[j] = 0.0;
            }
        }
        return ret;
    }

    /**
     * C's {@code OrthVectorSet}: {@code n} vectors of {@code n} elements, the first {@code m}
     * copies of {@code in} and each of the others the unit vector {@link #orthVector} finds
     * orthogonal to all those before it.
     *
     * @param in {@code m} vectors of {@code n} elements
     * @return the vectors, or null if one cannot be found
     */
    static double[][] orthVectorSet(int n, int m, double[][] in) {
        double[][] ret = new double[n][];
        boolean bad = false;
        int i;
        for (i = 0; i < m; i++) {
            ret[i] = in[i].clone();
        }
        for (; i < n; i++) {
            ret[i] = orthVector(n, i, ret);
            if (ret[i] == null) {
                bad = true;
            }
        }
        return bad ? null : ret;
    }
}
