package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_find_frame.rs}: {@code findFrame} with plain Frames and
 * FrameSets as targets, the domain list, and the Domain of the template (frame.c {@code
 * FindFrame} and {@code Match}, frameset.c {@code FindFrame}). Rust's {@code
 * FrameSet::find_frame(&template, &[])} is {@code findFrame(template, " ")}, and with {@code
 * &["X"]} it is {@code findFrame(template, "X")}.
 *
 * <p>The tests whose Frames include a SpecFrame are deferred.
 *
 * <p>{@code matchEndAndPreserveAxesChooseTheAxes} has no Rust counterpart: it checks the axes
 * frame.c {@code Match} associates under the MatchEnd and PreserveAxes attributes.
 *
 * <p>The tests of the template subclass retry (frame.c {@code astMatch_}) and of the plain Frame
 * a cast gives (object.c {@code Cast} and {@code astCastCopy}, frame.c {@code CleanAttribs}) have
 * no Rust counterpart; they use a subclass of Frame defined here, whose own Match finds nothing.
 */
class FindFrameTest {

    private static Frame named(int naxes, String domain) {
        Frame f = new Frame(naxes);
        f.setDomain(domain);
        return f;
    }

    @Test
    void findFrameBasicSelf() {
        Frame target = new Frame(2);
        Frame template = new Frame(2);
        FrameSet result = target.findFrame(template, "");
        assertNotNull(result, "self-match should succeed");
        assertEquals(2, result.getNframe());
        assertEquals(2, result.getFrame(FrameSet.AST__BASE).getNaxes());
        assertEquals(2, result.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void findFrameBasicDomainPriority() {
        Frame target = new Frame(2);
        Frame template = named(2, "PIXEL");
        assertNull(target.findFrame(template, ""));
    }

    @Test
    void findFrameBasicDomainListBlank() {
        Frame target = new Frame(2);
        Frame template = new Frame(2);
        assertNotNull(target.findFrame(template, ""));
    }

    @Test
    void findFrameBasicDomainListMatch() {
        Frame target = named(2, "PIXEL");
        Frame template = new Frame(2);
        assertNotNull(target.findFrame(template, "PIXEL,SKY"));
    }

    @Test
    void findFrameBasicDomainListMiss() {
        Frame target = named(2, "PIXEL");
        Frame template = new Frame(2);
        assertNull(target.findFrame(template, "GRID,SKY"));
    }

    // frame.c Match: the result has the template's number of axes unless PreserveAxes is set,
    // when it has the target's; result axis i is target axis i, counted from the last axis of
    // each when MatchEnd is set.
    @Test
    void matchEndAndPreserveAxesChooseTheAxes() {
        Frame target = new Frame(3);
        target.set("Label(1)=A,Label(2)=B,Label(3)=C");
        Frame template = new Frame(1);
        template.setMaxAxes(3);

        FrameSet found = target.findFrame(template, "");
        assertNotNull(found);
        assertEquals(1, found.getNaxes());
        assertEquals("A", found.getLabel(1));
        double[][] in = {{1.0}, {2.0}, {3.0}};
        assertEquals(1.0, found.tranP(1, 3, in, true, 1)[0][0]);

        template.setMatchEnd(true);
        found = target.findFrame(template, "");
        assertNotNull(found);
        assertEquals("C", found.getLabel(1));
        assertEquals(3.0, found.tranP(1, 3, in, true, 1)[0][0]);

        template.setPreserveAxes(true);
        template.setLabel(1, "T");
        found = target.findFrame(template, "");
        assertNotNull(found);
        assertEquals(3, found.getNaxes());
        assertEquals("A", found.getLabel(1));
        assertEquals("B", found.getLabel(2));
        assertEquals("T", found.getLabel(3), "the template's axis goes with the last result axis");
    }

    // ---- CmpFrame targets ----

    /** A CmpFrame template with its Domain set matches only a target of that Domain. */
    @Test
    void cmpframeOverlayTemplateCopiesDomain() {
        CmpFrame template = new CmpFrame(new Frame(2), new Frame(1));
        template.setDomain("MYCMP");

        CmpFrame target = new CmpFrame(new Frame(2), new Frame(1));
        target.setDomain("MYCMP");
        FrameSet result = target.findFrame(template, "");
        assertNotNull(result, "the Domains match");
        assertEquals(2, result.getNframe());
        assertEquals(3, result.getFrame(FrameSet.AST__CURRENT).getNaxes());

        CmpFrame otherTarget = new CmpFrame(new Frame(2), new Frame(1));
        otherTarget.setDomain("OTHER");
        assertNull(otherTarget.findFrame(template, ""));
    }

    /** Selecting every axis of a CmpFrame target gives a 3-in, 3-out Mapping to a 3-axis Frame. */
    @Test
    void cmpframeSubFrameIdentity() {
        CmpFrame target = new CmpFrame(new Frame(2), new Frame(1));
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        assertTrue(target.subFrame(null, 3, new int[] {0, 1, 2}, new int[] {0, 1, 2}, map, result));
        assertEquals(3, map[0].getNin());
        assertEquals(3, map[0].getNout());
        assertEquals(3, result[0].getNaxes());
    }

    // ---- FrameSet targets ----

    @Test
    void framesetFindFrameSelfReturnsCurrent() {
        FrameSet fs = new FrameSet(new Frame(2));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), new Frame(2));
        FrameSet result = fs.findFrame(new Frame(2), " ");
        assertNotNull(result, "self-match should succeed");
        assertEquals(2, result.getNframe());
        assertEquals(2, result.getFrame(FrameSet.AST__BASE).getNaxes());
        assertEquals(2, result.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    private static FrameSet baseAndCurrent() {
        FrameSet fs = new FrameSet(named(2, "BASE"));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), named(2, "CURRENT"));
        return fs;
    }

    @Test
    void framesetFindFramePriorityOrder() {
        FrameSet result = baseAndCurrent().findFrame(new Frame(2), " ");
        assertNotNull(result);
        assertEquals("CURRENT", result.getFrame(FrameSet.AST__CURRENT).getDomain());
    }

    @Test
    void framesetFindFrameDomainFilter() {
        FrameSet result = baseAndCurrent().findFrame(new Frame(2), "BASE");
        assertNotNull(result);
        assertEquals("BASE", result.getFrame(FrameSet.AST__CURRENT).getDomain());
    }

    @Test
    void framesetFindFrameNoMatchReturnsNone() {
        assertNull(baseAndCurrent().findFrame(new Frame(2), "NOSUCH"));
    }

    // ---- The subclass retry of astMatch, and casting ----

    /**
     * A Frame subclass whose own Match finds nothing, as a SkyFrame's finds no plain Frame, and
     * whose default Domain is SKY.
     */
    private static final class Unmatched extends Frame {
        Unmatched(int naxes) {
            super(naxes);
        }

        @Override
        public String getDomain() {
            return testDomain() ? super.getDomain() : "SKY";
        }

        @Override
        FrameFinder.MatchResult match(Frame target, boolean matchSub) {
            return null;
        }
    }

    // frame.c astMatch_ (15586): a template that cannot probe the target is cast into the
    // target's class and tried again, but only when matchsub is set; the cast takes the
    // template's Domain when the target has one set.
    @Test
    void aSubclassTemplateIsCastWhenMatchsubIsSet() {
        Unmatched template = new Unmatched(2);
        template.setDomain("SKY");
        Frame target = named(2, "SKY");
        assertNull(FrameFinder.match(template, target, false));
        FrameFinder.MatchResult m = FrameFinder.match(template, target, true);
        assertNotNull(m);
        assertEquals("SKY", m.result.getDomain());
        assertNull(target.findFrame(template, ""), "astFindFrame does not cast");
        assertNotNull(target.convert(template, ""), "astConvert casts");

        // The cast, a plain Frame with no Domain set, is given the template's default Domain.
        Unmatched unset = new Unmatched(2);
        assertNotNull(FrameFinder.match(unset, named(2, "SKY"), true));
        assertNull(FrameFinder.match(unset, named(2, "OTHER"), true));
        assertNotNull(FrameFinder.match(unset, new Frame(2), true), "only a target with a Domain set");
    }

    // object.c Cast: a same-class cast is a copy; a subclass is cast into its ancestor's class
    // (astCastCopy), keeping the Object, Mapping and Frame attributes except the ID; a class
    // that is not an ancestor cannot be cast into.
    @Test
    void castGivesAPlainFrameOfTheAncestralPart() {
        Unmatched sub = new Unmatched(2);
        sub.permAxes(new int[] {2, 1});
        sub.setTitle("T");
        sub.setLabel(2, "L2");
        sub.setIdent("I");
        sub.setID("D");
        Frame plain = sub.cast(new Frame(1));
        assertNotNull(plain);
        assertSame(Frame.class, plain.getClass());
        assertEquals("T", plain.getTitle());
        assertEquals("L2", plain.getLabel(2));
        assertEquals("Axis 2", plain.getLabel(1), "the permutation is copied");
        assertEquals("I", plain.getIdent());
        assertFalse(plain.test("ID"));
        assertEquals(2, plain.getNaxes());
        plain.setLabel(2, "changed");
        assertEquals("L2", sub.getLabel(2), "the Axis objects are copied");

        Frame f = named(1, "A");
        Frame copy = f.cast(new Frame(3));
        assertInstanceOf(Frame.class, copy);
        assertEquals("A", copy.getDomain());
        assertNull(f.cast(new Unmatched(1)), "a Frame is not a subclass of its subclass");
    }

    // A FrameSet casts its current Frame into a Frame, and is copied when cast into a FrameSet
    // (frameset.c Cast).
    @Test
    void aFramesetCastsItsCurrentFrame() {
        FrameSet fs = new FrameSet(named(1, "A"));
        fs.addFrame(1, new ZoomMap(1, 2.0), named(1, "B"));
        Frame f = fs.cast(new Frame(1));
        assertSame(Frame.class, f.getClass());
        assertEquals("B", f.getDomain());
        FrameSet back = assertInstanceOf(FrameSet.class, fs.cast(new FrameSet(new Frame(1))));
        assertEquals(2, back.getNframe());
    }
}
