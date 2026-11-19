package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_object.rs}: the Object part of Region dumps,
 * checked against C ({@code oracle/region_object.json}, from {@code
 * tools/oracle_fixtures/region_object_probe.c}). A Region keeps its own Ident, written before the
 * Frame part (object.c {@code Dump}), while UseDefs set through it goes to the encapsulated Frame
 * and is written in that Frame's dump (region.c {@code SetAttrib}, {@code GetUseDefs}). Whether
 * UseDefs is set is asked as C's probe asks it, through the attribute ({@code astTest}).
 */
class RegionObjectOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_object");

    private static Circle circle() {
        return new Circle(new Frame(2), new double[] {0.0, 0.0}, 1.0, null);
    }

    /** The Region a case builds. */
    private static Region build(String name) {
        switch (name) {
            case "box_ident": {
                Box b = new Box(new Frame(2), 0, new double[] {0.0, 0.0}, new double[] {1.0, 2.0}, null);
                b.setIdent("B");
                return b;
            }
            case "interval_usedefs": {
                Interval iv = new Interval(new Frame(2), new double[] {-1.0, -2.0}, new double[] {1.0, 2.0}, null);
                iv.set("UseDefs=0");
                return iv;
            }
            case "circle_ident_usedefs": {
                Circle c = circle();
                c.setIdent("Circ");
                c.set("UseDefs=0");
                return c;
            }
            case "circle_plain":
                return circle();
            case "polygon_ident": {
                Polygon p =
                        new Polygon(new Frame(2), 3, new double[] {0.0, 1.0, 0.0}, new double[] {0.0, 0.0, 1.0}, null);
                p.setIdent("P");
                return p;
            }
            case "cmpregion_idents": {
                Circle c = circle();
                c.setIdent("A");
                Box b = new Box(new Frame(2), 0, new double[] {0.0, 0.0}, new double[] {1.0, 2.0}, null);
                b.setIdent("B");
                CmpRegion r = new CmpRegion(c, b, CmpRegion.AST__OR);
                r.setIdent("AB");
                return r;
            }
            default:
                throw new AssertionError("no construction for case " + name);
        }
    }

    private static String objectPart(Region r) {
        return r.getIdent() + " " + r.getUseDefs() + " " + r.test("UseDefs");
    }

    @Test
    void regionObjectPartsMatchTheCLibrary() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region r = build(name);
            run++;
            String want = c.get(1).textValue() + " " + c.get(2).booleanValue() + " "
                    + c.get(3).booleanValue();
            String dump = c.get(4).textValue();
            assertEquals(want, objectPart(r), name);
            assertEquals(dump, ChannelText.dump(r), name + ": dump");
            assertEquals(want, objectPart((Region) ChannelText.read(dump)), name + ": read back");
        }
        assertEquals(6, run);
    }
}
