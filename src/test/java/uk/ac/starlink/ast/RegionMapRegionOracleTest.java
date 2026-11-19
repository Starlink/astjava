package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_mapregion_oracle.rs}: the protected {@code
 * astMapRegion} (region.c {@code MapRegion}) given an Interval with a bad limit and a PermMap that
 * drops an axis, which C does not reduce to fewer axes because the bad limit leaves it no flags
 * saying which axes give good values; and the simplification of that PermMap, whose inperm C
 * keeps as given (permmap.c {@code MapMerge}, {@code NullPerm}) ({@code
 * oracle/region_mapregion.json}, from {@code tools/oracle_fixtures/region_mapregion_probe.c}).
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code identity_chain_dumps_only_the_frame}
 * (Circle), and {@code public_map_region_simplifies}
 * (needs simplification: {@link Region#mapRegion} of a Box through a PermMap gives C's re-fitted
 * Box only once Box has its own Simplify).
 */
class RegionMapRegionOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_mapregion");

    private static String expected(String name) {
        return DATA.get(name).textValue();
    }

    @Test
    void badLimitKeepsEveryAxis() {
        double bad = AstObject.AST__BAD;
        Interval iv = new Interval(new Frame(3), new double[] {0.0, 0.0, 0.0}, new double[] {1.0, 1.0, bad}, null);
        Mapping pm = new PermMap(3, new int[] {1, 2, 0}, 2, new int[] {1, 2}, null);
        assertEquals(expected("DROP_UNBOUNDED"), ChannelText.dump(iv.mapRegionInto(pm, new Frame(2))));
    }

    @Test
    void permmapWithBadInputSimplifiesAsCDoes() {
        Mapping pm = new PermMap(3, new int[] {1, 2, 0}, 2, new int[] {1, 2}, null);
        assertEquals(expected("PERMMAP_SIMPLIFIED"), ChannelText.dump(pm.simplify()));
    }
}
