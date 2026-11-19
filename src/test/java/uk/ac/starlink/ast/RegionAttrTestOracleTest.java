package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_attr_test_oracle.rs}: whether the Region attributes
 * Negated, Adaptive and RegionFS are set, against C (region.c {@code TestNegated}, {@code
 * TestAdaptive} and {@code TestRegionFS}): after construction, negation, setting, clearing,
 * reading a dump (region.c {@code Load}, which sets RegionFS and reads Negate and Adapt as set
 * only when the dump has them uncommented) and copying (region.c {@code Copy}, which clears
 * RegionFS) ({@code oracle/region_attr_test.json}, from {@code
 * tools/oracle_fixtures/region_attr_test_probe.c}).
 *
 * <p>The Rust test copies with {@code clone} followed by {@code clear_region_fs_deep}; that is
 * what C's {@code astCopy} does, so here it is {@link AstObject#copy}.
 */
class RegionAttrTestOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_attr_test");

    private static String state(Region r) {
        return Arrays.toString(new boolean[] {
            r.getNegated(), r.testNegated(), r.getAdaptive(), r.testAdaptive(), r.getRegionFS(), r.testRegionFS()
        });
    }

    private static Region reread(Region r) {
        return (Region) ChannelText.read(ChannelText.dump(r));
    }

    private static Box unitBox() {
        return new Box(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null);
    }

    @Test
    void setStatesMatchTheCLibrary() {
        Box bx = unitBox();
        List<String> got = new ArrayList<>();
        got.add("built " + state(bx));
        bx.negate();
        got.add("negated " + state(bx));
        got.add("negated_reread " + state(reread(bx)));
        bx.negate();
        got.add("negated_twice " + state(bx));
        got.add("negated_twice_reread " + state(reread(bx)));
        bx.clearNegated();
        bx.setAdaptive(false);
        got.add("not_adaptive " + state(bx));
        got.add("not_adaptive_reread " + state(reread(bx)));
        bx.clearAdaptive();
        got.add("cleared " + state(bx));
        got.add("cleared_reread " + state(reread(bx)));
        bx.setRegionFS(false);
        got.add("no_region_fs " + state(bx));
        got.add("no_region_fs_copy " + state((Region) bx.copy()));

        List<String> want = new ArrayList<>();
        for (JsonNode c : DATA.get("CASES")) {
            JsonNode v = c.get(1);
            boolean[] b = new boolean[v.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = v.get(i).booleanValue();
            }
            want.add(c.get(0).textValue() + " " + Arrays.toString(b));
        }
        assertEquals(want, got);
    }

    /** A Box whose Adaptive is set to 0 writes {@code Adapt = 0} uncommented (region.c {@code Dump}). */
    @Test
    void notAdaptiveDumpMatchesTheCLibrary() {
        Box bx = unitBox();
        bx.setAdaptive(false);
        assertEquals(DATA.get("NOT_ADAPTIVE_DUMP").textValue(), ChannelText.dump(bx));
    }
}
