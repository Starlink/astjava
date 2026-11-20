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
 * oracle/region_mapregion.json}, from {@code tools/oracle_fixtures/region_mapregion_probe.c}); and
 * the public {@link Region#mapRegion} of a Box through a PermMap that swaps its axes, which box.c
 * {@code Simplify} redefines in the new Frame.
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
    void publicMapRegionSimplifies() {
        Box bx = new Box(new Frame(2), 1, new double[] {0.0, 1.0}, new double[] {2.0, 4.0}, null);
        Mapping pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        assertEquals(expected("SWAPPED_PUBLIC"), ChannelText.dump(bx.mapRegion(pm, new Frame(2))));
    }

    @Test
    void identityChainDumpsOnlyTheFrame() {
        Frame foo = new Frame(2);
        foo.setDomain("FOO");
        Frame bar = new Frame(2);
        bar.setDomain("BAR");
        Circle circle = new Circle(foo, new double[] {1.0, 0.5}, 0.1, null);
        Mapping chain = new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 0.5), true);
        assertEquals(expected("IDENTITY_CHAIN"), ChannelText.dump(circle.mapRegionInto(chain, bar)));
    }

    @Test
    void permmapWithBadInputSimplifiesAsCDoes() {
        Mapping pm = new PermMap(3, new int[] {1, 2, 0}, 2, new int[] {1, 2}, null);
        assertEquals(expected("PERMMAP_SIMPLIFIED"), ChannelText.dump(pm.simplify()));
    }
}
