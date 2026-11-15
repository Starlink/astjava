package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Rx;
import uk.ac.starlink.ast.internal.erfa.Ry;
import uk.ac.starlink.ast.internal.erfa.Rz;

/**
 * Rotation matrix from Euler angles: {@code palDeuler}.
 *
 * <p>Ported from Rust {@code pal/deuler.rs}, itself ported from PAL's {@code palDeuler} ({@code
 * pal/palDeuler.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Deuler {

    private Deuler() {}

    /**
     * The rotation matrix for up to three successive rotations about the axes named by {@code
     * order}.
     *
     * <p>Each of the first three characters of {@code order} is {@code x}, {@code y} or {@code z}
     * in either case, or {@code 1}, {@code 2} or {@code 3}, and pairs with the next of {@code phi},
     * {@code theta} and {@code psi}. The first character that is none of these (a NUL included) ends
     * the sequence, leaving the rotations so far applied.
     */
    public static double[][] deuler(String order, double phi, double theta, double psi) {
        double[][] rmat = {{1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0}};
        double[] rotations = {phi, theta, psi};
        int n = Math.min(order.length(), rotations.length);
        for (int i = 0; i < n; i++) {
            char c = order.charAt(i);
            double angle = rotations[i];
            if (c == 'X' || c == 'x' || c == '1') {
                rmat = Rx.rx(angle, rmat);
            } else if (c == 'Y' || c == 'y' || c == '2') {
                rmat = Ry.ry(angle, rmat);
            } else if (c == 'Z' || c == 'z' || c == '3') {
                rmat = Rz.rz(angle, rmat);
            } else {
                break;
            }
        }
        return rmat;
    }
}
