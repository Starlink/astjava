package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_attrib_oracle.rs}: Frame attributes set and
 * cleared through a Region, against C (region.c {@code SetAttrib} and {@code ClearAttrib}). An
 * Adaptive Region is remapped into its changed current Frame; one that is not has the change made
 * to its current and base Frames alike, on the base Frame axis the current Frame axis is connected
 * to, and to its uncertainty ({@code RegSetAttrib}, {@code RegClearAttrib}). Each case is compared
 * with C's dump, byte for byte, and with whether C finds (0.5, 0.5) inside ({@code
 * oracle/region_attrib.json}, from {@code tools/oracle_fixtures/region_attrib_probe.c}).
 */
class RegionAttribOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_attrib");

    /** The unit square in a Frame with axes in metres and ActiveUnit set. */
    private static Region metreBox() {
        Frame frame = new Frame(2);
        frame.setUnit(1, "m");
        frame.setUnit(2, "m");
        frame.setActiveUnit(true);
        return new Box(frame, 1, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null);
    }

    private static Region unitBox(double[] lbnd, double[] ubnd, Region unc) {
        return new Box(new Frame(2), 1, lbnd, ubnd, unc);
    }

    /** The Box [0, 1] x [0, 2] with its current Frame axes swapped. */
    private static Region permutedBox() {
        Region reg = unitBox(new double[] {0.0, 0.0}, new double[] {1.0, 2.0}, null);
        reg.permAxes(new int[] {2, 1});
        return reg;
    }

    /** The Region a case builds. */
    private static Region region(String name) {
        switch (name) {
            case "unit_km_adaptive": {
                Region reg = metreBox();
                reg.setAdaptive(true);
                reg.setUnit(1, "km");
                return reg;
            }
            case "unit_km_not_adaptive": {
                Region reg = metreBox();
                reg.setAdaptive(false);
                reg.setUnit(1, "km");
                return reg;
            }
            case "unit_cleared_not_adaptive": {
                Region reg = metreBox();
                reg.setAdaptive(false);
                reg.setUnit(1, "km");
                reg.clear("Unit(1)");
                return reg;
            }
            case "permuted_box":
                return permutedBox();
            case "label_after_permaxes": {
                Region reg = permutedBox();
                reg.setAdaptive(false);
                reg.setLabel(1, "Swapped");
                return reg;
            }
            case "label_and_title_with_uncertainty": {
                Region unc = unitBox(new double[] {0.0, 0.0}, new double[] {0.01, 0.01}, null);
                Region reg = unitBox(new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, unc);
                reg.setAdaptive(false);
                reg.setLabel(2, "Second");
                reg.setTitle("Titled");
                return reg;
            }
            case "label_cmpregion": {
                Region reg = new CmpRegion(
                        unitBox(new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null),
                        unitBox(new double[] {0.5, 0.5}, new double[] {2.0, 2.0}, null),
                        CmpRegion.AST__OR);
                reg.setAdaptive(false);
                reg.setLabel(1, "Joined");
                return reg;
            }
            case "labels_prism": {
                Interval interval = new Interval(new Frame(1), new double[] {0.0}, new double[] {1.0}, null);
                Region reg = new Prism(unitBox(new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null), interval);
                reg.setAdaptive(false);
                reg.setLabel(3, "Height");
                reg.setSymbol(1, "X");
                return reg;
            }
            default:
                throw new AssertionError("no construction for case " + name);
        }
    }

    /** Whether (0.5, 0.5) is inside a 2-D Region: 1, 0, or -1 when not 2-D. */
    private static int inside(Region reg) {
        if (reg.getNaxes() != 2) {
            return -1;
        }
        double[][] out = reg.tran2(1, new double[] {0.5}, new double[] {0.5}, true);
        return out[0][0] != AstObject.AST__BAD ? 1 : 0;
    }

    @Test
    void settingsMatchTheCLibrary() {
        int run = 0;
        for (JsonNode c : DATA.get("CASES")) {
            String name = c.get(0).textValue();
            Region reg = region(name);
            run++;
            assertEquals(c.get(1).intValue(), inside(reg), name + ": (0.5, 0.5)");
            assertEquals(c.get(2).textValue(), ChannelText.dump(reg), name + ": dump");
        }
        assertEquals(8, run);
    }
}
