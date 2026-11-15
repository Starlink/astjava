package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/error_model.rs}. The tests of error context and source
 * locations are of Rust-only API and are listed in {@code deferred/rust-tests.txt}.
 */
class ErrorModelTest {

    @Test
    void newErrorHasEmptyContext() {
        AstException err = new AstException("test message", AstException.AST__ATGER);
        assertEquals(AstException.AST__ATGER, err.getStatus());
        // jniast appends the status name to the message.
        assertEquals("test message (AST__ATGER)", err.getMessage());
    }

    @Test
    void errHelperBuildsTheException() {
        AstException err = Ast.err(AstException.AST__INTER, "bug");
        assertEquals(AstException.AST__INTER, err.getStatus());
        assertEquals("AST__INTER", err.getStatusName());
        assertEquals("bug (AST__INTER)", err.getMessage());
    }

    @Test
    void isARuntimeException() {
        RuntimeException e = new AstException("x");
        assertSame(AstException.class, e.getClass());
    }
}
