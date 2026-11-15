package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_sky_interleave.rs}: a SkyFrame template found among the
 * interleaved axes of a CmpFrame of two SkyFrames, checked against C ({@code
 * oracle/sky_interleave.json}, from {@code tools/oracle_fixtures/sky_interleave_probe.c}). SkyFrame
 * {@code Match} takes the first target axis that belongs to a SkyFrame, then searches the remaining
 * axes for the other axis of that same SkyFrame, passing over the axes of any other (skyframe.c
 * {@code Match}).
 *
 * <p>The values found are compared with C's as {@link CValues#cValuesEq} does: exactly, or within
 * libm rounding where they come through a celestial conversion.
 */
class SkyInterleaveTest {

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    /** The first two values the template finds for the probe's point, or null if nothing is found. */
    private static double[] found(int[] perm, double[] point) {
        CmpFrame target = new CmpFrame(sky("FK5"), sky("GALACTIC"));
        target.permAxes(perm);
        SkyFrame template = sky("FK5");
        template.setMaxAxes(4);
        FrameSet fs = target.findFrame(template, "");
        if (fs == null) {
            return null;
        }
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        double[] p = MappingTestData.point(MappingTestData.transformRows(map, true, point), 0);
        return new double[] {p[0], p[1]};
    }

    private static double[] doubles(JsonNode n) {
        double[] out = new double[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(n.get(i));
        }
        return out;
    }

    @Test
    void interleavedSkyAxesMatchTheCLibrary() {
        JsonNode consts = ProbeData.load("sky_interleave");
        double[] point = doubles(consts.get("POINT"));
        List<String> failures = new ArrayList<>();
        for (JsonNode c : consts.get("CASES")) {
            JsonNode p = c.get(0);
            int[] perm = new int[p.size()];
            for (int i = 0; i < perm.length; i++) {
                perm[i] = p.get(i).asInt();
            }
            double[] got = found(perm, point);
            double[] want = c.get(1).isNull() ? null : doubles(c.get(1));
            boolean same =
                    (got == null && want == null) || (got != null && want != null && CValues.cValuesEq(got, want));
            if (!same) {
                failures.add(
                        Arrays.toString(perm) + ": got " + Arrays.toString(got) + ", C gives " + Arrays.toString(want));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
