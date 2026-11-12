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

    /**
     * The exception {@code e} with a further line of context, as C adds a message to the error
     * stack with {@code astError} while keeping the status: the message texts are joined by a
     * newline, as jniast joins the messages of C's error stack.
     *
     * @param e the exception reporting the original error
     * @param context the further message
     */
    static AstException withContext(AstException e, String context) {
        AstException out = new AstException(e.getMsgText() + "\n" + context, e.getStatus());
        out.initCause(e.getCause());
        return out;
    }
}
