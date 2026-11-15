package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How a CmpFrame's attributes are reached by name, as cmpframe.c's {@code GetAttrib}, {@code
 * SetAttrib}, {@code TestAttrib} and {@code ClearAttrib} reach them. No Rust test covers this: the
 * Rust port has no attribute strings (its {@code PLAN.md} records the string route as open). The
 * expected values are read from the C code cited at each test.
 */
class CmpFrameAttribTest {

    /**
     * cmpframe.c {@code GetAttrib} reads a name with an axis number from the primary Frame holding
     * that axis, with the axis number it has there, so the second Frame's only axis is "Axis 1";
     * the CmpFrame's own {@code GetLabel} ({@code MAKE_GET(Label)}) numbers the default Label as
     * the CmpFrame's axis.
     */
    @Test
    void anAxisNumberedNameIsReadFromThePrimaryFrame() {
        CmpFrame cmp = new CmpFrame(new Frame(1), new Frame(1));
        assertEquals("Axis 1", cmp.getC("Label(1)"));
        assertEquals("Axis 1", cmp.getC("Label(2)"));
        assertEquals("Axis 1", cmp.getLabel(1));
        assertEquals("Axis 2", cmp.getLabel(2));
        assertEquals("x1", cmp.getC("Symbol(2)"));
        assertEquals("x2", cmp.getSymbol(2));
    }

    /**
     * The axis number given to the primary Frame is its external one (cmpframe.c {@code GetAttrib}
     * calls {@code astValidateAxis} on the primary Frame), so a permuted component is addressed
     * through its own permutation.
     */
    @Test
    void thePrimaryFramesPermutationIsHonoured() {
        Frame f = new Frame(2);
        f.setLabel(1, "first");
        f.setLabel(2, "second");
        f.permAxes(new int[] {2, 1});
        CmpFrame cmp = new CmpFrame(new Frame(1), f);
        assertEquals("second", cmp.getC("Label(2)"));
        assertEquals("first", cmp.getC("Label(3)"));
        assertEquals("second", cmp.getLabel(2));
    }

    /**
     * A name the Frame class does not know is tried on the primary Frame of each axis
     * (cmpframe.c {@code SetAttrib}, {@code GetAttrib}, {@code TestAttrib}, {@code ClearAttrib}).
     */
    @Test
    void aComponentAttributeIsReachedThroughTheCmpFrame() {
        SkyFrame sky = new SkyFrame();
        CmpFrame cmp = new CmpFrame(new Frame(1), sky);
        assertFalse(cmp.test("Equinox"));
        cmp.set("Equinox=J1990");
        assertTrue(sky.test("Equinox"));
        assertTrue(cmp.test("Equinox"));
        assertEquals(sky.getC("Equinox"), cmp.getC("Equinox"));
        cmp.clear("Equinox");
        assertFalse(sky.test("Equinox"));
        cmp.set("AsTime(3)=1");
        assertTrue(sky.test("AsTime(2)"));
        assertEquals("1", cmp.getC("AsTime(3)"));
    }

    /** No part of the CmpFrame knowing a name gives C's {@code AST__BADAT} errors. */
    @Test
    void anUnknownNameIsAnError() {
        CmpFrame cmp = new CmpFrame(new Frame(1), new Frame(1));
        AstException e = assertThrows(AstException.class, () -> cmp.getC("Junk"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
        e = assertThrows(AstException.class, () -> cmp.test("Junk"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
        e = assertThrows(AstException.class, () -> cmp.set("Junk=1"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
        e = assertThrows(AstException.class, () -> cmp.clear("Junk"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
    }

    /**
     * The Epoch set on a CmpFrame is set on both components and cleared from both (cmpframe.c
     * {@code SetEpoch}, {@code ClearEpoch}); unset, the first component's set value is used
     * ({@code GetEpoch}).
     */
    @Test
    void epochGoesToBothComponents() {
        SkyFrame sky = new SkyFrame();
        Frame f = new Frame(1);
        CmpFrame cmp = new CmpFrame(sky, f);
        cmp.set("Epoch=J2010");
        assertTrue(sky.test("Epoch"));
        assertTrue(f.test("Epoch"));
        cmp.clear("Epoch");
        assertFalse(sky.test("Epoch"));
        assertFalse(f.test("Epoch"));
        f.set("Epoch=J1995");
        assertEquals(f.getD("Epoch"), cmp.getD("Epoch"));
        assertFalse(cmp.test("Epoch"));
    }
}
