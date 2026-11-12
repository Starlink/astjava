package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_norm_points.rs}: normalising a set of points, as C's
 * {@code astNormPoints} does through each class's {@code astNormalPoints}.
 *
 * <p>The plain Frame part of {@code norm_points_rejects_a_wrong_coordinate_count} runs here.
 * {@code norm_points_follows_the_c_library} and the SkyFrame part of the rejection test are
 * translated in Task 18, and the CmpFrame and FrameSet parts in Task 20; {@code
 * a_circle_mesh_is_normalised_as_c_does} and the Circle part need a Circle, outside the first
 * phase.
 */
class NormPointsTest {

    /** A coordinate count other than the Frame's axis count is {@code AST__DIMIN} (frame.c:7994). */
    @Test
    void normPointsRejectsAWrongCoordinateCount() {
        Frame basic = new Frame(2);
        for (int ncoord : new int[] {1, 4}) {
            double[][] in = new double[ncoord][1];
            for (double[] row : in) {
                Arrays.fill(row, 0.5);
            }
            for (boolean contig : new boolean[] {false, true}) {
                AstException e = assertThrows(AstException.class, () -> basic.normPoints(in, 1, contig));
                assertEquals(AstException.AST__DIMIN, e.getStatus(), "Frame, " + ncoord + " coordinates");
                String want = "astNormPoints(Frame): The number of input axes (" + ncoord
                        + ") is not the same as the number of axes in the Frame (2).";
                assertTrue(e.getMessage().contains(want), e.getMessage());
            }
        }
    }
}
