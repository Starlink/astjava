package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_object_id_oracle.rs}: ID, the Object attribute every AST
 * Object has, against C's (object.c). The dump cases of {@code oracle/object_id.json} need the
 * classes they dump, and are translated with Channel and the classes that follow it.
 */
class ObjectIdOracleTest {

    @Test
    void aCloneHasNoIdAndKeepsIdent() {
        AstObject m = new PlainObject();
        m.setID("id");
        m.setIdent("ident");
        AstObject copy = m.copy();
        assertNotSame(m, copy);
        assertSame(PlainObject.class, copy.getClass());
        assertFalse(copy.test("ID"));
        assertEquals("", copy.getID());
        assertEquals("ident", copy.getIdent());
        assertEquals("id", m.getID());
    }
}
