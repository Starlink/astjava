package uk.ac.starlink.ast;

/**
 * Makes an Object from its description, choosing the loading constructor by class name, as C's
 * {@code astGetLoader} (loader.c) chooses the loader.
 *
 * <p>Ported from Rust {@code serial/mod.rs} ({@code any_object_from_node}).
 */
final class ObjectRegistry {

    /** A loading constructor: makes an Object of one class from its description. */
    @FunctionalInterface
    interface Loader {

        /** The Object {@code node} describes. */
        AstObject load(NativeNode node);
    }

    private ObjectRegistry() {}

    /**
     * The loading constructor of the class called {@code className}, as C's {@code astGetLoader}
     * gives a class's loader, or null if this library cannot load that class.
     */
    static Loader loader(String className) {
        switch (className) {
            case "Channel":
                return Channel::new;
            case "CmpMap":
                return CmpMap::new;
            case "KeyMap":
                return KeyMap::new;
            case "MatrixMap":
                return MatrixMap::new;
            case "PermMap":
                return PermMap::new;
            case "ShiftMap":
                return ShiftMap::new;
            case "UnitMap":
                return UnitMap::new;
            case "WinMap":
                return WinMap::new;
            case "ZoomMap":
                return ZoomMap::new;
            default:
                return null;
        }
    }

    /** Whether this library can load an Object of the class called {@code className}. */
    static boolean isRegistered(String className) {
        return loader(className) != null;
    }

    /**
     * The Object {@code node} describes.
     *
     * @throws AstException with status {@code AST__LDERR} if the class cannot be loaded here, or
     *     the error of loading it
     */
    static AstObject load(NativeNode node) {
        Loader loader = loader(node.className);
        if (loader == null) {
            throw Ast.err(AstException.AST__LDERR, "unknown AST class: " + node.className);
        }
        return loader.load(node);
    }
}
