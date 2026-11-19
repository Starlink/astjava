package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * The C dumps of {@code oracle/region_interval_remap.json} (from {@code
 * tools/oracle_fixtures/region_interval_remap_probe.c}): Intervals with a missing limit, and the
 * Mappings the probe maps them through, read back and written as C wrote them.
 *
 * <p>Rust's {@code tests/test_region_interval_remap_oracle.rs}, which compares the public {@link
 * Region#mapRegion} of each with C's, is listed in {@code deferred/rust-tests.txt} as needing
 * simplification: C's result is the Interval redefined in the new Frame by interval.c {@code
 * Simplify}, which depends on the Box's own simplification.
 */
class RegionIntervalRemapOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_interval_remap");

    @Test
    void dumpsReadBackAsCWroteThem() {
        int n = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            String region = c.get(1).textValue();
            String mapping = c.get(2).textValue();
            Interval iv = assertInstanceOf(Interval.class, ChannelText.read(region), name);
            assertEquals(region, ChannelText.dump(iv), name + ": Interval");
            Mapping map = assertInstanceOf(Mapping.class, ChannelText.read(mapping), name);
            assertEquals(mapping, ChannelText.dump(map), name + ": Mapping");
            n++;
        }
        assertEquals(3, n);
    }
}
