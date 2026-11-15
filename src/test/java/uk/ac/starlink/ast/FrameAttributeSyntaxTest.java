package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Axis-indexed attribute names matched as C matches them (frame.c {@code GetAttrib}, {@code
 * SetAttrib}, {@code TestAttrib} and {@code ClearAttrib}): in any case, with white space, several
 * settings separated by commas, and an axis index after the name.
 *
 * <p>C's {@code VSet} (object.c) removes white space and folds case before the '=', and keeps the
 * value after it as given, so a space after the '=' is part of the value; frame.c {@code SetUnit}
 * removes only trailing white space from a Unit.
 */
class FrameAttributeSyntaxTest {

    @Test
    void axisIndexedNamesAreMatchedAsCMatchesThem() {
        Frame f = new Frame(2);
        f.set("label(1)=X, Unit(2) = deg");
        assertEquals("X", f.getC("Label(1)"));
        assertEquals(" deg", f.getC("unit(2)"));
        assertEquals("X", f.getC(" LABEL ( 1 ) "));
        assertEquals("Axis 2", f.getC("Label(2)"));
        AstException e = assertThrows(AstException.class, () -> f.getC("Label(3)"));
        assertEquals(AstException.AST__AXIIN, e.getStatus());
    }

    @Test
    void axisIndexedNamesCanBeTestedAndCleared() {
        Frame f = new Frame(2);
        f.set("Label(1)=X,Symbol(2)=y");
        assertTrue(f.test("label(1)"));
        assertFalse(f.test("label(2)"));
        f.clear("label(1), SYMBOL(2)");
        assertFalse(f.test("Label(1)"));
        assertFalse(f.test("Symbol(2)"));
        assertEquals("Axis 1", f.getC("Label(1)"));
    }

    /** On a one-axis Frame a per-axis name needs no index (frame.c:5269, 10588, 11710, 2490). */
    @Test
    void aOneAxisFrameNeedsNoIndex() {
        Frame f = new Frame(1);
        assertEquals("Axis 1", f.getC("Label"));
        f.set("Label=Distance");
        assertEquals("Distance", f.getC("Label(1)"));
        assertTrue(f.test("label"));
        f.clear("Label");
        assertFalse(f.test("Label(1)"));
    }

    /** Naxes, NormUnit and InternalUnit are read-only (frame.c:2379, 10460). */
    @Test
    void readOnlyAttributesCannotBeChanged() {
        Frame f = new Frame(2);
        assertEquals(2, f.getI("Naxes"));
        assertEquals(
                AstException.AST__NOWRT,
                assertThrows(AstException.class, () -> f.set("Naxes=3")).getStatus());
        assertEquals(
                AstException.AST__NOWRT,
                assertThrows(AstException.class, () -> f.clear("NormUnit(1)")).getStatus());
        assertFalse(f.test("InternalUnit(1)"));
    }

    @Test
    void anUnknownAttributeIsAnError() {
        Frame f = new Frame(2);
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> f.getC("Colour")).getStatus());
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> f.set("Colour(1)=red")).getStatus());
    }
}
