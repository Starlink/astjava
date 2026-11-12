package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_sky_geometry.rs}: SkyFrame {@code resolve} and {@code
 * intersect}, which work on great circles, checked against C ({@code oracle/sky_geometry.json},
 * from {@code tools/oracle_fixtures/sky_geometry_probe.c}). The probe calls {@code astResolve} and
 * {@code astIntersect} on a default SkyFrame and on one with its axes swapped, including bad
 * inputs and the degenerate cases C reports as bad.
 *
 * <p>{@code cmp_frame_resolve_matches_the_c_library} and {@code
 * cmp_frame_resolve_points_matches_the_c_library} use CmpFrames holding a SkyFrame and a SpecFrame
 * or a Frame, and are left to Task 20 (CmpFrame) and SpecFrame.
 */
class SkyGeometryTest {

    private static SkyFrame sky(boolean permuted) {
        SkyFrame f = new SkyFrame();
        if (permuted) {
            f.permAxes(new int[] {2, 1});
        }
        return f;
    }

    private static double[] point(JsonNode n) {
        double[] p = new double[n.size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = ProbeData.toDouble(n.get(i));
        }
        return p;
    }

    /** {@code {p4[0], p4[1], d1, d2}} from {@code frame.resolve}. */
    private static double[] resolve(Frame frame, JsonNode c) {
        double[] p4 = new double[2];
        double[] d = frame.resolve(point(c.get(1)), point(c.get(2)), point(c.get(3)), p4);
        return new double[] {p4[0], p4[1], d[0], d[1]};
    }

    private static double[] resolveWant(JsonNode c) {
        double[] p4 = point(c.get(4));
        return new double[] {p4[0], p4[1], ProbeData.toDouble(c.get(5)), ProbeData.toDouble(c.get(6))};
    }

    private static double[] intersect(Frame frame, JsonNode c) {
        return frame.intersect(point(c.get(1)), point(c.get(2)), point(c.get(3)), point(c.get(4)));
    }

    private static String describe(JsonNode c, int n) {
        StringBuilder s = new StringBuilder("permuted ").append(c.get(0).asBoolean());
        for (int i = 1; i <= n; i++) {
            s.append(' ').append(c.get(i));
        }
        return s.toString();
    }

    @Test
    void resolveMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("sky_geometry").get("RESOLVE_CASES")) {
            double[] got = resolve(sky(c.get(0).asBoolean()), c);
            double[] want = resolveWant(c);
            if (!CValues.cValuesEq(got, want)) {
                failures.add(describe(c, 3) + ": got " + Arrays.toString(got) + ", C gives " + Arrays.toString(want));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void intersectMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("sky_geometry").get("INTERSECT_CASES")) {
            double[] got = intersect(sky(c.get(0).asBoolean()), c);
            double[] want = point(c.get(5));
            if (!CValues.cValuesEq(got, want)) {
                failures.add(describe(c, 4) + ": got " + Arrays.toString(got) + ", C gives " + Arrays.toString(want));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static FrameSet inFrameSet(boolean permuted) {
        FrameSet fs = new FrameSet(new Frame(2));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), sky(permuted));
        return fs;
    }

    /**
     * A FrameSet resolves and intersects in its current Frame (frameset.c {@code Resolve}, {@code
     * Intersect}), so one whose current Frame is a SkyFrame gives the SkyFrame's great-circle
     * answers.
     */
    @Test
    void framesetResolvesAndIntersectsInItsCurrentFrame() {
        JsonNode data = ProbeData.load("sky_geometry");
        for (JsonNode c : data.get("RESOLVE_CASES")) {
            double[] got = resolve(inFrameSet(c.get(0).asBoolean()), c);
            CValues.assertCValues(got, resolveWant(c), describe(c, 3));
        }
        for (JsonNode c : data.get("INTERSECT_CASES")) {
            double[] got = intersect(inFrameSet(c.get(0).asBoolean()), c);
            CValues.assertCValues(got, point(c.get(5)), describe(c, 4));
        }

        // PermAxes on a FrameSet permutes the current Frame and remaps it (frameset.c PermAxes).
        FrameSet fs = inFrameSet(false);
        fs.permAxes(new int[] {2, 1});
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        double[][] out = map.tran2(1, new double[] {1.0}, new double[] {0.5}, true);
        assertArrayEquals(new double[] {0.5, 1.0}, new double[] {out[0][0], out[1][0]});
    }
}
