package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * A PointSet, the defining points of a Region in its description, reads, writes and compares as
 * C's {@code pointset.c} does ({@code astLoadPointSet}, {@code Dump}, {@code Equal}).
 *
 * <p>The PointSets of {@code simplify/cap_slamap_07.map} are nested in Regions, which this test
 * does not read; each {@code Begin PointSet} block of the fixture is taken out of it, moved to the
 * top level, read, and written again as the same text. The descriptions of {@code
 * probes/frame_line_probe.c} ({@code pstext}), with bad values, accuracies, an empty PointSet and
 * invalid sizes, are read and written as C writes them, and compared as C's {@code astEqual}
 * compares them.
 */
class PointSetTest {

    /** The probe's {@code pstext}: each name with the lines of its description. */
    private static final String[][] PSTEXT = {
        {
            "plain",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    X1 = 0",
            "    X2 = 0",
            "    X3 = 0.0001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "rel_close",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    X1 = 0",
            "    X2 = 0",
            "    X3 = 0.00010000000000001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "rel_far",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    X1 = 0",
            "    X2 = 0",
            "    X3 = 0.0001000000000001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "tiny_close",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    X1 = 1e-152",
            "    X2 = 0",
            "    X3 = 0.0001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "tiny_far",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    X1 = 1e-150",
            "    X2 = 0",
            "    X3 = 0.0001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "bad",
            " Begin PointSet",
            "    Npoint = 3",
            "    Ncoord = 2",
            "    X1 = 1.5",
            "    X2 = 2.5",
            "    X4 = -3",
            "    X6 = 7",
            " End PointSet"
        },
        {
            "bad_other",
            " Begin PointSet",
            "    Npoint = 3",
            "    Ncoord = 2",
            "    X1 = 1.5",
            "    X2 = 2.5",
            "    X3 = 4",
            "    X4 = -3",
            "    X6 = 7",
            " End PointSet"
        },
        {
            "first_bad",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    X2 = 2.5",
            "    X3 = 4",
            "    X4 = -3",
            " End PointSet"
        },
        {
            "acc",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    Acc1 = 0.1",
            "    X1 = 0",
            "    X2 = 0",
            "    X3 = 0.0001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "acc_close",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    Acc1 = 0.1",
            "    X1 = 0.1",
            "    X2 = 0",
            "    X3 = 0.0001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "acc_far",
            " Begin PointSet",
            "    Npoint = 2",
            "    Ncoord = 2",
            "    Acc1 = 0.1",
            "    X1 = 0.2",
            "    X2 = 0",
            "    X3 = 0.0001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {
            "three",
            " Begin PointSet",
            "    Npoint = 3",
            "    Ncoord = 2",
            "    X1 = 0",
            "    X2 = 0",
            "    X3 = 0.0001",
            "    X4 = 0.0001",
            " End PointSet"
        },
        {"empty", " Begin PointSet", "    Npoint = 2", "    Ncoord = 3", "    Empty = 1", "    X1 = 5", " End PointSet"
        },
        {"zero", " Begin PointSet", "    Npoint = 0", "    Ncoord = -1", "    X1 = 5", " End PointSet"},
    };

    private static String text(String[] desc) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < desc.length; i++) {
            sb.append(desc[i]).append('\n');
        }
        return sb.toString();
    }

    private static PointSet read(String text) {
        return assertInstanceOf(PointSet.class, ChannelText.read(text));
    }

    /** The probe's dump of each named PointSet, and its {@code equal} lines. */
    private static final class Probe {
        final Map<String, String> dumps = new LinkedHashMap<>();
        final List<String[]> equal = new ArrayList<>();

        Probe() {
            String name = null;
            StringBuilder sb = new StringBuilder();
            for (String line :
                    ProbeOutput.load("frame_line_probe").get("pointset").split("\n", -1)) {
                if (line.startsWith("pointset ") || line.startsWith("equal ") || line.isEmpty()) {
                    if (name != null) {
                        dumps.put(name, sb.toString());
                        name = null;
                    }
                    String[] w = line.split(" ", -1);
                    if (line.startsWith("pointset ")) {
                        name = w[1];
                        sb.setLength(0);
                    } else if (!line.isEmpty()) {
                        equal.add(new String[] {w[1], w[2], w[4]});
                    }
                } else {
                    sb.append(line).append('\n');
                }
            }
        }
    }

    @Test
    void descriptionsAreReadAndWrittenAsCDoes() {
        Probe probe = new Probe();
        assertEquals(PSTEXT.length, probe.dumps.size());
        for (String[] desc : PSTEXT) {
            String want = probe.dumps.get(desc[0]);
            assertNotNull(want, desc[0]);
            assertEquals(want, ProbeOutput.trimLines(ChannelText.dump(read(text(desc)))), desc[0]);
        }
    }

    @Test
    void pointSetsCompareAsCDoes() {
        Probe probe = new Probe();
        Map<String, PointSet> sets = new LinkedHashMap<>();
        for (String[] desc : PSTEXT) {
            sets.put(desc[0], read(text(desc)));
        }
        assertEquals(20, probe.equal.size());
        for (String[] e : probe.equal) {
            PointSet a = sets.get(e[0]);
            PointSet b = sets.get(e[1]);
            assertEquals("1".equals(e[2]), a.astEqual(b), e[0] + " " + e[1]);
            if (!e[0].equals(e[1])) {
                // A copy is a different object, so this compares the values, not the reference.
                assertEquals("1".equals(e[2]), a.astEqual(b.copy()), e[0] + " " + e[1] + " (copy)");
            }
        }
        assertTrue(sets.get("plain").astEqual(sets.get("plain").copy()));
        assertTrue(sets.get("bad").astEqual(sets.get("bad").copy()));
    }

    /** The values a description gives, missing ones bad, as {@code [ncoord][npoint]}. */
    @Test
    void valuesAreReadPointByPoint() {
        double bad = AstObject.AST__BAD;
        PointSet ps = read(text(PSTEXT[5]));
        assertEquals(3, ps.getNpoint());
        assertEquals(2, ps.getNcoord());
        assertArrayEquals(new double[][] {{1.5, bad, bad}, {2.5, -3.0, 7.0}}, ps.points());
        PointSet zero = read(text(PSTEXT[13]));
        assertArrayEquals(new double[][] {{5.0}}, zero.points());
    }

    /**
     * C's {@code astGetPoints} gives the values of an empty PointSet uninitialised; here asking for
     * them is an error.
     */
    @Test
    void anEmptyPointSetHasNoValues() {
        PointSet empty = read(text(PSTEXT[12]));
        assertEquals(2, empty.getNpoint());
        assertEquals(3, empty.getNcoord());
        AstException e = assertThrows(AstException.class, empty::points);
        assertEquals(AstException.AST__INTER, e.getStatus());
    }

    @Test
    void aPointSetIsMadeFromItsValues() {
        double[][] v = {{0.0, 1e-4}, {0.0, 1e-4}};
        PointSet ps = new PointSet(v);
        v[0][0] = 9.0;
        assertArrayEquals(new double[][] {{0.0, 1e-4}, {0.0, 1e-4}}, ps.points());
        double[][] got = ps.points();
        got[1][1] = 9.0;
        assertArrayEquals(new double[][] {{0.0, 1e-4}, {0.0, 1e-4}}, ps.points());
        assertTrue(ps.astEqual(read(text(PSTEXT[0]))));
        assertEquals(new Probe().dumps.get("plain"), ProbeOutput.trimLines(ChannelText.dump(ps)));
        PointSet copy = (PointSet) ps.copy();
        assertTrue(ps.astEqual(copy));
        assertFalse(ps.astEqual(new PointSet(new double[][] {{0.0, 1e-4, 0.0}, {0.0, 1e-4, 0.0}})));
    }

    /** C's {@code astInitPointSet} rejects a PointSet with no points or no coordinates. */
    @Test
    void aPointSetHasPointsAndCoordinates() {
        AstException e = assertThrows(AstException.class, () -> new PointSet(new double[][] {{}, {}}));
        assertEquals(AstException.AST__NPTIN, e.getStatus());
        assertEquals("astInitPointSet(PointSet): Number of points (0) is not valid.", e.getMsgText());
        e = assertThrows(AstException.class, () -> new PointSet(new double[0][]));
        assertEquals(AstException.AST__NCOIN, e.getStatus());
        assertEquals("astInitPointSet(PointSet): Number of coordinates per point (0) is not valid.", e.getMsgText());
    }

    /**
     * Each {@code Begin PointSet} block of {@code simplify/cap_slamap_07.map}, moved to the top
     * level, reads and writes again as the same text.
     */
    @Test
    void theFixturesPointSetsRoundTrip() {
        List<String> blocks = pointSetBlocks(Fixtures.read("simplify/cap_slamap_07.map"));
        assertEquals(4, blocks.size());
        List<PointSet> sets = new ArrayList<>();
        for (String block : blocks) {
            PointSet ps = read(block);
            assertEquals(block, ChannelText.dump(ps));
            sets.add(ps);
        }
        assertArrayEquals(new double[][] {{0.0, 1e-4}, {0.0, 1e-4}}, sets.get(0).points());
        assertArrayEquals(
                new double[][] {
                    {num("1.68166715892457"), num("1.6814025477719401")},
                    {num("-1.0504365074719999"), num("-1.0504884000346699")}
                },
                sets.get(1).points());
        assertTrue(sets.get(0).astEqual(sets.get(2)));
        assertTrue(sets.get(1).astEqual(sets.get(3)));
        assertFalse(sets.get(0).astEqual(sets.get(1)));
    }

    /** The value C writes as {@code text}, which has more digits than a Java literal may. */
    private static double num(String text) {
        return Double.parseDouble(text);
    }

    /** The {@code Begin PointSet} blocks of {@code text}, each indented as at the top level. */
    private static List<String> pointSetBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder sb = null;
        int indent = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Begin PointSet")) {
                sb = new StringBuilder();
                indent = line.indexOf("Begin") - 1;
            }
            if (sb != null) {
                sb.append(line.substring(indent)).append('\n');
                if (trimmed.startsWith("End PointSet")) {
                    blocks.add(sb.toString());
                    sb = null;
                }
            }
        }
        return blocks;
    }
}
