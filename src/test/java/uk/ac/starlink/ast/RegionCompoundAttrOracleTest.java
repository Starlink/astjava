package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_compound_attr_oracle.rs}: how a CmpRegion and a
 * Prism share Closed, MeshSize and FillFactor with their components, against C: setting or clearing
 * Closed or MeshSize sets or clears it on both components too (cmpregion.c and prism.c {@code
 * MAKE_SET}, {@code MAKE_CLEAR}); {@code astInitCmpRegion} copies the first component's set
 * MeshSize and Closed; and the default FillFactor is the first component's for a CmpRegion and the
 * product of the components' for a Prism ({@code GetFillFactor}) ({@code
 * oracle/region_compound_attr.json}, from {@code tools/oracle_fixtures/region_compound_attr_probe.c}).
 */
class RegionCompoundAttrOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_compound_attr");

    private static Box box(double[] l, double[] u) {
        return new Box(new Frame(2), 1, l, u, null);
    }

    @Test
    void cmpRegionSharesClosedAndMeshSizeWithItsComponents() {
        Box b1 = box(new double[] {0.0, 0.0}, new double[] {2.0, 2.0});
        b1.setMeshSize(50);
        b1.setClosed(false);
        b1.setFillFactor(0.5);
        Box b2 = box(new double[] {1.0, 1.0}, new double[] {3.0, 3.0});
        CmpRegion cmp = new CmpRegion(b1, b2, CmpRegion.AST__OR);
        assertEquals(DATA.get("CMP_MADE").textValue(), ChannelText.dump(cmp));
        assertEquals(ProbeData.toDouble(DATA.get("CMP_FILL")), cmp.getFillFactor());

        cmp.setMeshSize(30);
        cmp.setClosed(true);
        assertEquals(DATA.get("CMP_SET").textValue(), ChannelText.dump(cmp));

        cmp.clear("MeshSize");
        cmp.clear("Closed");
        assertEquals(DATA.get("CMP_CLEARED").textValue(), ChannelText.dump(cmp));
    }

    @Test
    void prismSharesClosedAndMeshSizeWithItsComponents() {
        Box b = box(new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
        b.setFillFactor(0.5);
        Interval iv = new Interval(new Frame(1), new double[] {0.0}, new double[] {1.0}, null);
        iv.setFillFactor(0.4);
        Prism prism = new Prism(b, iv);
        assertEquals(ProbeData.toDouble(DATA.get("PRISM_FILL")), prism.getFillFactor());

        prism.setMeshSize(30);
        prism.setClosed(false);
        assertEquals(DATA.get("PRISM_SET").textValue(), ChannelText.dump(prism));

        prism.clear("MeshSize");
        prism.clear("Closed");
        assertEquals(DATA.get("PRISM_CLEARED").textValue(), ChannelText.dump(prism));
    }
}
