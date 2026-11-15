package uk.ac.starlink.ast;

import java.util.Locale;

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
            case "Axis":
                return Axis::new;
            case "Channel":
                return Channel::new;
            case "CmpFrame":
                return CmpFrame::new;
            case "CmpMap":
                return CmpMap::new;
            case "FitsChan":
                return FitsChan::new;
            case "Frame":
                return Frame::new;
            case "FrameSet":
                return FrameSet::new;
            case "KeyMap":
                return KeyMap::new;
            case "MatrixMap":
                return MatrixMap::new;
            case "PermMap":
                return PermMap::new;
            case "PolyMap":
                return PolyMap::new;
            case "ShiftMap":
                return ShiftMap::new;
            case "SkyAxis":
                return SkyAxis::new;
            case "SkyFrame":
                return SkyFrame::new;
            case "SlaMap":
                return SlaMap::new;
            case "SphMap":
                return SphMap::new;
            case "TimeFrame":
                return TimeFrame::new;
            case "TimeMap":
                return TimeMap::new;
            case "UnitMap":
                return UnitMap::new;
            case "WcsMap":
                return WcsMap::new;
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

    /**
     * The Object {@code node} describes, which is the value of item {@code key} of another
     * Object's description. C loads such an Object while it reads the outer one, and when the
     * load fails adds "Failed to read the "key" Object value." to the error, once, for the
     * innermost such Object (channel.c:2929, 2947-2953); a description read from a stream gets
     * that message here.
     *
     * @throws AstException with status {@code AST__LDERR} if the class cannot be loaded here, or
     *     the error of loading it
     */
    static AstObject loadNested(NativeNode node, String key) {
        NativeNode.Progress progress = node.progress;
        if (progress == null) {
            return load(node);
        }
        progress.nestedMsg = false;
        try {
            return load(node);
        } catch (AstException e) {
            if (progress.nestedMsg) {
                throw e;
            }
            progress.nestedMsg = true;
            throw Ast.withContext(e, "Failed to read the \"" + key.toLowerCase(Locale.ROOT) + "\" Object value.");
        }
    }
}
