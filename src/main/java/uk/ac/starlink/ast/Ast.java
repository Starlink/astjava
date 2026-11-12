package uk.ac.starlink.ast;

/** Helpers used throughout the package. */
final class Ast {

    private Ast() {}

    /**
     * An exception reporting an AST error, for {@code throw Ast.err(AstException.AST__XXXX, "...")}.
     *
     * @param status the status value, one of the {@code AST__} constants of {@link AstException}
     * @param message a description of this occurrence of the error
     */
    static AstException err(int status, String message) {
        return new AstException(message, status);
    }
}
