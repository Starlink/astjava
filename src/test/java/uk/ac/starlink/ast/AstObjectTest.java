package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Object-class methods that are not attribute access. */
class AstObjectTest {

    @Test
    void constantsHaveCsValues() {
        // pointset.h AST__BAD and memory.h AST__TUNULL.
        assertEquals(-Double.MAX_VALUE, AstObject.AST__BAD, 0.0);
        assertEquals(-99999, AstObject.AST__TUNULL);
    }

    @Test
    void tuneReturnsTheOldValueAndSetsANewOne() {
        // astTune (object.c:4333): both parameters default to 0, AST__TUNULL only reads.
        assertEquals(0, AstObject.tune("ObjectCaching", AstObject.AST__TUNULL));
        assertEquals(0, AstObject.tune("objectcaching  ", 5));
        assertEquals(5, AstObject.tune("ObjectCaching", 0));
        assertEquals(0, AstObject.tune("MemoryCaching", AstObject.AST__TUNULL));
        AstException e = assertThrows(AstException.class, () -> AstObject.tune("NoSuchParam", 1));
        assertEquals(AstException.AST__TUNAM, e.getStatus());
    }

    @Test
    @SuppressWarnings("deprecation")
    void annulAndDeleteDoNothing() {
        AstObject o = new PlainObject();
        o.setIdent("x");
        o.annul();
        o.delete();
        assertEquals("x", o.getIdent());
    }

    @Test
    void sameObjectIsIdentity() {
        AstObject o = new PlainObject();
        assertTrue(o.sameObject(o));
        assertFalse(o.sameObject(o.copy()));
        assertFalse(o.sameObject("x"));
    }

    @Test
    void threadedAndVersions() {
        assertTrue(AstObject.isThreaded());
        assertTrue(AstObject.reportVersions().startsWith("AST V9.5-0; "), AstObject.reportVersions());
    }

    @Test
    void refCountIsOne() {
        assertEquals(1, new PlainObject().getRefCount());
    }
}
