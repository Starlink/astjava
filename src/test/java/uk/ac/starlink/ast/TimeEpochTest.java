package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_time_epoch.rs}: the Epoch of a TimeFrame with only its
 * TimeOrigin set, checked against C ({@code oracle/time_epoch.json}, from {@code
 * tools/oracle_fixtures/time_epoch_probe.c}).
 *
 * <p>C's {@code GetEpoch} (timeframe.c) converts the TimeOrigin to a TDB MJD. For UT1, GMST, LAST
 * and LMST it maps the origin to UT1 first, but then converts the TimeOrigin as it is, not that UT1
 * value, with the Mapping from a UTC MJD in days to TDB, so the result is that of a UTC MJD
 * whatever the System. The Frames are built with the settings the probe makes.
 */
class TimeEpochTest {

    @Test
    void epochFromTimeOriginMatchesTheCLibrary() {
        JsonNode cases = ProbeData.load("time_epoch").get("CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String system = c.get(0).asText();
            String scale = c.get(1).asText();
            double origin = ProbeData.toDouble(c.get(2));
            String extra = c.get(3).asText();
            TimeFrame f = new TimeFrame();
            f.set("System=" + system);
            f.set("TimeScale=" + scale);
            f.set("TimeOrigin=" + origin);
            if (!extra.isEmpty()) {
                f.set(extra);
            }
            assertFalse(f.testEpoch());
            double want = ProbeData.toDouble(c.get(4));
            double got = f.getEpochMjd();
            if (!CValues.cValueEq(got, want)) {
                failures.add(system + " " + scale + " [" + extra + "]: got " + got + ", C gives " + want);
            }
        }
        assertEquals(102, cases.size());
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ from C:\n" + String.join("\n", failures));
    }
}
