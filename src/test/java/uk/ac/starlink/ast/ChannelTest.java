package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Translation of Rust {@code tests/test_channel.rs}, the port of {@code testchannel.c}.
 *
 * <p>Most of its tests write and read ZoomMaps, ShiftMaps, CmpMaps, Frames, SkyFrames and
 * FrameSets, and are translated in the tasks that port them: {@code round_trip_zoom_via_channel},
 * {@code round_trip_shiftmap_via_channel}, {@code round_trip_zoommap_via_file}, {@code
 * config_comment_false_suppresses_comments}, {@code config_indent_6_wider_indentation}, {@code
 * channel_from_string_matches_convenience_function}, {@code write_multiple_objects_read_back},
 * {@code source_file_replaces_source_and_clear_restores_it}, {@code
 * sink_file_replaces_sink_and_clear_restores_it}, {@code source_and_sink_file_open_failures},
 * {@code channel_without_source_reads_standard_input} and {@code
 * channel_without_sink_writes_standard_output} in Task 6 (ZoomMap, ShiftMap); {@code
 * to_native_string_matches_a_channel_set_as_ast_to_string_sets_it} in Task 7 (CmpMap); {@code
 * config_full_minus_one_shorter_output} in Task 15 (Frame); {@code
 * round_trip_skyframe_via_file} and {@code default_skyframe_file_round_trip_is_equal} in Task 18
 * (SkyFrame); {@code round_trip_multiple_types}, {@code write_and_read_frameset_via_channel} and
 * {@code converted_frameset_string_round_trip} in Task 20 (FrameSet).
 *
 * <p>The tests after those translated check, with KeyMaps, the reading behaviour of channel.c that
 * those later tests cover with other classes.
 */
class ChannelTest {

    @Test
    void readEmptyStringReturnsNone() throws IOException {
        assertNull(ChannelText.reader("").read(), "reading from empty string should return None");
    }

    @Test
    void readWhitespaceOnlyReturnsErrorOrNone() {
        // A reader may give no Object, or reject the text.
        AstObject obj;
        try {
            obj = ChannelText.reader("   \n\n  \n").read();
        } catch (AstException | IOException e) {
            obj = null;
        }
        assertNull(obj, "whitespace-only input should not produce an object");
    }

    @Test
    void configDefaultsAreSensible() {
        Channel ch = new Channel(null, null);
        assertTrue(ch.getComment());
        assertEquals(0, ch.getFull());
        assertEquals(3, ch.getIndent());
        assertFalse(ch.getSkip());
        assertFalse(ch.getStrict());
        assertEquals(1, ch.getReportLevel());
        assertFalse(ch.test("Comment") || ch.test("Full") || ch.test("Indent"));
    }

    @Test
    void configMutIsHonoured() {
        Channel ch = new Channel(null, null);
        ch.setComment(false);
        ch.setFull(-4);
        ch.setIndent(5);
        assertFalse(ch.getComment());
        assertEquals(-1, ch.getFull());
        assertEquals(5, ch.getIndent());
        assertTrue(ch.test("Full"));
        ch.clear("Full");
        assertEquals(0, ch.getFull());
    }

    @Test
    void warningsInitiallyEmpty() {
        Channel ch = new Channel(null, null);
        assertTrue(ch.warningTexts().isEmpty());
        // C's astWarnings returns NULL when the Channel issued no warnings (channel.c:1696).
        assertNull(ch.warnings());
    }

    @Test
    void cloneDoesNotInheritSourceFileOrSinkFile() {
        Channel ch = new Channel(null, null);
        ch.set("SourceFile=a.ast");
        ch.set("SinkFile=b.ast");
        Channel copy = (Channel) ch.copy();
        assertFalse(copy.test("SourceFile"));
        assertFalse(copy.test("SinkFile"));
        assertTrue(ch.test("SourceFile") && ch.test("SinkFile"));
    }

    // ------------------------------------------------------------------
    // Reading behaviour of channel.c, checked with KeyMaps
    // ------------------------------------------------------------------

    private static KeyMap twoKeys() {
        KeyMap km = new KeyMap();
        km.mapPut0I("a", 1, null);
        km.mapPut0C("b", "x", null);
        return km;
    }

    @Test
    void successiveReadsTakeSuccessiveObjects() throws IOException {
        KeyMap km = twoKeys();
        String one = ChannelText.dump(km);
        Channel ch = ChannelText.reader(one + one);
        assertEquals(km, ch.read());
        assertEquals(km, ch.read());
        assertNull(ch.read());
    }

    @Test
    void anUnrecognisedItemIsAWarning() throws IOException {
        // channel.c:805-811 (ClearValues) reports each item no loader read through astAddWarning,
        // which stores it as a warning unless Strict is set (channel.c:483-492).
        String text = ChannelText.dump(twoKeys()).replace(" End KeyMap", "    Bogus = 3\n End KeyMap");
        Channel ch = ChannelText.reader(text);
        AstObject obj = ch.read();
        assertEquals(twoKeys(), obj);
        KeyMap w = ch.warnings();
        assertNotNull(w);
        assertEquals(1, w.mapSize());
        assertEquals("The value \"bogus = 3\" was not recognised as valid input.", w.mapGet0C("Warning_1"));
    }

    @Test
    void anUnrecognisedItemIsAnErrorWhenStrict() {
        // channel.c:486: with Strict set, astAddWarning reports AST__BADIN instead.
        String text = ChannelText.dump(twoKeys()).replace(" End KeyMap", "    Bogus = 3\n End KeyMap");
        Channel ch = ChannelText.reader(text);
        ch.setStrict(true);
        AstException e = assertThrows(AstException.class, ch::read);
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertTrue(
                e.getMessage().contains("astRead(Channel): The value \"bogus = 3\" was not recognised"),
                e.getMessage());
    }

    @Test
    void skipPassesOverDataBetweenObjects() throws IOException {
        String text = "some junk\n" + ChannelText.dump(twoKeys());
        AstException e =
                assertThrows(AstException.class, () -> ChannelText.reader(text).read());
        // channel.c:1290: without Skip, a line that is no data item is an error.
        assertEquals(AstException.AST__BADIN, e.getStatus());
        Channel ch = ChannelText.reader(text);
        ch.setSkip(true);
        assertEquals(twoKeys(), ch.read());
    }

    @Test
    void sourceFileAndSinkFileHoldTheText(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("keymap.ast");
        Channel out = new Channel(null, null);
        out.set("SinkFile=" + file);
        out.write(twoKeys());
        out.clear("SinkFile");
        assertEquals(ChannelText.dump(twoKeys()), new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

        Channel in = ChannelText.reader("");
        in.set("SourceFile=" + file);
        assertEquals(twoKeys(), in.read());
        assertNull(in.read());
        in.clear("SourceFile");
        assertNull(in.read());
    }

    @Test
    void aChannelDumpReadsBackAsAChannel() {
        Channel ch = new Channel(null, null);
        ch.setFull(1);
        ch.setStrict(true);
        ch.setID("ch");
        AstObject back = ChannelText.read(ChannelText.dump(ch));
        Channel c = assertInstanceOf(Channel.class, back);
        assertEquals(1, c.getFull());
        assertTrue(c.getStrict());
        assertEquals("ch", c.getID());
        assertFalse(c.test("Comment"));
    }
}
