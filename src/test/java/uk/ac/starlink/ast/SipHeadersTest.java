package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Deferred;
import uk.ac.starlink.ast.testsupport.OracleSection;

/**
 * The SIP headers of the wcsconv fixtures ({@code sip.head}, {@code sip2.head} and {@code
 * sip-alt.head}) read through a FitsChan, against C's {@code fixtures/oracle/headers.oracle}.
 *
 * <p>None of them may be listed in {@code deferred/headers.txt}, so that the headers gate of {@link
 * TransformOracleTest} checks every one of their sections. Here each is read as C's oracle
 * generator reads it ({@code transform_oracle_util.c}: each line given to {@code astPutFits}, Card
 * cleared, then {@code astRead}, with SipReplace at its default, so the inverse is C's {@code
 * astPolyTran} fit), and its base to current Mapping is checked against the forward and inverse
 * sections within the tolerances the oracle header states ({@code |got - C| <= atol + rtol * |C|},
 * angles compared modulo 2 pi), and the round trip of inverse after forward at the forward rows
 * within the oracle's round-trip tolerances.
 */
class SipHeadersTest {

    private static final List<String> SIP_HEADERS =
            Arrays.asList("wcsconv/inputs/sip.head", "wcsconv/inputs/sip2.head", "wcsconv/inputs/sip-alt.head");

    private static final double TWO_PI = 2.0 * Math.PI;

    /** C's {@code oracle_within_tol}, with an angle also matching one 2 pi away. */
    private static boolean within(double got, double want, double rtol, double atol, boolean angular) {
        double tol = atol + rtol * Math.abs(want);
        return Math.abs(got - want) <= tol || (angular && Math.abs(Math.abs(got - want) - TWO_PI) <= tol);
    }

    /** Which axes of the Frame {@code iframe} of {@code fs} are angular (sky axes). */
    private static boolean[] angular(FrameSet fs, int iframe) {
        Frame f = fs.getFrame(iframe);
        boolean[] out = new boolean[f.getNaxes()];
        Arrays.fill(out, f instanceof SkyFrame);
        return out;
    }

    @Test
    void sipHeadersAreNotDeferred() {
        Map<String, String> deferred = Deferred.load("headers");
        for (String rel : SIP_HEADERS) {
            assertFalse(deferred.containsKey(rel), rel + " is listed in deferred/headers.txt as " + deferred.get(rel));
        }
    }

    @Test
    void sipHeadersMatchTheOracle() {
        List<String> failed = new ArrayList<>();
        for (String rel : SIP_HEADERS) {
            FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.fixture(rel));
            Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            OracleSection fwd = FitsHeaders.headersOracle(rel, true);
            OracleSection inv = FitsHeaders.headersOracle(rel, false);
            boolean[] skyOut = angular(fs, FrameSet.AST__CURRENT);
            boolean[] pixOut = angular(fs, FrameSet.AST__BASE);
            for (int row = 1; row <= fwd.npoint(); row++) {
                double[] in = FitsHeaders.oracleIn(fwd, row);
                double[] want = FitsHeaders.oracleOut(fwd, row);
                double[] got = FitsHeaders.tran(map, in, true);
                for (int k = 0; k < want.length; k++) {
                    if (!within(got[k], want[k], fwd.rtol, fwd.atol, skyOut[k])) {
                        failed.add(rel + " forward row " + row + ": " + Arrays.toString(got) + ", C gives "
                                + Arrays.toString(want));
                        break;
                    }
                }
                double[] back = FitsHeaders.tran(map, got, false);
                for (int k = 0; k < in.length; k++) {
                    if (!within(back[k], in[k], fwd.rtripRtol, fwd.rtripAtol, false)) {
                        failed.add(rel + " round trip of forward row " + row + ": " + Arrays.toString(back));
                        break;
                    }
                }
            }
            for (int row = 1; row <= inv.npoint(); row++) {
                double[] want = FitsHeaders.oracleOut(inv, row);
                double[] got = FitsHeaders.tran(map, FitsHeaders.oracleIn(inv, row), false);
                for (int k = 0; k < want.length; k++) {
                    if (!within(got[k], want[k], inv.rtol, inv.atol, pixOut[k])) {
                        failed.add(rel + " inverse row " + row + ": " + Arrays.toString(got) + ", C gives "
                                + Arrays.toString(want));
                        break;
                    }
                }
            }
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }
}
