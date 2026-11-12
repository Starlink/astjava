package uk.ac.starlink.ast;

/**
 * Makes an Object from its description, choosing the loading constructor by class name, as C's
 * {@code astGetLoader} (loader.c) chooses the loader.
 *
 * <p>Ported from Rust {@code serial/mod.rs} ({@code any_object_from_node}).
 */
final class ObjectRegistry {

    private ObjectRegistry() {}

    /**
     * The Object {@code node} describes.
     *
     * @throws AstException with status {@code AST__LDERR} if the class cannot be loaded here, or
     *     the error of loading it
     */
    static AstObject load(NativeNode node) {
        switch (node.className) {
            case "Channel":
                return new Channel(node);
            case "CmpMap":
                return new CmpMap(node);
            case "KeyMap":
                return new KeyMap(node);
            case "MatrixMap":
                return new MatrixMap(node);
            case "PermMap":
                return new PermMap(node);
            case "ShiftMap":
                return new ShiftMap(node);
            case "UnitMap":
                return new UnitMap(node);
            case "WinMap":
                return new WinMap(node);
            case "ZoomMap":
                return new ZoomMap(node);
            default:
                throw Ast.err(AstException.AST__LDERR, "unknown AST class: " + node.className);
        }
    }
}
