package uk.ac.starlink.ast.internal.pal;

/**
 * Normalises an angle into the range -pi to +pi: {@code palDrange}.
 *
 * <p>Ported from Rust {@code pal/drange.rs}, itself ported from PAL's {@code palDrange} ({@code
 * pal/palDrange.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Drange {

    private Drange() {}

    /** Normalises an angle into the range -pi to +pi. */
    public static double drange(double angle) {
        // C's fmod is Java's %.
        double result = angle % Pal.PAL__D2PI;
        if (result > Pal.PAL__DPI) {
            result -= Pal.PAL__D2PI;
        } else if (result < -Pal.PAL__DPI) {
            result += Pal.PAL__D2PI;
        }
        return result;
    }
}
