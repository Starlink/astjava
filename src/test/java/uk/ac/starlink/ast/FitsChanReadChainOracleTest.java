package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_fitschan_read_chain_oracle.rs}: the FrameSet a FITS-WCS read
 * gives, against C, for headers whose Mappings depend on how C's {@code WcsMapFrm} builds the pixel
 * to WCS chain: {@code ((((WcsIntWorld ; WcsCelestial) ; WcsSpectral) ; WcsOthers) ; tabmap)}.
 * {@code WcsIntWorld} makes the CDELT scaling a diagonal MatrixMap, which is a unit one when every
 * CDELT is equal to 1 within {@code astEQUAL}.
 *
 * <p>The cases of {@code oracle/fitschan_read_chain.json} (from {@code
 * tools/oracle_fixtures/fitschan_read_chain_probe.c}) hold C's native dump of the FrameSet, whose
 * Mapping C simplified ({@code WcsMapFrm} returns {@code astSimplify} of it). Each case is compared
 * with C's whole dump.
 *
 * <p>The cases in {@link #EXCLUDED} have a spectral axis (SpecFrame) or a {@code -LOG} axis (which
 * C maps with a MathMap); each must still stop there.
 */
class FitsChanReadChainOracleTest {

    /** The cases that cannot be read, with the reason the read gives. */
    private static final Map<String, String> EXCLUDED = exclusions();

    private static Map<String, String> exclusions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("spectral_and_linear", "needs SpecFrame");
        m.put("other_log", "needs MathMap");
        m.put("other_log_and_linear", "needs MathMap");
        return m;
    }

    /** The first mismatch between the port's read of {@code header} and C's dump {@code want}, or null. */
    private static String compare(String[] header, String want) {
        AstObject obj;
        try {
            obj = FitsHeaders.chan(header).read();
        } catch (AstException e) {
            String got = "! " + e.getStatus() + "\n";
            return got.equals(want) ? null : "read failed: " + e.getMessage();
        }
        if (obj == null) {
            return "= none\n".equals(want) ? null : "the read gave no Object";
        }
        String got = ChannelText.dump(obj);
        return got.equals(want) ? null : "the dump differs from C's:\n--- Java\n" + got + "--- C\n" + want;
    }

    @Test
    void readsMatchC() {
        List<String> failed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("fitschan_read_chain").get("CASES")) {
            String name = c.get(0).asText();
            seen.add(name);
            String[] header = FitsHeaders.cards(c.get(1));
            String reason = EXCLUDED.get(name);
            try {
                if (reason != null) {
                    FitsHeaders.chan(header).read();
                    failed.add(name + ": excluded (" + reason + ") but the read did not stop there");
                    continue;
                }
                run++;
                String problem = compare(header, c.get(2).asText());
                if (problem != null) {
                    failed.add(name + ": " + problem);
                }
            } catch (UnsupportedOperationException e) {
                String got = FitsHeaders.outOfScope(e);
                if (reason == null) {
                    failed.add(name + ": the read stops with " + got);
                } else if (!reason.equals(got)) {
                    failed.add(name + ": excluded (" + reason + ") but the read stops with " + got);
                }
            }
        }
        assertTrue(seen.containsAll(EXCLUDED.keySet()), "every excluded case is in the C table");
        assertEquals(seen.size() - EXCLUDED.size(), run, "every case not excluded is run");
        assertTrue(failed.isEmpty(), String.join("\n\n", failed));
    }
}
