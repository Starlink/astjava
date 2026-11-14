package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_simplify_seams.rs}: the FrameSet's own simplification,
 * against C's dump. Rust's scan of its own source files for the classes that implement the
 * simplify seam has no Java counterpart.
 */
class SimplifySeamsTest {

    /**
     * A FrameSet whose only Mapping is a UnitMap comes back as itself (Rust {@code
     * frameset_with_no_reducible_edge_returns_the_original}). C's {@code Simplify} (frameset.c)
     * simplifies the Mappings of a deep copy and returns {@code astClone( simpler ? new : this )};
     * nothing is simpler here, so the FrameSet itself is returned and the copy, whose UnitMap the
     * simplification marked {@code IsSimp}, is discarded. Returning the copy would add {@code IsSimp
     * = 1} to the UnitMap in the dump. A FrameSet never carries the record itself, since a Frame's
     * IsSimple is always false (frame.c {@code GetIsSimple}).
     */
    @Test
    void capFramesetNochange() {
        AstObject obj = ChannelText.read(Fixtures.read("simplify/cap_frameset_nochange.map"));
        FrameSet fs = assertInstanceOf(FrameSet.class, obj, "cap_frameset_nochange.map holds a FrameSet");
        Mapping simplified = fs.simplify();
        assertSame(fs, simplified, "C returns the FrameSet itself");
        assertEquals(Fixtures.read("simplify/cap_frameset_nochange.simp"), ChannelText.dump(simplified));
    }
}
