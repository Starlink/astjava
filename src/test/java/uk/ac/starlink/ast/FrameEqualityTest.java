package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_frame_equality.rs}: Frames are equal when the conversion
 * between them simplifies to a UnitMap (frame.c {@code Equal}), so attributes that only describe
 * the axes play no part. C's answers are in the probe {@code probes/equal_probe.c}.
 */
class FrameEqualityTest {

    private static SkyFrame icrs() {
        SkyFrame f = new SkyFrame();
        f.setSystem("ICRS");
        return f;
    }

    @Test
    void basicFramesDifferingOnlyInCosmeticsAreEqual() {
        Frame f1 = new Frame(2);
        f1.set("Title=First frame,Label(1)=X,Symbol(1)=x,Format(1)=3");
        Frame f2 = new Frame(2);
        f2.set("Title=Second frame,Label(1)=Different label,Symbol(1)=q,Format(1)=7");
        assertTrue(f1.astEqual(f2));
    }

    @Test
    void basicFramesDifferingInDomainAreNotEqual() {
        Frame f1 = new Frame(2);
        f1.setDomain("PIXEL");
        Frame f2 = new Frame(2);
        f2.setDomain("GRID");
        assertFalse(f1.astEqual(f2));
    }

    @Test
    void skyframesDifferingOnlyInTitleAreEqual() {
        SkyFrame sf2 = icrs();
        sf2.setTitle("A different description");
        assertTrue(icrs().astEqual(sf2));
    }

    @Test
    void skyframeExplicitDefaultDomainEqualsUnsetDomain() {
        SkyFrame sf2 = icrs();
        sf2.setDomain("SKY");
        assertTrue(icrs().astEqual(sf2));
    }

    @Test
    void skyframesDifferingInSystemAreNotEqual() {
        SkyFrame sf2 = new SkyFrame();
        sf2.setSystem("Galactic");
        assertFalse(icrs().astEqual(sf2));
    }
}
