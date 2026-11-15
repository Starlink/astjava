package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the unit tests in Rust {@code serial/native_write.rs}. The {@code fmt_*} values
 * are C's {@code %.17g} output. The Rust {@code round_trip_*} tests read files from the top of
 * {@code ast_tester}, which are now under {@code ast_tester/fixtures}; they read them from there.
 */
class NativeWriterTest {

    private static String render(NativeNode node) {
        return NativeWriter.render(node, ChannelOptions.defaults());
    }

    @Test
    void fmtZero() {
        assertEquals("0", NativeWriter.formatDoubleG(0.0, 17));
    }

    @Test
    void fmtNegZero() {
        assertEquals("0", NativeWriter.formatDoubleG(-0.0, 17));
    }

    @Test
    void fmtIntegers() {
        assertEquals("1", NativeWriter.formatDoubleG(1.0, 17));
        assertEquals("42", NativeWriter.formatDoubleG(42.0, 17));
        assertEquals("-129", NativeWriter.formatDoubleG(-129.0, 17));
    }

    @Test
    void fmtSmallDecimals() {
        assertEquals("-0.5", NativeWriter.formatDoubleG(-0.5, 17));
        assertEquals("0.0001", NativeWriter.formatDoubleG(0.0001, 17));
        assertEquals("-0.0001", NativeWriter.formatDoubleG(-0.0001, 17));
        assertEquals("0.001", NativeWriter.formatDoubleG(0.001, 17));
    }

    @Test
    void fmtFullPrecision() {
        assertEquals("3.1400000000000001", NativeWriter.formatDoubleG(3.14, 17));
        assertEquals("0.99619469800000005", NativeWriter.formatDoubleG(0.996194698, 17));
        assertEquals("0.087155742999999994", NativeWriter.formatDoubleG(0.087155743, 17));
    }

    @Test
    void fmtScientificSmall() {
        assertEquals("1.0000000000000001e-05", NativeWriter.formatDoubleG(1e-5, 17));
        assertEquals("9.9989999999999996e-05", NativeWriter.formatDoubleG(9.999e-5, 17));
        assertEquals("-5.8416170064250195e-07", NativeWriter.formatDoubleG(-5.84161700642502e-07, 17));
        assertEquals("5.2359877559829903e-06", NativeWriter.formatDoubleG(5.23598775598299e-06, 17));
    }

    @Test
    void fmtScientificLarge() {
        assertEquals("1e+17", NativeWriter.formatDoubleG(1e17, 17));
        assertEquals("1e+100", NativeWriter.formatDoubleG(1e100, 17));
        assertEquals("1.2345678901234569e+100", NativeWriter.formatDoubleG(1.2345678901234569e100, 17));
    }

    @Test
    void fmtBoundaryFixedLarge() {
        assertEquals("10000000000000000", NativeWriter.formatDoubleG(1e16, 17));
    }

    @Test
    void fmtExactValue() {
        assertEquals("2003.0173483725", NativeWriter.formatDoubleG(2003.0173483725, 17));
    }

    @Test
    void fmtExtremeValues() {
        assertEquals("1.7976931348623157e+308", NativeWriter.formatDoubleG(Double.MAX_VALUE, 17));
        assertEquals("2.2250738585072014e-308", NativeWriter.formatDoubleG(Double.MIN_NORMAL, 17));
        assertEquals("4.9406564584124654e-324", NativeWriter.formatDoubleG(Double.MIN_VALUE, 17));
    }

    @Test
    void fmtLowPrecision() {
        assertEquals("3", NativeWriter.formatDoubleG(3.14, 1));
        assertEquals("0.006", NativeWriter.formatDoubleG(0.0056, 1));
        assertEquals("1e+02", NativeWriter.formatDoubleG(99.9, 1));
    }

    @Test
    void renderSimpleObject() {
        NativeNode node = new NativeNode("ZoomMap", "Zoom about the origin");
        node.writeInt("Nin", true, true, 2, "Number of input coordinates");
        node.writeInt("Invert", true, true, 0, "Mapping not inverted");
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeDouble("Zoom", true, true, 5.23598775598299e-06, "Zoom factor");
        String text = render(node);
        assertTrue(text.startsWith(" Begin ZoomMap \t# Zoom about the origin\n"));
        assertTrue(text.contains("    Nin = 2 \t# Number of input coordinates\n"));
        assertTrue(text.contains("    Invert = 0 \t# Mapping not inverted\n"));
        assertTrue(text.contains(" IsA Mapping \t# Mapping between coordinate systems\n"));
        assertTrue(text.contains("    Zoom = 5.2359877559829903e-06 \t# Zoom factor\n"));
        assertTrue(text.endsWith(" End ZoomMap\n"));
    }

    @Test
    void renderNestedObject() {
        NativeNode inner = new NativeNode("UnitMap", "Unit (null) Mapping");
        inner.writeInt("Nin", true, true, 2, "Number of input coordinates");
        inner.writeIsA("Mapping", "Mapping between coordinate systems");
        NativeNode outer = new NativeNode("CmpMap", "Compound Mapping");
        outer.writeInt("Nin", true, true, 2, "Number of input coordinates");
        outer.writeIsA("Mapping", "Mapping between coordinate systems");
        outer.writeObject("MapA", true, true, inner, "First component Mapping");
        String text = render(outer);
        assertTrue(text.startsWith(" Begin CmpMap \t# Compound Mapping\n"));
        assertTrue(text.contains("    Nin = 2 \t# Number of input coordinates\n"));
        assertTrue(text.contains("    MapA = \t# First component Mapping\n"));
        assertTrue(text.contains("       Begin UnitMap \t# Unit (null) Mapping\n"));
        assertTrue(text.contains("          Nin = 2 \t# Number of input coordinates\n"));
        assertTrue(text.contains("       IsA Mapping \t# Mapping between coordinate systems\n"));
        assertTrue(text.contains("       End UnitMap\n"));
        assertTrue(text.endsWith(" End CmpMap\n"));
    }

    @Test
    void renderUnsetAttributes() {
        NativeNode node = new NativeNode("FrameSet", "Set of inter-related coordinate systems");
        node.add("Title", NativeNode.Value.ofString("test title"), false, "Title of coordinate system", true);
        node.add("Naxes", NativeNode.Value.ofInt(3), false, "Number of coordinate axes", true);
        node.writeInt("ActUnt", true, true, 0, "Unit strings do not affect alignment");
        node.writeIsA("Frame", "Coordinate system description");
        String text = render(node);
        assertTrue(text.contains("#   Title = \"test title\" \t# Title of coordinate system\n"));
        assertTrue(text.contains("#   Naxes = 3 \t# Number of coordinate axes\n"));
        assertTrue(text.contains("    ActUnt = 0 \t# Unit strings do not affect alignment\n"));
    }

    @Test
    void renderStringWithQuotes() {
        NativeNode node = new NativeNode("Axis", "Coordinate axis");
        node.writeString("Label", true, true, "has\"quotes", "Axis Label");
        assertTrue(render(node).contains("    Label = \"has\"\"quotes\" \t# Axis Label\n"));
    }

    @Test
    void renderNoComment() {
        NativeNode node = new NativeNode("LutMap");
        node.writeDouble("L2", true, true, 1.5, null);
        node.writeIsA("Mapping", null);
        String text = render(node);
        assertTrue(text.startsWith(" Begin LutMap\n"));
        assertTrue(text.contains("    L2 = 1.5\n"));
        assertTrue(text.contains(" IsA Mapping\n"));
        assertTrue(text.endsWith(" End LutMap\n"));
    }

    private static void assertRoundTrip(String rel) {
        NativeNode node = NativeReader.parse(Fixtures.read(rel));
        NativeNode node2 = NativeReader.parse(render(node));
        assertEquals(node.className, node2.className);
        assertEquals(NativeReaderTest.countNodes(node), NativeReaderTest.countNodes(node2));
        assertEquals(node.items.size(), node2.items.size());
        for (int i = 0; i < node.items.size(); i++) {
            assertEquals(node.items.get(i).key, node2.items.get(i).key, "key mismatch");
        }
    }

    @Test
    void roundTripSplittest1() {
        assertRoundTrip("programs/testcmpmap/splittest1.ast");
    }

    @Test
    void roundTripDegen1() {
        assertRoundTrip("wcsconv/inputs/degen1.ast");
        NativeNode node = NativeReader.parse(Fixtures.read("wcsconv/inputs/degen1.ast"));
        NativeNode node2 = NativeReader.parse(render(node));
        assertEquals(node.readObject("Frm4").className, node2.readObject("Frm4").className);
    }
}
