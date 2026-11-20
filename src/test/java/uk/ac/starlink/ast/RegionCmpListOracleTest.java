package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_cmplist_oracle.rs}: the protected {@code
 * astCmpRegionList} (cmpregion.c {@code CmpRegionList} and {@code XORCheck}) against C, for nested
 * ANDs and ORs, an OR containing an AND, an XOR, an OR containing an XOR, and an OR of two ANDs
 * equivalent to an XOR; and the dump of that last CmpRegion, which cmpregion.c {@code Dump} writes
 * as an XOR ({@code oracle/region_cmplist.json}, from {@code
 * tools/oracle_fixtures/region_cmplist_probe.c}).
 */
class RegionCmpListOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_cmplist");

    private static Region circle(double x) {
        return new Circle(new Frame(2), 1, new double[] {x, 0.0}, new double[] {1.0}, null);
    }

    private static CmpRegion cmp(Region a, Region b, int oper) {
        return new CmpRegion(a, b, oper);
    }

    private static Region negated(Region r) {
        Region n = (Region) r.copy();
        n.negate();
        return n;
    }

    private static CmpRegion handXor() {
        Region b = circle(3.0);
        Region c = circle(6.0);
        return cmp(cmp(b, negated(c), CmpRegion.AST__AND), cmp(negated(b), c, CmpRegion.AST__AND), CmpRegion.AST__OR);
    }

    private static CmpRegion build(String name) {
        switch (name) {
            case "a_and_b_and_c":
                return cmp(circle(0.0), cmp(circle(3.0), circle(6.0), CmpRegion.AST__AND), CmpRegion.AST__AND);
            case "a_or_b_or_c":
                return cmp(cmp(circle(0.0), circle(3.0), CmpRegion.AST__OR), circle(6.0), CmpRegion.AST__OR);
            case "a_or_b_and_c":
                return cmp(circle(0.0), cmp(circle(3.0), circle(6.0), CmpRegion.AST__AND), CmpRegion.AST__OR);
            case "b_xor_c":
                return cmp(circle(3.0), circle(6.0), 3);
            case "a_or_b_xor_c":
                return cmp(circle(0.0), cmp(circle(3.0), circle(6.0), 3), CmpRegion.AST__OR);
            case "hand_xor":
                return handXor();
            default:
                throw new AssertionError("no CmpRegion for case " + name);
        }
    }

    @Test
    void regionListsMatchTheCLibrary() {
        int ran = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            List<Region> list = new ArrayList<>();
            int oper = build(name).cmpRegionList(list);
            assertEquals(c.get(1).intValue(), oper, name + ": operator");
            JsonNode want = c.get(2);
            assertEquals(want.size(), list.size(), name + ": number of Regions");
            for (int i = 0; i < list.size(); i++) {
                assertEquals(want.get(i).textValue(), ChannelText.dump(list.get(i)), name + ": Region " + i);
            }
            ran++;
        }
        assertEquals(6, ran);
    }

    @Test
    void equivalentXorDumpsAsXor() {
        assertEquals(DATA.get("HAND_XOR_DUMP").textValue(), ChannelText.dump(handXor()));
    }
}
