package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

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
 * does, (1, 3) to (2, 6). The two controls, which read Regions, are deferred.
 */
class FrameInMapSlotTest {

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
}
