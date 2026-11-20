package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.NativeClasses;

/**
 * For Rust {@code tests/test_frame_in_map_slot.rs}: a Frame where a Mapping is expected. C
 * accepts a Frame in any Mapping slot, because frame.c {@code Transform} transforms through
 * {@code astUnitMap( astGetNaxes( this ) )}; this version follows C, where the Rust port refuses
 * the construct.
 *
 * <p>{@code aFrameInAMappingSlotTransformsAsAUnitMap} has no Rust counterpart: it builds the
 * FrameSet of {@code tools/oracle_fixtures/frame_in_map_slot_probe.c} with a plain Frame in place
 * of its CmpFrame, and transforms through it before and after a dump and reload. {@code
 * frameInAMapSlotIsRejected} reads the probe's dump, whose Mapping slot holds a CmpFrame, and
 * asserts C's acceptance: the dump is read, and the FrameSet transforms as the probe reports C's
 * does, (1, 3) to (2, 6). {@code aRegionInsideACmpMapStillReads} and {@code
 * aRegionInsideACmpFrameStillReads} are the Rust test's two controls.
 */
class FrameInMapSlotTest {

    /** The Region classes whose blocks {@link #regionNestedIn} looks for, as the Rust test lists them. */
    private static final List<String> REGION_CLASSES = Arrays.asList(
            "Box", "Circle", "Ellipse", "Interval", "NullRegion", "PointList", "Polygon", "Prism", "CmpRegion");

    /**
     * Whether a native dump nests a Region block inside a block of class {@code outer}, by walking
     * the block structure: every CmpFrame of the simplify corpus is a Region's own Frame, the
     * opposite nesting.
     */
    private static boolean regionNestedIn(String text, String outer) {
        Deque<String> stack = new ArrayDeque<>();
        for (String line : text.split("\n", -1)) {
            String s = line.trim();
            if (s.startsWith("Begin ")) {
                String[] words = s.substring("Begin ".length()).trim().split("\\s+", -1);
                String cls = words[0];
                if (REGION_CLASSES.contains(cls) && stack.contains(outer)) {
                    return true;
                }
                stack.push(cls);
            } else if (s.startsWith("End ")) {
                stack.poll();
            }
        }
        return false;
    }

    private static void checkTransform(FrameSet fs) {
        double[][] out = fs.tran2(2, new double[] {1.0, 2.0}, new double[] {3.0, 4.0}, true);
        assertArrayEquals(new double[] {2.0, 4.0}, out[0]);
        assertArrayEquals(new double[] {6.0, 8.0}, out[1]);
    }

    @Test
    void aFrameInAMappingSlotTransformsAsAUnitMap() {
        Frame pix = new Frame(2);
        pix.set("Domain=GRID,Title=pixel grid");
        FrameSet fs = new FrameSet(pix);
        Frame mid = new Frame(2);
        mid.setDomain("MID");
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), mid);
        Frame top = new Frame(2);
        top.setDomain("TOP");
        fs.addFrame(2, new Frame(2), top);
        assertEquals(3, fs.getNframe());
        checkTransform(fs);

        FrameSet back = assertInstanceOf(FrameSet.class, ChannelText.read(ChannelText.dump(fs)));
        assertInstanceOf(Frame.class, back.getMapping(2, 3));
        checkTransform(back);
    }

    @Test
    void frameInAMapSlotIsRejected() {
        String text = Fixtures.read("serialisation/frame_in_map_slot.ast");
        FrameSet fs = assertInstanceOf(FrameSet.class, ChannelText.read(text));
        assertEquals(3, fs.getNframe());
        CmpFrame slot = assertInstanceOf(CmpFrame.class, fs.getMapping(2, 3));
        assertEquals(2, slot.getNaxes());
        checkTransform(fs);

        FrameSet back = assertInstanceOf(FrameSet.class, ChannelText.read(ChannelText.dump(fs)));
        checkTransform(back);
    }

    /**
     * A Region is a Mapping, so a CmpMap may hold one: every {@code simplify/*.map} fixture nesting
     * a Region inside a CmpMap reads, 6 of them holding SelectorMaps. The corpus has 34 such
     * fixtures; at least 34 must read: like the Rust test's count of 33, written when the corpus
     * had 33, this is a floor, so that a fixture added to the corpus does not fail the test.
     */
    @Test
    void aRegionInsideACmpMapStillReads() throws IOException {
        int read = 0;
        try (DirectoryStream<Path> dir =
                Files.newDirectoryStream(Fixtures.root().resolve("simplify"), "*.map")) {
            for (Path path : dir) {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                if (!regionNestedIn(text, "CmpMap")) {
                    continue;
                }
                assertNull(NativeClasses.needs(text), path.getFileName().toString());
                assertNotNull(ChannelText.read(text), path.getFileName() + " no longer reads");
                read++;
            }
        }
        assertTrue(read >= 34, "only " + read + " fixtures nest a Region inside a CmpMap and read");
    }

    /**
     * A Region is a Frame, so a CmpFrame may hold one, and C's own dump of that arrangement reads
     * here ({@code serialisation/region_in_cmpframe.ast}).
     */
    @Test
    void aRegionInsideACmpFrameStillReads() {
        String text = Fixtures.read("serialisation/region_in_cmpframe.ast");
        CmpFrame cf = assertInstanceOf(CmpFrame.class, ChannelText.read(text));
        assertEquals(2, cf.getNaxes());
        assertInstanceOf(Box.class, cf.decompose(null, null)[0]);
        CmpFrame back = assertInstanceOf(CmpFrame.class, ChannelText.read(ChannelText.dump(cf)));
        assertInstanceOf(Box.class, back.decompose(null, null)[0]);
    }
}
