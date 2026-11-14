package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Simplifications on which C's {@code MapMerge} methods read or write past the end of an array, so
 * that C has no defined result: each Mapping is built with the public API, or is a dump of one, and
 * a C defect elsewhere in the simplification (a unit or non-square diagonal MatrixMap beside a
 * WinMap, see {@code docs/rust-c-differences.md}) leaves a list whose Mappings no longer fit
 * together as C's code assumes. This library declines the step whose assumption fails, so
 * simplifying raises no exception other than an {@link AstException}, and where no C defect with a
 * defined result is reached, the simplified Mapping transforms as the original does.
 *
 * <p>The dumps in {@code simplify-robustness/} were found by a differential fuzzer that compared
 * random compositions of this library's Mappings with the C library.
 */
class SimplifyRobustnessTest {

    private static String resource(String name) {
        try (InputStream in = SimplifyRobustnessTest.class.getResourceAsStream("/simplify-robustness/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no resource " + name);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Points spread over each input axis. */
    private static double[][] points(int nin) {
        double[][] in = new double[nin][4];
        for (int c = 0; c < nin; c++) {
            for (int p = 0; p < 4; p++) {
                in[c][p] = 0.37 * (p + 1) + 0.11 * c;
            }
        }
        return in;
    }

    private static void assertSameTransform(Mapping original, Mapping simplified) {
        assertEquals(original.getNin(), simplified.getNin(), "inputs");
        assertEquals(original.getNout(), simplified.getNout(), "outputs");
        double[][] in = points(original.getNin());
        double[][] want = original.tranP(4, original.getNin(), in, true, original.getNout());
        double[][] got = simplified.tranP(4, original.getNin(), in, true, original.getNout());
        for (int c = 0; c < want.length; c++) {
            for (int p = 0; p < 4; p++) {
                assertEquals(
                        want[c][p], got[c][p], 1e-9 * Math.max(1.0, Math.abs(want[c][p])), "axis " + c + " point " + p);
            }
        }
    }

    /** Simplifies, allowing only an {@link AstException}; gives the result, or null if it threw one. */
    private static Mapping simplifyOrAstError(Mapping m) {
        try {
            return m.simplify();
        } catch (AstException e) {
            return null;
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "distribute-overflow.ast",
                "permmap-run-overflow.ast",
                "windiagmat-series-overflow.ast",
                "windiagmat-overflow.ast",
                "winshift-overflow.ast",
                "winwin-overflow.ast",
                "parallel-in-series-overflow.ast",
                "parallel-in-series-overflow-2.ast",
                "winperm-overflow.ast"
            })
    void simplifyingRaisesNoUncheckedException(String name) {
        Mapping m = assertInstanceOf(Mapping.class, ChannelText.read(resource(name)));
        simplifyOrAstError(m);
    }

    /**
     * A WinMap followed by a diagonal MatrixMap of 3 inputs and 2 outputs: C's {@code WinDiagMat}
     * reads the diagonal as if it had 3 elements (heap-buffer-overflow at winmap.c:2721).
     */
    @Test
    void aWinMapBesideANonSquareDiagonalMatrixMapKeepsItsTransform() {
        WinMap win = new WinMap(
                3, new double[] {0, 0, 0}, new double[] {1, 2, 3}, new double[] {5, 6, 7}, new double[] {6, 9, 12});
        CmpMap cmp = new CmpMap(win, new MatrixMap(3, 2, new double[] {2, 3}), true);
        assertSameTransform(cmp, cmp.simplify());
    }

    /**
     * A WinMap distributed into a neighbouring parallel CmpMap whose components are wider than the
     * WinMap's terms: C reads past the terms (winmap.c:1352); copying them with padding would give
     * the second output 0 instead of its value.
     */
    @Test
    void distributingTooFewWinMapTermsKeepsTheTransform() {
        Mapping m = assertInstanceOf(Mapping.class, ChannelText.read(resource("distribute-short-terms.ast")));
        Mapping copy = (Mapping) m.copy();
        Mapping simplified = simplifyOrAstError(m);
        if (simplified != null) {
            assertSameTransform(copy, simplified);
        }
    }
}
