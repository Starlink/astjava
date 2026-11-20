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
 * <p>Translation of Rust {@code tests/test_region_interval_remap_oracle.rs}: the public {@link
 * Region#mapRegion} of each into a Frame of Domain NEW is C's, the Interval redefined in the new
 * Frame by interval.c {@code Simplify}.
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

    @Test
    void mappedIntervalIsRedefinedInTheNewFrame() {
        int n = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region region = (Region) ChannelText.read(c.get(1).textValue());
            Mapping mapping = (Mapping) ChannelText.read(c.get(2).textValue());
            Frame frame = new Frame(2);
            frame.setDomain("NEW");
            assertEquals(c.get(3).textValue(), ChannelText.dump(region.mapRegion(mapping, frame)), name);
            n++;
        }
        assertEquals(3, n);
    }
}
