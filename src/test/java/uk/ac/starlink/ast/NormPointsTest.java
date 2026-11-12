package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_norm_points.rs}: normalising a set of points, as C's
 * {@code astNormPoints} does through each class's {@code astNormalPoints}. A SkyFrame asked for
 * contiguous values keeps its longitudes on one side of the widest gap between them, and a
 * FrameSet hands the points to its current Frame. The expected values come from C ({@code
 * oracle/norm_points.json}, from {@code tools/oracle_fixtures/norm_points_probe.c}).
 *
 * <p>The plain Frame, SkyFrame and FrameSet parts run here. The CmpFrame parts (the case {@code
 * cmpframe_contig} of {@code norm_points_follows_the_c_library} and the CmpFrame part of the
 * rejection test) are translated in Task 20; {@code a_circle_mesh_is_normalised_as_c_does} and
 * the Circle part of the rejection test need a Circle, outside the first phase.
 */
class NormPointsTest {

    private static final JsonNode CONSTS = ProbeData.load("norm_points");

    private static final double D2R = Math.PI / 180.0;

    /**
     * The probe's points as {@code [2][npoint]}, longitude on axis {@code lonAxis + 1} and latitude
     * on the other.
     */
    private static double[][] points(int lonAxis) {
        JsonNode lon = CONSTS.get("LON");
        JsonNode lat = CONSTS.get("LAT");
        int npoint = lon.size();
        double[][] out = new double[2][npoint];
        for (int i = 0; i < npoint; i++) {
            out[lonAxis][i] = ProbeData.toDouble(lon.get(i)) * D2R;
            out[1 - lonAxis][i] = ProbeData.toDouble(lat.get(i)) * D2R;
        }
        return out;
    }

    @Test
    void normPointsFollowsTheCLibrary() {
        SkyFrame sky = new SkyFrame();
        SkyFrame swapped = (SkyFrame) sky.copy();
        swapped.permAxes(new int[] {2, 1});
        FrameSet fs = new FrameSet((SkyFrame) sky.copy());
        int npoint = CONSTS.get("LON").size();

        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : CONSTS.get("CASES")) {
            String name = c.get(0).asText();
            double[][] got;
            switch (name) {
                case "sky":
                    got = sky.normPoints(points(0), npoint, false);
                    break;
                case "sky_contig":
                    got = sky.normPoints(points(0), npoint, true);
                    break;
                case "sky_swapped_contig":
                    got = swapped.normPoints(points(1), npoint, true);
                    break;
                case "frameset_contig":
                    got = fs.normPoints(points(0), npoint, true);
                    break;
                case "cmpframe_contig":
                    continue;
                default:
                    throw new IllegalStateException("unexpected case " + name);
            }
            ran++;
            JsonNode want = c.get(1);
            assertEquals(npoint, want.size(), name + ": number of points");
            for (int i = 0; i < npoint; i++) {
                double[] wantRow = {
                    ProbeData.toDouble(want.get(i).get(0)),
                    ProbeData.toDouble(want.get(i).get(1))
                };
                double[] gotRow = {got[0][i], got[1][i]};
                if (!CValues.cValuesEq(gotRow, wantRow)) {
                    failures.add(name + " point " + i + ": got " + Arrays.toString(gotRow) + ", C gives "
                            + Arrays.toString(wantRow));
                }
            }
        }
        assertEquals(4, ran, "the SkyFrame and FrameSet cases the C probe recorded");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * A coordinate count other than the Frame's axis count is {@code AST__DIMIN} (frame.c:7994),
     * whichever class normalises the points.
     */
    @Test
    void normPointsRejectsAWrongCoordinateCount() {
        SkyFrame sky = new SkyFrame();
        Frame[] frames = {new Frame(2), sky, new FrameSet((SkyFrame) sky.copy())};
        // The class names C's message gives.
        String[] classes = {"Frame", "SkyFrame", "FrameSet"};
        for (int i = 0; i < frames.length; i++) {
            Frame frame = frames[i];
            String cls = classes[i];
            for (int ncoord : new int[] {1, 4}) {
                double[][] in = new double[ncoord][1];
                for (double[] row : in) {
                    Arrays.fill(row, 0.5);
                }
                for (boolean contig : new boolean[] {false, true}) {
                    AstException e = assertThrows(AstException.class, () -> frame.normPoints(in, 1, contig));
                    assertEquals(AstException.AST__DIMIN, e.getStatus(), cls + ", " + ncoord + " coordinates");
                    String want = "astNormPoints(" + cls + "): The number of input axes (" + ncoord
                            + ") is not the same as the number of axes in the " + cls + " (" + frame.getNaxes()
                            + ").";
                    assertTrue(e.getMessage().contains(want), e.getMessage());
                }
            }
        }
    }
}
