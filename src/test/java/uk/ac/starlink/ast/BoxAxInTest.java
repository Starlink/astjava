package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_box_axin.rs}: a Box tests each axis value through its
 * Frame's axes, as C does with {@code astAxIn} (box.c {@code Transform}), so on a sky axis a value
 * is moved by whole turns into the Box's range, and a Box that crosses RA 0 contains points on
 * both sides of it ({@code oracle/box_axin.json}, from {@code tools/oracle_fixtures/box_axin_probe.c}).
 */
class BoxAxInTest {

    private static final JsonNode DATA = ProbeData.load("box_axin");

    private static final double D2R = Math.PI / 180.0;

    private static Frame cmp() {
        return new CmpFrame(new SkyFrame(), new Frame(1));
    }

    /** The Box from (350,-10) to (10,10) degrees, by its two corners. */
    private static Box corners(Frame frame, int n) {
        double[] lo = {350.0 * D2R, -10.0 * D2R, 0.0};
        double[] hi = {10.0 * D2R, 10.0 * D2R, 1.0};
        return new Box(frame, 1, Arrays.copyOf(lo, n), Arrays.copyOf(hi, n), null);
    }

    /** The Box centred on (0,0) with a corner at (10,10) degrees. */
    private static Box centred(Frame frame, int n) {
        double[] c = {0.0, 0.0, 0.5};
        double[] k = {10.0 * D2R, 10.0 * D2R, 1.0};
        return new Box(frame, 0, Arrays.copyOf(c, n), Arrays.copyOf(k, n), null);
    }

    private static boolean[] inside(Box region, int n) {
        JsonNode lonNode = DATA.get("LON");
        JsonNode latNode = DATA.get("LAT");
        int np = lonNode.size();
        double[][] in = new double[n][np];
        for (int i = 0; i < np; i++) {
            in[0][i] = ProbeData.toDouble(lonNode.get(i)) * D2R;
            in[1][i] = ProbeData.toDouble(latNode.get(i)) * D2R;
            if (n > 2) {
                in[2][i] = 0.5;
            }
        }
        double[][] out = region.tranP(np, n, in, true, n);
        boolean[] got = new boolean[np];
        for (int i = 0; i < np; i++) {
            got[i] = out[0][i] != AstObject.AST__BAD;
        }
        return got;
    }

    @Test
    void boxContainmentFollowsTheCLibrary() {
        List<String> names = new ArrayList<>();
        List<boolean[]> got = new ArrayList<>();
        names.add("sky");
        got.add(inside(corners(new SkyFrame(), 2), 2));
        Box b = centred(new SkyFrame(), 2);
        names.add("sky_centred");
        got.add(inside(b, 2));
        b.negate();
        names.add("sky_centred_negated");
        got.add(inside(b, 2));
        names.add("plain_centred");
        got.add(inside(centred(new Frame(2), 2), 2));
        names.add("cmpframe_centred");
        got.add(inside(centred(cmp(), 3), 3));
        b = corners(new SkyFrame(), 2);
        b.negate();
        names.add("sky_negated");
        got.add(inside(b, 2));
        b.negate();
        b.setClosed(false);
        names.add("sky_open");
        got.add(inside(b, 2));
        names.add("plain");
        got.add(inside(corners(new Frame(2), 2), 2));
        names.add("cmpframe");
        got.add(inside(corners(cmp(), 3), 3));

        JsonNode cases = DATA.get("CASES");
        assertEquals(cases.size(), got.size(), "the test runs every case the C probe recorded");
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            assertEquals(c.get(0).textValue(), names.get(i), "the test runs the probe's cases");
            JsonNode want = c.get(1);
            boolean[] w = new boolean[want.size()];
            for (int j = 0; j < w.length; j++) {
                w[j] = want.get(j).booleanValue();
            }
            assertEquals(Arrays.toString(w), Arrays.toString(got.get(i)), names.get(i));
        }
    }
}
