package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Malformed native text makes {@link Channel#read} raise an {@link AstException} with the status C
 * raises for that input, never another exception. Each expected status is read from the C code
 * that handles the case, cited beside it.
 */
class MalformedNativeTest {

    /** The first {@code n} lines of {@code text}, each with its newline. */
    private static String head(String text, int n) {
        List<String> lines = NativeReader.lines(text);
        return ChannelText.text(lines.subList(0, n).toArray(new String[0]));
    }

    private static KeyMap keyMap() {
        KeyMap km = new KeyMap();
        km.mapPut0I("a", 1, null);
        km.mapPut0C("b", "x", null);
        return km;
    }

    @Test
    void aKeyMapCutOffAfterItsFirstItem() {
        String full = ChannelText.dump(keyMap());
        String text = head(full, 2);
        assertTrue(text.contains("MapSz"), text);
        AstException e =
                assertThrows(AstException.class, () -> ChannelText.reader(text).read());
        // ReadClassData meets the end of the text while reading the Object class's items:
        // channel.c:2824 raises AST__EOCHN, and Read adds its context with the same status
        // (channel.c:2672).
        assertEquals(AstException.AST__EOCHN, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains("Unexpected end of input (missing end of KeyMap)."), e.getMessage());
        assertTrue(e.getMessage().contains("Error while reading a KeyMap from a Channel."), e.getMessage());
    }

    @Test
    void aKeyMapCutOffWithinANestedKeyMap() {
        KeyMap outer = new KeyMap();
        outer.mapPut0A("inner", keyMap(), null);
        List<String> lines = NativeReader.lines(ChannelText.dump(outer));
        int cut = 0;
        while (!lines.get(cut).contains("Begin KeyMap") || cut == 0) {
            cut++;
        }
        String text = head(ChannelText.dump(outer), cut + 2);
        AstException e =
                assertThrows(AstException.class, () -> ChannelText.reader(text).read());
        // The nested Object's ReadClassData meets the end of the text (channel.c:2824), and the
        // outer one adds that the item's Object could not be read (channel.c:2950), keeping
        // AST__EOCHN.
        assertEquals(AstException.AST__EOCHN, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains("Failed to read the \"val1\" Object value."), e.getMessage());
    }

    @Test
    void anUnknownClass() {
        String text = " Begin NoSuchClass \t# Not an AST class\n    Ident = \"x\"\n End NoSuchClass\n";
        AstException e =
                assertThrows(AstException.class, () -> ChannelText.reader(text).read());
        // Read asks astGetLoader for the class's loader (channel.c:2618), which raises AST__OCLUK
        // for a class it does not know (loader.c:202).
        assertEquals(AstException.AST__OCLUK, e.getStatus(), e.getMessage());
        assertTrue(
                e.getMessage().contains("astGetLoader: Object of unknown class \"NoSuchClass\" cannot be loaded."),
                e.getMessage());
    }

    /** A ZoomMap's description with the given {@code Nin} and {@code Zoom} values, as text. */
    private static String zoomMap(String nin, String zoom) {
        return ChannelText.text(
                " Begin ZoomMap", "    Nin = " + nin, " IsA Mapping", "    Zoom = " + zoom, " End ZoomMap");
    }

    /** The error of reading {@code text}, which must be an AST__BADIN error with C's messages. */
    private static void assertBadIn(String text, String message) {
        AstException e =
                assertThrows(AstException.class, () -> ChannelText.reader(text).read());
        assertEquals(AstException.AST__BADIN, e.getStatus(), e.getMessage());
        assertEquals(message + "\nError while reading a ZoomMap from a Channel.", e.getMsgText());
    }

    private static final String NOT_A_DOUBLE = "\" cannot be read as a double precision floating point number.";

    // The messages below are channel.c ReadDouble's (channel.c:3052-3063) and ReadInt's
    // (channel.c:3170-3173), checked against the pinned C library, followed by Read's context
    // (channel.c:2672).

    @Test
    void aQuotedStringWhereADoubleIsExpected() {
        assertBadIn(zoomMap("1", "\"abc\""), "astRead(Channel): The value \"zoom = abc" + NOT_A_DOUBLE);
    }

    @Test
    void aNumberWithTrailingTextWhereADoubleIsExpected() {
        assertBadIn(zoomMap("1", "2x"), "astRead(Channel): The value \"zoom = 2x" + NOT_A_DOUBLE);
    }

    @Test
    void aNanWhereADoubleIsExpected() {
        assertBadIn(
                zoomMap("1", "nan"),
                "astRead(Channel): Illegal double precision floating point value \"nan\" read for \"zoom\".");
    }

    @Test
    void anInfinityWhereADoubleIsExpected() {
        assertBadIn(
                zoomMap("1", "-inf"),
                "astRead(Channel): Illegal double precision floating point value \"-inf\" read for \"zoom\".");
    }

    @Test
    void aStringWhereAnIntegerIsExpected() {
        assertBadIn(zoomMap("abc", "2"), "astRead(Channel): The value \"nin = abc\" cannot be read as an integer.");
    }

    @Test
    void aFractionWhereAnIntegerIsExpected() {
        // sscanf's %d stops at the point, so the whole value is not read (channel.c:3168-3169).
        assertBadIn(zoomMap("1.5", "2"), "astRead(Channel): The value \"nin = 1.5\" cannot be read as an integer.");
    }

    @Test
    void anIntegerBeyondTheRangeOfAnInt() {
        // C reads it with sscanf's %d, whose result for a number an int cannot hold is undefined
        // (the pinned library on macOS keeps the low 32 bits, giving 1215752191 axes); this port
        // reports it as a value that cannot be read as an integer.
        assertBadIn(
                zoomMap("99999999999", "2"),
                "astRead(Channel): The value \"nin = 99999999999\" cannot be read as an integer.");
    }

    @Test
    void numbersCReadsFromAnyText() throws Exception {
        // ReadDouble scans the text of the value, quoted or not, with sscanf's %lf, which also
        // reads hexadecimal (channel.c:3049-3050); results checked against the pinned C library.
        ZoomMap quoted =
                (ZoomMap) ChannelText.reader(zoomMap("\"2\"", "\"2.5\"")).read();
        assertEquals(2, quoted.getNin());
        assertEquals(2.5, quoted.getZoom());
        ZoomMap hex = (ZoomMap) ChannelText.reader(zoomMap("1", "0x10")).read();
        assertEquals(16.0, hex.getZoom());
    }
}
