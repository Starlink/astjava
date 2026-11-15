package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of Rust {@code tests/test_channel.rs}, the port of {@code testchannel.c}.
 *
 * <p>Rust's {@code to_native_string} is C's {@code astToString}, which jniast does not have; {@code
 * to_native_string_matches_a_channel_set_as_ast_to_string_sets_it} checks instead that jniast's
 * {@code toString}, which writes as a default Channel does, matches a Channel for the same nested
 * Object.
 *
 * <p>{@code default_skyframe_file_round_trip_is_equal} compares the SkyFrame read back with the
 * original, which C does with {@code astEqual}, by comparing their dumps, since a native round
 * trip writes back what it read. {@code converted_frameset_string_round_trip} likewise leaves out
 * C's {@code astEqual} of the FrameSet read back with the original, and checks only that it writes
 * the same text again.
 *
 * <p>The Rust tests that use the real standard input and output run a child copy of the test
 * program; here standard input and output are replaced for the duration of the test.
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

    /** A Frame written with Full -1 leaves out the unset items written with Full 0. */
    @Test
    void configFullMinusOneShorterOutput() throws IOException {
        Frame frame = new Frame(3);
        frame.setDomain("PIXEL");
        frame.setLabel(1, "X");
        frame.setLabel(2, "Y");
        frame.setLabel(3, "Z");
        String normal = ChannelText.dump(frame);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Channel ch = new Channel(null, out);
        ch.setFull(-1);
        ch.write(frame);
        String minimal = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(
                minimal.length() < normal.length(),
                "Full -1 output (" + minimal.length() + " bytes) should be shorter than Full 0 (" + normal.length()
                        + " bytes)");
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
    // Round trips of ZoomMaps and ShiftMaps
    // ------------------------------------------------------------------

    /** The text a Channel writing to a stream, configured by {@code configure}, writes for {@code obj}. */
    private static String channelWrite(AstObject obj, Consumer<Channel> configure) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Channel ch = new Channel(null, out);
        configure.accept(ch);
        ch.write(obj);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Mapping readMapping(String text) throws IOException {
        AstObject obj = ChannelText.reader(text).read();
        return assertInstanceOf(Mapping.class, obj);
    }

    private static double[] transform1(Mapping m, double... point) {
        return MappingTestData.point(MappingTestData.transformRows(m, true, point), 0);
    }

    @Test
    void roundTripZoomViaChannel() throws IOException {
        String text = channelWrite(new ZoomMap(2, 3.5), ch -> {});
        assertFalse(text.isEmpty());
        assertTrue(text.contains("Begin ZoomMap"));
        assertTrue(text.contains("End ZoomMap"));

        Mapping m = readMapping(text);
        assertEquals(2, m.getNin());
        assertEquals(2, m.getNout());
        double[] out = transform1(m, 1.0, 2.0);
        assertEquals(3.5, out[0], 1e-10, "zoom axis 1");
        assertEquals(7.0, out[1], 1e-10, "zoom axis 2");
    }

    @Test
    void roundTripShiftmapViaChannel() throws IOException {
        String text = channelWrite(new ShiftMap(new double[] {10.0, -20.0, 30.0}), ch -> {});
        assertTrue(text.contains("Begin ShiftMap"));

        Mapping m = readMapping(text);
        assertEquals(3, m.getNin());
        double[] out = transform1(m, 1.0, 2.0, 3.0);
        assertEquals(11.0, out[0], 1e-10, "shift axis 1");
        assertEquals(-18.0, out[1], 1e-10, "shift axis 2");
        assertEquals(33.0, out[2], 1e-10, "shift axis 3");
    }

    /** Writes {@code obj} to the file {@code path} and reads it back, through SinkFile and SourceFile. */
    private static AstObject viaFile(AstObject obj, String path) throws IOException {
        Channel out = new Channel(null, null);
        out.setSinkFile(path);
        out.write(obj);
        out.clearSinkFile();

        Channel in = ChannelText.reader("");
        in.setSourceFile(path);
        return in.read();
    }

    @Test
    void roundTripSkyframeViaFile(@TempDir Path dir) throws IOException {
        SkyFrame sky = new SkyFrame();
        sky.setSystem("FK5");
        Frame f = assertInstanceOf(
                Frame.class,
                viaFile(sky, dir.resolve("ast_channel_test_skyframe.ast").toString()));
        assertEquals(2, f.getNaxes());
        assertEquals("FK5", f.getSystem());
    }

    @Test
    void roundTripZoommapViaFile(@TempDir Path dir) throws IOException {
        String path = dir.resolve("ast_channel_test_zoommap.ast").toString();
        Channel out = new Channel(null, null);
        out.setSinkFile(path);
        out.write(new ZoomMap(3, -2.5));
        out.clearSinkFile();

        Channel in = ChannelText.reader("");
        in.setSourceFile(path);
        Mapping m = assertInstanceOf(Mapping.class, in.read());
        assertEquals(3, m.getNin());
        assertEquals(3, m.getNout());
        double[] p = transform1(m, 2.0, 4.0, 6.0);
        assertEquals(-5.0, p[0], 1e-10, "zoom file axis 1");
        assertEquals(-10.0, p[1], 1e-10, "zoom file axis 2");
        assertEquals(-15.0, p[2], 1e-10, "zoom file axis 3");
    }

    /**
     * testchannel.c Errors 1-6: a default SkyFrame written to a file and read back is the same as
     * the original, for two files in turn. C's Error 7, a read while SinkFile is still set, is
     * not part of this test, as it is not of Rust's.
     */
    @Test
    void defaultSkyframeFileRoundTripIsEqual(@TempDir Path dir) throws IOException {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        String want = ChannelText.dump(sf);
        String[] names = {"ast_testchannel_fred.txt", "ast_testchannel_fred2.txt"};
        for (int n = 0; n < names.length; n++) {
            AstObject restored = viaFile(sf, dir.resolve(names[n]).toString());
            assertNotNull(restored, "Error " + (2 + 3 * n));
            assertInstanceOf(SkyFrame.class, restored, "a SkyFrame reads back as a SkyFrame");
            assertEquals(want, ChannelText.dump(restored), "Error " + (3 + 3 * n));
        }
    }

    @Test
    void configCommentFalseSuppressesComments() throws IOException {
        ZoomMap zoom = new ZoomMap(2, 4.0);
        String with = channelWrite(zoom, ch -> {});
        String without = channelWrite(zoom, ch -> ch.setComment(false));
        assertTrue(with.contains("#"), "default output should contain '#' comment markers");
        assertFalse(without.contains("#"), "Comment=0 output should not contain '#'");
        assertEquals(2, readMapping(without).getNin());
    }

    /** The indentation of the first line holding {@code Zoom =}. */
    private static int zoomIndent(String text) {
        for (String l : CValues.lines(text)) {
            if (l.contains("Zoom =")) {
                int i = 0;
                while (i < l.length() && CScan.isCSpace(l.charAt(i))) {
                    i++;
                }
                return i;
            }
        }
        return 0;
    }

    @Test
    void configIndent6WiderIndentation() throws IOException {
        ZoomMap zoom = new ZoomMap(1, 2.0);
        String dflt = channelWrite(zoom, ch -> {});
        String wide = channelWrite(zoom, ch -> ch.setIndent(6));
        assertTrue(
                zoomIndent(wide) > zoomIndent(dflt),
                "Indent=6 should give wider indentation (" + zoomIndent(wide) + " vs " + zoomIndent(dflt) + ")");
        assertEquals(1, readMapping(wide).getNin());
    }

    @Test
    void roundTripMultipleTypes() throws IOException {
        Mapping zoom = readMapping(ChannelText.dump(new ZoomMap(2, 5.0)));
        assertEquals(2, zoom.getNin());

        Mapping shift = readMapping(ChannelText.dump(new ShiftMap(new double[] {1.0, 2.0})));
        assertEquals(2, shift.getNin());

        SkyFrame sky = new SkyFrame();
        sky.setSystem("GALACTIC");
        Frame f = assertInstanceOf(Frame.class, ChannelText.read(ChannelText.dump(sky)));
        assertEquals(2, f.getNaxes());
        assertEquals("GALACTIC", f.getSystem());

        Frame base = new Frame(2);
        base.setDomain("PIXEL");
        FrameSet fs = new FrameSet(base);
        Frame current = new Frame(2);
        current.setDomain("SKY");
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), current);
        String text = ChannelText.dump(fs);
        assertTrue(text.contains("Begin FrameSet"));
        Frame restored = assertInstanceOf(Frame.class, ChannelText.read(text));
        assertEquals(2, restored.getNaxes());
    }

    /**
     * A CmpMap of a ZoomMap with an ID and a ShiftMap: nested objects and a set Object attribute,
     * which the text must keep.
     */
    private static CmpMap nestedObject() {
        ZoomMap zoom = new ZoomMap(2, 4.0);
        zoom.setID("zoom_id");
        return new CmpMap(zoom, new ShiftMap(new double[] {100.0, -200.0}), true);
    }

    @Test
    void toNativeStringMatchesAChannelSetAsAstToStringSetsIt() throws IOException {
        CmpMap obj = nestedObject();
        String want = channelWrite(obj, ch -> {});
        assertTrue(want.contains("zoom_id"), want);
        assertEquals(want, obj.toString());
    }

    @Test
    void channelFromStringMatchesConvenienceFunction() throws IOException {
        String text = channelWrite(new ZoomMap(2, 7.7), ch -> {});
        Mapping m1 = readMapping(text);
        Mapping m2 = (Mapping) ChannelText.read(text);
        assertEquals(m1.getNin(), m2.getNin());
        assertEquals(m1.getNout(), m2.getNout());
        double[] out1 = transform1(m1, 3.0, 4.0);
        double[] out2 = transform1(m2, 3.0, 4.0);
        assertEquals(out2[0], out1[0], 1e-15, "axis 1");
        assertEquals(out2[1], out1[1], 1e-15, "axis 2");
    }

    @Test
    void writeMultipleObjectsReadBack() throws IOException {
        String text1 = channelWrite(new ZoomMap(1, 9.0), ch -> {});
        String text2 = channelWrite(new ShiftMap(new double[] {5.0}), ch -> {});

        Mapping m1 = readMapping(text1);
        assertEquals(1, m1.getNin());
        assertEquals(18.0, transform1(m1, 2.0)[0], 1e-10, "zoom 9*2");

        Mapping m2 = readMapping(text2);
        assertEquals(1, m2.getNin());
        assertEquals(7.0, transform1(m2, 2.0)[0], 1e-10, "shift 2+5");
    }

    @Test
    void writeAndReadFramesetViaChannel() throws IOException {
        // PIXEL to GRID by a zoom of 2.
        Frame pixel = new Frame(2);
        pixel.setDomain("PIXEL");
        FrameSet fs = new FrameSet(pixel);
        Frame grid = new Frame(2);
        grid.setDomain("GRID");
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), grid);

        String text = channelWrite(fs, ch -> {});
        assertTrue(text.contains("Begin FrameSet"));
        assertTrue(text.contains("PIXEL"));

        // A FrameSet is both a Frame and a Mapping.
        Mapping m = readMapping(text);
        assertEquals(2, m.getNin());
        assertEquals(2, m.getNout());
        double[] out = transform1(m, 3.0, 4.0);
        assertEquals(6.0, out[0], 1e-10, "fs zoom axis 1");
        assertEquals(8.0, out[1], 1e-10, "fs zoom axis 2");
    }

    /**
     * testobject.c {@code TestCheckToString}: the FrameSet {@code astConvert} makes from a
     * SKY-domain Frame to a SkyFrame writes to a string, reads back, and writes the same string
     * again.
     */
    @Test
    void convertedFramesetStringRoundTrip() {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        Frame bf = new Frame(2);
        bf.setDomain("SKY");
        FrameSet fs = bf.convert(sf, "");
        assertNotNull(fs, "astConvert failed");
        String pickle1 = ChannelText.dump(fs);
        FrameSet fs2 = assertInstanceOf(FrameSet.class, ChannelText.read(pickle1));
        assertEquals(pickle1, ChannelText.dump(fs2), "round-tripped strings differ");
    }

    // ------------------------------------------------------------------
    // SourceFile and SinkFile
    // ------------------------------------------------------------------

    /** The native dump of a ZoomMap with {@code nin} inputs. */
    private static String zoomDump(int nin) {
        return ChannelText.dump(new ZoomMap(nin, 2.0));
    }

    private static int ninOf(AstObject obj) {
        return assertInstanceOf(Mapping.class, obj).getNin();
    }

    private static String fileText(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * SourceFile is read in place of the source; clearing it restores the source, and setting it
     * again reads the file from its start (channel.c {@code SetSourceFile} and {@code
     * ClearSourceFile} close the open file, and {@code ReadData} opens it again on the next read).
     */
    @Test
    void sourceFileReplacesSourceAndClearRestoresIt(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("source.ast");
        Files.write(path, (zoomDump(3) + zoomDump(4)).getBytes(StandardCharsets.UTF_8));
        String name = path.toString();

        Channel ch = ChannelText.reader(zoomDump(2));
        assertFalse(ch.test("SourceFile"));
        assertNull(ch.getSourceFile());

        ch.setSourceFile(name);
        assertTrue(ch.test("SourceFile"));
        assertEquals(name, ch.getSourceFile());
        assertEquals(3, ninOf(ch.read()));

        // The file is closed and the next read goes to the source, which no read has touched.
        ch.clearSourceFile();
        assertFalse(ch.test("SourceFile"));
        assertEquals(2, ninOf(ch.read()));
        assertNull(ch.read());

        // A new open starts at the beginning of the file, not where the closed one stopped.
        ch.setSourceFile(name);
        assertEquals(3, ninOf(ch.read()));
        // Setting the same name again closes the file and so starts again.
        ch.setSourceFile(name);
        assertEquals(3, ninOf(ch.read()));
        assertEquals(4, ninOf(ch.read()));
        assertNull(ch.read());
    }

    /** SinkFile is written in place of the sink; clearing it restores the sink, and setting it again creates the file anew. */
    @Test
    void sinkFileReplacesSinkAndClearRestoresIt(@TempDir Path dir) throws IOException {
        Path first = dir.resolve("sink1.ast");
        Path second = dir.resolve("sink2.ast");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        Channel ch = new Channel(null, sink);
        assertFalse(ch.test("SinkFile"));
        ch.setSinkFile(first.toString());
        assertTrue(ch.test("SinkFile"));
        assertEquals(first.toString(), ch.getSinkFile());
        ch.write(new ZoomMap(2, 2.0));
        ch.write(new ZoomMap(3, 2.0));

        // A different name closes the first file and writes to the second.
        ch.setSinkFile(second.toString());
        ch.write(new ZoomMap(4, 2.0));

        // Clearing returns to the sink.
        ch.clearSinkFile();
        assertNull(ch.getSinkFile());
        ch.write(new ZoomMap(5, 2.0));

        // The first name again replaces the first file's contents.
        ch.setSinkFile(first.toString());
        ch.write(new ZoomMap(6, 2.0));
        ch.clearSinkFile();

        assertEquals(zoomDump(6), fileText(first), "the first file was written by a handle that was not reopened");
        assertEquals(zoomDump(4), fileText(second));
        assertEquals(zoomDump(5), new String(sink.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * A SourceFile that cannot be opened is an error at the first read, a SinkFile at the first
     * write, and a SourceFile the SinkFile has open is refused.
     */
    @Test
    void sourceAndSinkFileOpenFailures(@TempDir Path dir) throws IOException {
        String missing = dir.resolve("no_such_dir").resolve("x.ast").toString();
        Channel reader = ChannelText.reader(zoomDump(2));
        reader.setSourceFile(missing);
        AstException e = assertThrows(AstException.class, reader::read);
        assertEquals(AstException.AST__RDERR, e.getStatus());
        assertTrue(e.getMessage().contains("astRead(Channel): Failed to open input SourceFile"), e.getMessage());

        Channel writer = new Channel(null, new ByteArrayOutputStream());
        writer.setSinkFile(missing);
        e = assertThrows(AstException.class, () -> writer.write(new ZoomMap(2, 2.0)));
        assertEquals(AstException.AST__WRERR, e.getStatus());
        assertTrue(e.getMessage().contains("astWrite(Channel): Failed to open output SinkFile"), e.getMessage());

        String name = dir.resolve("both.ast").toString();
        Channel both = new Channel(null, null);
        both.setSinkFile(name);
        both.write(new ZoomMap(2, 2.0));
        both.setSourceFile(name);
        e = assertThrows(AstException.class, both::read);
        assertEquals(AstException.AST__RDERR, e.getStatus());
        assertTrue(e.getMessage().contains("being used as the output SinkFile"), e.getMessage());
        both.clearSinkFile();
    }

    // ------------------------------------------------------------------
    // Standard input and output
    // ------------------------------------------------------------------

    /** A Channel with no source reads standard input (channel.c {@code ReadData}). */
    @Test
    // The saved standard input is restored, not closed.
    @SuppressWarnings("PMD.CloseResource")
    void channelWithoutSourceReadsStandardInput() throws IOException {
        InputStream old = System.in;
        System.setIn(new ByteArrayInputStream((zoomDump(3) + zoomDump(4)).getBytes(StandardCharsets.UTF_8)));
        try {
            Channel ch = new Channel(null, null);
            assertEquals(3, ninOf(ch.read()));
            assertEquals(4, ninOf(ch.read()));
            assertNull(ch.read());
        } finally {
            System.setIn(old);
        }
    }

    /**
     * A Channel with no sink writes standard output, and clearing the SinkFile returns to it
     * (channel.c {@code WriteData}).
     */
    @Test
    // Java 8 has no PrintStream constructor taking a Charset; the saved standard output is
    // restored, not closed.
    @SuppressWarnings({"JdkObsolete", "PMD.CloseResource"})
    void channelWithoutSinkWritesStandardOutput(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("stdout_sink.ast");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.out;
        // MODERN-JAVA(10): new PrintStream(captured, true, StandardCharsets.UTF_8)
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
        try {
            Channel ch = new Channel(null, null);
            ch.setSinkFile(path.toString());
            ch.write(new ZoomMap(3, 2.0));
            ch.clearSinkFile();
            ch.write(new ZoomMap(4, 2.0));
        } finally {
            System.setOut(old);
        }
        assertEquals(zoomDump(3), fileText(path));
        String out = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(out.contains(zoomDump(4)), out);
        assertFalse(out.contains(zoomDump(3)), out);
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
