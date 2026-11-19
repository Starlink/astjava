package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_mapregion_oracle.rs}: the simplification of the
 * PermMap the probe maps an Interval with, whose inperm C keeps as given (permmap.c {@code
 * MapMerge}, {@code NullPerm}) ({@code oracle/region_mapregion.json}, from {@code
 * tools/oracle_fixtures/region_mapregion_probe.c}).
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code bad_limit_keeps_every_axis} (Interval),
 * {@code identity_chain_dumps_only_the_frame} (Circle), and {@code public_map_region_simplifies}
 * (needs simplification: {@link Region#mapRegion} of a Box through a PermMap gives C's re-fitted
 * Box only once Box has its own Simplify).
 */
class RegionMapRegionOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_mapregion");

    private static String expected(String name) {
        return DATA.get(name).textValue();
    }

    @Test
    void permmapWithBadInputSimplifiesAsCDoes() {
        Mapping pm = new PermMap(3, new int[] {1, 2, 0}, 2, new int[] {1, 2}, null);
        assertEquals(expected("PERMMAP_SIMPLIFIED"), ChannelText.dump(pm.simplify()));
    }
}
