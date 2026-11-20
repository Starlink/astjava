package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A Frame attribute value holding {@code %} set on a Region is kept as given, whether or not the
 * Region is Adaptive. C's region.c {@code SetAttrib} passes the setting of an Adaptive Region to
 * {@code astSet} as its format, so C's value differs (a C bug, recorded in {@code
 * docs/rust-c-differences.md}); this pins Java's behaviour.
 */
class RegionAttributeValueTest {

    private static Box box(boolean adaptive) {
        Box b = new Box(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null);
        b.setAdaptive(adaptive);
        return b;
    }

    @Test
    void aValueHoldingAPercentSignIsKept() {
        for (boolean adaptive : new boolean[] {true, false}) {
            Box b = box(adaptive);
            b.setFormat(1, "%10.3f");
            b.setTitle("50%s done");
            assertEquals("%10.3f", b.getFormat(1), "Format, Adaptive=" + adaptive);
            assertEquals("50%s done", b.getTitle(), "Title, Adaptive=" + adaptive);
        }
    }
}
