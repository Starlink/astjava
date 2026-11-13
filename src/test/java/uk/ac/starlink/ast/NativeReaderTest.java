package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the unit tests in Rust {@code serial/native_read.rs}. The Rust {@code smoke_*}
 * tests read files from the top of {@code ast_tester}, which are now under {@code
 * ast_tester/fixtures}; they read them from there.
 */
class NativeReaderTest {

    @Test
    void splitCommentBasic() {
        String[] s = NativeReader.splitComment("Nin = 4 \t# Number of input coordinates");
        assertEquals("Nin = 4 \t", s[0]);
        assertEquals("Number of input coordinates", s[1]);
    }

    @Test
    void splitCommentNoHash() {
        String[] s = NativeReader.splitComment("L2 = 1.5");
        assertEquals("L2 = 1.5", s[0]);
        assertNull(s[1]);
    }

    @Test
    void splitCommentHashInQuotes() {
        String[] s = NativeReader.splitComment("Fmt = \"value # with hash\" \t# real comment");
        assertEquals("Fmt = \"value # with hash\" \t", s[0]);
        assertEquals("real comment", s[1]);
    }

    @Test
    void splitCommentBareHash() {
        String[] s = NativeReader.splitComment("something #");
        assertEquals("something ", s[0]);
        assertNull(s[1]);
    }

    @Test
    void parseScalarInteger() {
        assertEquals(NativeNode.Value.ofInt(42), NativeReader.parseScalarValue("42"));
        assertEquals(NativeNode.Value.ofInt(-129), NativeReader.parseScalarValue("-129"));
        assertEquals(NativeNode.Value.ofInt(0), NativeReader.parseScalarValue("0"));
    }

    @Test
    void parseScalarDouble() {
        assertEquals(NativeNode.Value.ofDouble(0.5), NativeReader.parseScalarValue("0.5"));
        assertEquals(NativeNode.Value.ofDouble(-0.5), NativeReader.parseScalarValue("-0.5"));
        assertEquals(
                NativeNode.Value.ofDouble(5.23598775598299e-06), NativeReader.parseScalarValue("5.23598775598299e-06"));
        assertEquals(NativeNode.Value.ofDouble(2e-06), NativeReader.parseScalarValue("2e-06"));
    }

    @Test
    void parseScalarQuotedString() {
        assertEquals(NativeNode.Value.ofString("Full"), NativeReader.parseScalarValue("\"Full\""));
        assertEquals(NativeNode.Value.ofString(""), NativeReader.parseScalarValue("\"\""));
    }

    @Test
    void parseScalarQuotedStringWithEscapedQuotes() {
        assertEquals(NativeNode.Value.ofString("has\"quotes"), NativeReader.parseScalarValue("\"has\"\"quotes\""));
    }

    @Test
    void parseScalarUnquotedString() {
        assertEquals(NativeNode.Value.ofString("GALACTIC"), NativeReader.parseScalarValue("GALACTIC"));
    }

    @Test
    void parseCommentedDefault() {
        NativeNode.Item item = NativeReader.parseCommentedDefault("   Title = \"test\" \t# Title of coordinate system");
        assertNotNull(item);
        assertEquals("Title", item.key);
        assertFalse(item.set);
        assertEquals(NativeNode.Value.ofString("test"), item.value);
        assertEquals("Title of coordinate system", item.comment);
    }

    @Test
    void parseCommentedDefaultInt() {
        NativeNode.Item item = NativeReader.parseCommentedDefault("   Base = 1 \t# Index of base Frame");
        assertNotNull(item);
        assertEquals("Base", item.key);
        assertFalse(item.set);
        assertEquals(NativeNode.Value.ofInt(1), item.value);
    }

    @Test
    void plainCommentNotDefault() {
        assertNull(NativeReader.parseCommentedDefault(" This is a comment"));
        assertNull(NativeReader.parseCommentedDefault(""));
        assertNull(NativeReader.parseCommentedDefault("  "));
    }

    @Test
    void parseSimpleObject() {
        String text = "\n"
                + " Begin ZoomMap \t# Zoom about the origin\n"
                + "    Nin = 2 \t# Number of input coordinates\n"
                + "    Invert = 0 \t# Mapping not inverted\n"
                + " IsA Mapping \t# Mapping between coordinate systems\n"
                + "    Zoom = 5.23598775598299e-06 \t# Zoom factor\n"
                + " End ZoomMap\n";
        NativeNode node = NativeReader.parse(text);
        assertEquals("ZoomMap", node.className);
        assertEquals("Zoom about the origin", node.classComment);
        assertEquals(2, node.readLong("Nin", 0));
        assertEquals(0, node.readLong("Invert", -1));
        assertEquals(5.23598775598299e-06, node.readDouble("Zoom", 0.0));
        NativeNode.Item isa = null;
        for (NativeNode.Item i : node.items) {
            if (i.isIsA()) {
                isa = i;
                break;
            }
        }
        assertNotNull(isa, "IsA marker missing");
        assertEquals(NativeNode.Value.ofString("Mapping"), isa.value);
    }

    @Test
    void parseNestedObject() {
        String text = "\n"
                + " Begin CmpMap \t# Compound Mapping\n"
                + "    Nin = 2 \t# Number of input coordinates\n"
                + " IsA Mapping \t# Mapping between coordinate systems\n"
                + "    MapA = \t# First component Mapping\n"
                + "       Begin UnitMap \t# Unit (null) Mapping\n"
                + "          Nin = 2 \t# Number of input coordinates\n"
                + "       IsA Mapping \t# Mapping between coordinate systems\n"
                + "       End UnitMap\n"
                + "    MapB = \t# Second component Mapping\n"
                + "       Begin ZoomMap \t# Zoom about the origin\n"
                + "          Nin = 2 \t# Number of input coordinates\n"
                + "       IsA Mapping \t# Mapping between coordinate systems\n"
                + "          Zoom = 2 \t# Zoom factor\n"
                + "       End ZoomMap\n"
                + " End CmpMap\n";
        NativeNode node = NativeReader.parse(text);
        assertEquals("CmpMap", node.className);
        NativeNode mapA = node.readObject("MapA");
        assertNotNull(mapA, "MapA missing");
        assertEquals("UnitMap", mapA.className);
        NativeNode mapB = node.readObject("MapB");
        assertNotNull(mapB, "MapB missing");
        assertEquals("ZoomMap", mapB.className);
        assertEquals(2, mapB.readLong("Zoom", 0));
    }

    @Test
    void parseCommentedDefaultsInObject() {
        String text = "\n"
                + " Begin FrameSet \t# Set of inter-related coordinate systems\n"
                + "#   Title = \"test title\" \t# Title of coordinate system\n"
                + "#   Naxes = 3 \t# Number of coordinate axes\n"
                + "    ActUnt = 0 \t# Unit strings do not affect alignment\n"
                + " IsA Frame \t# Coordinate system description\n"
                + "    Nframe = 1 \t# Number of Frames in FrameSet\n"
                + " End FrameSet\n";
        NativeNode node = NativeReader.parse(text);
        assertEquals("FrameSet", node.className);
        assertNull(node.get("Title"));
        NativeNode.Item title = node.item("Title");
        assertNotNull(title, "Title item missing");
        assertFalse(title.set);
        assertEquals(0, node.readLong("ActUnt", -1));
    }

    @Test
    void parseBeginNoComment() {
        NativeNode node = NativeReader.parse("  Begin FrameSet\n  IsA Frame\n  End FrameSet\n");
        assertEquals("FrameSet", node.className);
        assertNull(node.classComment);
    }

    @Test
    void parseLeadingCommentsSkipped() {
        String text = "\n"
                + "#\n"
                + "#  This is a comment block.\n"
                + "#\n"
                + " Begin UnitMap \t# Unit (null) Mapping\n"
                + "    Nin = 1 \t# Number of input coordinates\n"
                + " IsA Mapping \t# Mapping between coordinate systems\n"
                + " End UnitMap\n";
        NativeNode node = NativeReader.parse(text);
        assertEquals("UnitMap", node.className);
        assertEquals(1, node.readLong("Nin", 0));
    }

    @Test
    void parseValueNoComment() {
        NativeNode node = NativeReader.parse(" Begin LutMap\n IsA Mapping\n    L2 = 1.5\n End LutMap\n");
        assertEquals(1.5, node.readDouble("L2", 0.0));
        assertNull(node.item("L2").comment);
    }

    @Test
    void parseEmptyStringValue() {
        NativeNode node = NativeReader.parse(" Begin Axis\n    Unit = \"\" \t# Axis units\n End Axis\n");
        assertEquals("", node.readString("Unit", "default"));
    }

    @Test
    void errorMissingBegin() {
        assertThrows(AstException.class, () -> NativeReader.parse("  "));
    }

    @Test
    void errorMismatchedEnd() {
        AstException e = assertThrows(AstException.class, () -> NativeReader.parse(" Begin Foo\n End Bar\n"));
        assertTrue(e.getMessage().contains("mismatched End"), e.getMessage());
    }

    @Test
    void errorNoEnd() {
        AstException e = assertThrows(AstException.class, () -> NativeReader.parse(" Begin Foo\n    Nin = 1\n"));
        assertTrue(e.getMessage().contains("no End found"), e.getMessage());
    }

    /** The number of Objects in a description, itself included. */
    static int countNodes(NativeNode node) {
        int count = 1;
        for (NativeNode.Item item : node.items) {
            if (item.value.kind == NativeNode.Kind.OBJECT) {
                count += countNodes(item.value.object);
            }
        }
        return count;
    }

    private static void collectClasses(NativeNode node, Set<String> classes) {
        classes.add(node.className);
        for (NativeNode.Item item : node.items) {
            if (item.value.kind == NativeNode.Kind.OBJECT) {
                collectClasses(item.value.object, classes);
            }
        }
    }

    private static boolean hasUnsetKey(NativeNode node, String key) {
        for (NativeNode.Item item : node.items) {
            if (item.key.equals(key) && !item.set) {
                return true;
            }
            if (item.value.kind == NativeNode.Kind.OBJECT && hasUnsetKey(item.value.object, key)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void smokeSplittest1Ast() {
        NativeNode node = NativeReader.parse(Fixtures.read("programs/testcmpmap/splittest1.ast"));
        assertEquals("CmpMap", node.className);
        assertEquals(4, node.readLong("Nin", 0));
        int total = countNodes(node);
        assertTrue(total >= 15, "expected at least 15 objects, got " + total);
        NativeNode mapA = node.readObject("MapA");
        assertEquals("UnitMap", mapA.className);
        assertEquals(4, mapA.readLong("Nin", 0));
        assertEquals(1, mapA.readLong("Invert", -1));
        assertTrue(hasUnsetKey(node, "Incr"), "should have at least one Unset Incr");
    }

    @Test
    void smokeDegen1Ast() {
        NativeNode node = NativeReader.parse(Fixtures.read("wcsconv/inputs/degen1.ast"));
        assertEquals("FrameSet", node.className);
        assertEquals(4, node.readLong("Nframe", 0));
        assertEquals(4, node.readLong("Currnt", 0)); // codespell:ignore currnt
        NativeNode frm4 = node.readObject("Frm4");
        assertEquals("CmpFrame", frm4.className);
        NativeNode frameA = frm4.readObject("FrameA");
        assertEquals("SkyFrame", frameA.className);
        assertEquals("FK5", frameA.readString("System", ""));
    }

    @Test
    void smokeBradMap() {
        NativeNode node = NativeReader.parse(Fixtures.read("simplify/brad.map"));
        assertEquals("CmpMap", node.className);
        assertEquals(3, node.readLong("Nin", 0));
        assertEquals("CmpMap", node.readObject("MapA").className);
        NativeNode mapB = node.readObject("MapB");
        assertEquals("CmpMap", mapB.className);
        NativeNode zoom = mapB.readObject("MapA");
        assertEquals("ZoomMap", zoom.className);
        assertEquals(5.23598775598299e-06, zoom.readDouble("Zoom", 0.0));
    }

    @Test
    void refSplittest1Parse() {
        NativeNode node = NativeReader.parse(Fixtures.read("programs/testcmpmap/splittest1.ast"));
        assertEquals("CmpMap", node.className);
        assertEquals(4, node.readLong("Nin", 0));
        assertEquals("Compound Mapping", node.classComment);
        assertEquals(17, countNodes(node));
        NativeNode mapA = node.readObject("MapA");
        assertEquals("UnitMap", mapA.className);
        assertEquals(4, mapA.readLong("Nin", 0));
        assertEquals(1, mapA.readLong("Invert", -1));
        assertEquals("CmpMap", node.readObject("MapB").className);
        Set<String> classes = new TreeSet<>();
        collectClasses(node, classes);
        for (String expected : new String[] {"CmpMap", "UnitMap", "WinMap", "LutMap", "MatrixMap"}) {
            assertTrue(classes.contains(expected), "expected class " + expected + " not found; have: " + classes);
        }
        assertTrue(hasUnsetKey(node, "Incr"), "should have at least one Unset Incr");
    }

    @Test
    void refDegen1Parse() {
        NativeNode node = NativeReader.parse(Fixtures.read("wcsconv/inputs/degen1.ast"));
        assertEquals("FrameSet", node.className);
        assertEquals(4, node.readLong("Nframe", 0));
        assertEquals(4, node.readLong("Currnt", 0)); // codespell:ignore currnt
        assertNull(node.get("Base"), "Base should be Unset");
        NativeNode.Item base = node.item("Base");
        assertNotNull(base, "Base item missing");
        assertFalse(base.set);
        assertEquals(38, countNodes(node));
        NativeNode frm4 = node.readObject("Frm4");
        assertEquals("CmpFrame", frm4.className);
        NativeNode frameA = frm4.readObject("FrameA");
        assertEquals("SkyFrame", frameA.className);
        assertEquals("FK5", frameA.readString("System", ""));
        assertEquals("SkyAxis", frameA.readObject("Ax1").className);
        Set<String> classes = new TreeSet<>();
        collectClasses(node, classes);
        for (String expected : new String[] {
            "FrameSet", "CmpFrame", "SkyFrame", "Frame", "SkyAxis", "CmpMap", "PermMap", "WcsMap", "SphMap", "UnitMap"
        }) {
            assertTrue(classes.contains(expected), "expected class " + expected + " not found; have: " + classes);
        }
    }

    @Test
    void refBradMapParse() {
        NativeNode node = NativeReader.parse(Fixtures.read("simplify/brad.map"));
        assertEquals("CmpMap", node.className);
        assertEquals(3, node.readLong("Nin", 0));
        assertEquals(0, node.readLong("Series", 0));
        assertEquals(7, countNodes(node));
        assertEquals("CmpMap", node.readObject("MapA").className);
        NativeNode mapB = node.readObject("MapB");
        assertEquals("CmpMap", mapB.className);
        NativeNode zoom = mapB.readObject("MapA");
        assertEquals("ZoomMap", zoom.className);
        assertEquals(5.23598775598299e-06, zoom.readDouble("Zoom", 0.0));
        NativeNode wcsmap = mapB.readObject("MapB");
        assertEquals("WcsMap", wcsmap.className);
        assertEquals("TAN", wcsmap.readString("Type", ""));
        Set<String> classes = new TreeSet<>();
        collectClasses(node, classes);
        for (String expected : new String[] {"CmpMap", "ZoomMap", "WcsMap", "WinMap", "LutMap"}) {
            assertTrue(classes.contains(expected), "expected class " + expected + " not found; have: " + classes);
        }
    }

    @Test
    void refSpecfluxParse() {
        NativeNode node = NativeReader.parse(Fixtures.read("wcsconv/inputs/specflux.ast"));
        assertEquals("CmpFrame", node.className);
        assertEquals("Compound coordinate system description", node.classComment);
        assertEquals(5, countNodes(node));
        NativeNode frameA = node.readObject("FrameA");
        assertEquals("SpecFrame", frameA.className);
        assertEquals(1, frameA.readLong("Naxes", 0));
        assertEquals("WAVE", frameA.readString("System", ""));
        NativeNode specAx = frameA.readObject("Ax1");
        assertEquals("Axis", specAx.className);
        assertEquals("um", specAx.readString("Unit", ""));
        NativeNode frameB = node.readObject("FrameB");
        assertEquals("Frame", frameB.className);
        assertEquals(1, frameB.readLong("Naxes", 0));
        String fluxUnit = frameB.readObject("Ax1").readString("Unit", "");
        assertTrue(fluxUnit.contains("W/m"), "expected flux unit containing 'W/m', got: " + fluxUnit);
        assertFalse(node.item("Title").set, "Title should be Unset");
        assertFalse(node.item("Domain").set, "Domain should be Unset");
    }
}
