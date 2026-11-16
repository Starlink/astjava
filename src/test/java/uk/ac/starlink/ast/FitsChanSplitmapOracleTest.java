package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_splitmap_oracle.rs}: FITS-WCS headers written
 * for FrameSets whose pixel to sky Mapping holds no WcsMap, against C's. fitschan.c {@code
 * SplitMap} gives such a Mapping one: a linear Mapping a CAR projection, with its longitude origin
 * moved to the pixel origin, and one giving constant celestial values the WcsMap of a TAN header
 * with those values at its reference point.
 *
 * <p>The cases are those of {@code oracle/fitschan_splitmap.json}, made by {@code
 * tools/oracle_fixtures/fitschan_splitmap_probe.c}; each FrameSet is built as the probe builds it.
 * The cards are compared as {@link FitsHeaders#cardsMatchC} compares them, the keywords of {@link
 * #RUNTIME_MATH} within libm rounding.
 */
class FitsChanSplitmapOracleTest {

    /**
     * The keywords whose numbers depend on the runtime's elementary functions, by case. The CAR
     * projection's native rotation (fitschan.c {@code WcsNative}) is a rotation by -LONPOLE, 2 pi
     * - 0.2, about the z axis; java.lang.Math gives its cosine as 0.9800665778412416 where C's libm
     * (and StrictMath) give 0.9800665778412415, and the least-squares slope of {@code FitLine}
     * over 101 points raises the 1 ULP to 5 ULP in CDELT2 (C probe {@code p4.c} with lldb, beside
     * the task report).
     */
    private static final Map<String, Set<String>> RUNTIME_MATH = runtimeMath();

    private static Map<String, Set<String>> runtimeMath() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("linear", Collections.singleton("CDELT2"));
        m.put("linear_3d", Collections.singleton("CDELT2"));
        return Collections.unmodifiableMap(m);
    }

    /** The probe's linear Mapping: a MatrixMap, then a ShiftMap by (0.2, 0.3, 4.0). */
    private static Mapping linear(MatrixMap matrix, int n) {
        double[] shift = {0.2, 0.3, 4.0};
        double[] s = new double[n];
        System.arraycopy(shift, 0, s, 0, n);
        return new CmpMap(matrix, new ShiftMap(s), true);
    }

    /** The probe's current Frame and pixel to WCS Mapping for {@code name}. */
    private static Object[] probeCase(String name) {
        // MODERN-JAVA(21): switch expression
        switch (name) {
            case "linear":
                return new Object[] {new SkyFrame(), linear(new MatrixMap(2, 2, new double[] {-0.001, 0.002}), 2)};
            case "linear_3d": {
                Frame x = new Frame(1);
                x.setDomain("X");
                double[][] zoom = {{-0.001, 0.0, 0.0}, {0.0, 0.002, 0.0}, {0.0, 0.0, 3.0}};
                return new Object[] {new CmpFrame(new SkyFrame(), x), linear(new MatrixMap(3, 3, zoom), 3)};
            }
            case "constant":
                return new Object[] {
                    new SkyFrame(), new PermMap(2, new int[] {0, 0}, 2, new int[] {-1, -2}, new double[] {0.5, -0.25})
                };
            default:
                throw new IllegalArgumentException("no case " + name);
        }
    }

    @Test
    void headersMatchC() {
        List<String> failed = new ArrayList<>();
        for (JsonNode c : ProbeData.load("fitschan_splitmap").get("CASES")) {
            String name = c.get(0).asText();
            Object[] pc = probeCase(name);
            Frame frame = (Frame) pc[0];
            Mapping map = (Mapping) pc[1];
            Frame grid = new Frame(map.getNin());
            grid.setDomain("GRID");
            FrameSet fs = new FrameSet(grid);
            fs.addFrame(FrameSet.AST__BASE, map, frame);
            FitsChan fc = new FitsChan();
            fc.setEncoding("FITS-WCS");
            List<String> got = new ArrayList<>();
            got.add("wrote " + fc.write(fs));
            got.addAll(FitsHeaders.foundCards(fc));
            List<String> want = new ArrayList<>();
            want.add("wrote " + c.get(1).asInt());
            want.addAll(FitsHeaders.strings(c.get(2)));
            Set<String> libm = RUNTIME_MATH.getOrDefault(name, Collections.<String>emptySet());
            if (!FitsHeaders.cardsMatchC(got, want, libm)) {
                failed.add(name + ":\n--- Java\n" + String.join("\n", got) + "\n--- C\n" + String.join("\n", want));
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }
}
