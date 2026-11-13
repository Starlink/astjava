package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Parts of cmpframe.c's matching that no Rust test reaches without a SpecFrame: the Permute check
 * of {@code Match} and the component-by-component branch of {@code Overlay}. The expected results
 * are read from the C code cited at each test.
 */
class CmpFrameMatchingTest {

    /**
     * cmpframe.c {@code Match}: a division of the target's axes that matches both components but
     * reorders the axes is rejected when the template's Permute is false, and neither component
     * matches the whole three-axis target alone ({@code ComponentMatch}); with Permute true (the
     * default) the reordered match is taken, its result in the template's axis order.
     */
    @Test
    void permuteFalseRejectsAReorderedDivision() {
        CmpFrame target = new CmpFrame(new Frame(1), new SkyFrame());
        CmpFrame template = new CmpFrame(new SkyFrame(), new Frame(1));
        template.setPermute(false);
        assertNull(target.findFrame(template, ""));

        template.clear("Permute");
        FrameSet fs = target.findFrame(template, "");
        assertNotNull(fs);
        double[][] out = fs.tranP(1, 3, new double[][] {{5.0}, {1.0}, {0.5}}, true, 3);
        assertArrayEquals(new double[] {1.0}, out[0]);
        assertArrayEquals(new double[] {0.5}, out[1]);
        assertArrayEquals(new double[] {5.0}, out[2]);
    }

    /**
     * cmpframe.c {@code Overlay}: when each component of a CmpFrame result takes its axes from a
     * different component of the template, each template component is overlaid onto the
     * corresponding result component, so the components' Titles go to the result's components and
     * the result's own Title stays unset.
     */
    @Test
    void componentsOverlayTheMatchingComponents() {
        Frame t1 = new Frame(1);
        t1.setTitle("T1");
        Frame t2 = new Frame(1);
        t2.setTitle("T2");
        CmpFrame template = new CmpFrame(t1, t2);
        Frame r1 = new Frame(1);
        Frame r2 = new Frame(1);
        CmpFrame result = new CmpFrame(r1, r2);
        template.overlay(null, result);
        assertEquals("T1", r1.getTitle());
        assertEquals("T2", r2.getTitle());
        assertFalse(result.testTitle());
    }

    /**
     * cmpframe.c {@code Overlay}: when the result is not a CmpFrame, each template component is
     * overlaid onto the whole result, the second last.
     */
    @Test
    void componentsOverlayAPlainResultInTurn() {
        Frame t1 = new Frame(1);
        t1.setTitle("T1");
        Frame t2 = new Frame(1);
        t2.setTitle("T2");
        CmpFrame template = new CmpFrame(t1, t2);
        Frame result = new Frame(2);
        template.overlay(null, result);
        assertEquals("T2", result.getTitle());
    }
}
