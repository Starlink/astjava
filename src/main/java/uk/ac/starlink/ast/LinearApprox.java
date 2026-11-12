package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * A linear fit to a Mapping's forward transformation over a box of input coordinates, as C's
 * {@code astLinearApprox} makes it.
 *
 * <p>The gradients and constant terms are found from the Mapping at the centre of each face of
 * the box, where the fit is therefore exact; the fit is then tested at the centre of the box,
 * half way to the centre of each face, at each corner and half way to each corner, the points
 * slightly offset to break any regularity.
 *
 * <p>Ported from Rust {@code mappings/linear_approx.rs} and C {@code mapping.c} ({@code
 * LinearApprox}).
 */
final class LinearApprox {

    private LinearApprox() {}

    /**
     * C's {@code LinearApprox}: the fit to {@code map} over the box from {@code lbnd} to {@code
     * ubnd}, accurate to within {@code tol}.
     *
     * @return the {@code Nout} constant terms followed by the {@code Nout} by {@code Nin} gradients,
     *     row by row, or null if the Mapping is not linear enough, or every output is bad
     * @throws AstException with status {@code AST__NCPIN} if a bound holds too few values, or any
     *     error transforming the points
     */
    static double[] linearApprox(Mapping map, double[] lbnd, double[] ubnd, double tol) {
        int ndimIn = map.getNin();
        int ndimOut = map.getNout();
        if (lbnd.length < ndimIn || ubnd.length < ndimIn) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astLinearApprox(" + map.astClass() + "): The bounds give " + lbnd.length + " and " + ubnd.length
                            + " values; the " + map.astClass() + " has " + ndimIn + " inputs.");
        }
        if (ndimIn == 0 || ndimOut == 0) {
            return null;
        }
        double[] zero = new double[ndimOut];
        double[] grad = new double[ndimIn * ndimOut];
        Arrays.fill(zero, AstObject.AST__BAD);
        Arrays.fill(grad, AstObject.AST__BAD);

        // The centre of each face of the box.
        int nfaces = 2 * ndimIn;
        double[][] inF = new double[ndimIn][nfaces];
        for (int face = 0; face < nfaces; face++) {
            for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                inF[coordIn][face] = 0.5 * (lbnd[coordIn] + ubnd[coordIn]);
            }
            inF[face / 2][face] = face % 2 != 0 ? ubnd[face / 2] : lbnd[face / 2];
        }
        double[][] outF = map.transform(inF, nfaces, true);

        // The gradients, and constant terms for an origin at the centre of the box. An output that
        // is bad at a face centre keeps bad coefficients.
        for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
            boolean badOutput = false;
            double z = 0.0;
            int ii = ndimIn * coordOut;
            for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                int face1 = 2 * coordIn;
                int face2 = face1 + 1;
                double in1 = inF[coordIn][face1];
                double in2 = inF[coordIn][face2];
                double out1 = outF[coordOut][face1];
                double out2 = outF[coordOut][face2];
                if (Mapping.isBad(out1) || Mapping.isBad(out2)) {
                    badOutput = true;
                    break;
                }
                double indiff = in2 - in1;
                grad[ii++] = indiff != 0.0 ? (out2 - out1) / indiff : 0.0;
                z += out1 + out2;
            }
            if (!badOutput) {
                zero[coordOut] = z / (2 * ndimIn);
            }
        }

        // Move the constant terms to the actual origin; with every output bad there is no fit.
        boolean linear = false;
        int ii = 0;
        for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
            if (zero[coordOut] != AstObject.AST__BAD) {
                linear = true;
                for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                    double x0 = 0.5 * (lbnd[coordIn] + ubnd[coordIn]);
                    zero[coordOut] -= grad[ii++] * x0;
                }
            } else {
                for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                    grad[ii++] = AstObject.AST__BAD;
                }
            }
        }
        if (!linear) {
            // C fills the fit with a unit transformation here, but then reports failure and sets
            // every coefficient bad.
            return null;
        }

        double[][] inT = testPoints(ndimIn, lbnd, ubnd);
        int npoint = inT[0].length;
        double[][] outT = map.transform(inT, npoint, true);
        for (int point = 0; point < npoint; point++) {
            double err = 0.0;
            int badOutput = 0;
            ii = 0;
            for (int coordOut = 0; coordOut < ndimOut; coordOut++) {
                double y = outT[coordOut][point];
                if (Mapping.isBad(y) || zero[coordOut] == AstObject.AST__BAD) {
                    // As in C, the rest of this point's outputs are not checked, and the gradients
                    // marked bad are those at the current position in the matrix.
                    zero[coordOut] = AstObject.AST__BAD;
                    for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                        grad[ii++] = AstObject.AST__BAD;
                    }
                    badOutput++;
                    break;
                }
                double yfit = zero[coordOut];
                for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                    yfit += grad[ii++] * inT[coordIn][point];
                }
                double diff = y - yfit;
                err += diff * diff;
            }
            // The tolerance is reduced in proportion to the number of bad outputs.
            if (Math.sqrt(err) > (tol * (ndimOut - badOutput)) / ndimOut) {
                return null;
            }
        }
        double[] fit = new double[(ndimIn + 1) * ndimOut];
        System.arraycopy(zero, 0, fit, 0, ndimOut);
        System.arraycopy(grad, 0, fit, ndimOut, grad.length);
        return fit;
    }

    /**
     * The points at which the fit is tested: for one input, points spread evenly over the range,
     * avoiding its ends; otherwise one near the centre of the box, one half way to the centre of
     * each face, and one at each corner and half way to it.
     */
    private static double[][] testPoints(int ndimIn, double[] lbnd, double[] ubnd) {
        int nvertex = 1;
        for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
            nvertex *= 2;
        }
        int npoint = 1 + 2 * (ndimIn + nvertex);
        double[][] in = new double[ndimIn][npoint];
        if (ndimIn == 1) {
            for (int point = 0; point < npoint; point++) {
                double frac = (double) (point + 1) / (npoint + 1);
                in[0][point] = (1.0 - frac) * lbnd[0] + frac * ubnd[0];
            }
            return in;
        }
        int point = 0;
        for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
            in[coordIn][point] = 0.49 * lbnd[coordIn] + 0.51 * ubnd[coordIn];
        }
        point++;
        for (int face = 0; face < 2 * ndimIn; face++) {
            for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                in[coordIn][point] = 0.48 * lbnd[coordIn] + 0.52 * ubnd[coordIn];
            }
            in[face / 2][point] = 0.51 * (face % 2 != 0 ? ubnd[face / 2] : lbnd[face / 2]) + 0.49 * in[face / 2][0];
            point++;
        }
        boolean[] vertex = new boolean[ndimIn];
        boolean done = false;
        while (!done) {
            for (int coordIn = 0; coordIn < ndimIn; coordIn++) {
                in[coordIn][point] = vertex[coordIn] ? ubnd[coordIn] : lbnd[coordIn];
                in[coordIn][point + 1] = 0.52 * in[coordIn][point] + 0.48 * in[coordIn][0];
            }
            point += 2;
            // The least significant axis not yet at its upper bound moves to it, and those below
            // it return to their lower bounds; every vertex has been visited when none is left.
            int coordIn = 0;
            while (true) {
                if (!vertex[coordIn]) {
                    vertex[coordIn] = true;
                    break;
                }
                vertex[coordIn] = false;
                coordIn++;
                if (coordIn == ndimIn) {
                    done = true;
                    break;
                }
            }
        }
        return in;
    }
}
