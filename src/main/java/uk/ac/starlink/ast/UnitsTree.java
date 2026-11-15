package uk.ac.starlink.ast;

/**
 * The tree a units expression is parsed into. Each node performs one operation; its arguments
 * are the nodes below it, and the leaves load constants or named units.
 *
 * <p>Ported from Rust {@code units/tree.rs} and C {@code unit.c} ({@code UnitNode}, {@code Oper},
 * {@code NewNode} and {@code CopyTree}).
 */
final class UnitsTree {

    /** C's {@code PI}, the value an {@link Oper#LDPI} node loads. */
    static final double PI = 3.141592653589793;

    /** C's {@code E}, the value an {@link Oper#LDE} node loads. */
    static final double E = 2.718281828459045;

    private UnitsTree() {}

    /**
     * The operation a node performs, in the order of C's {@code Oper} enumeration, which {@link
     * UnitsCmp#cmpTree} uses to order nodes of different kinds.
     */
    enum Oper {
        /** Load a constant. */
        LDCON(0),
        /** Load a named unit. */
        LDVAR(0),
        /** Common logarithm. */
        LOG(1),
        /** Natural logarithm. */
        LN(1),
        /** Natural exponential. */
        EXP(1),
        /** Square root. */
        SQRT(1),
        /** Exponentiation: argument 0 raised to argument 1. */
        POW(2),
        /** Division: argument 0 divided by argument 1. */
        DIV(2),
        /** Multiplication. */
        MULT(2),
        /** Load the constant pi. */
        LDPI(0),
        /** Load the constant e. */
        LDE(0),
        /** No operation. */
        NULL(0);

        /** The number of arguments the operation takes. */
        final int narg;

        Oper(int narg) {
            this.narg = narg;
        }
    }

    /**
     * One node of a tree. As in C, a node that loads a constant holds it in {@code con}, which is
     * {@code AST__BAD} in every other node, so {@code con != AST__BAD} identifies a constant.
     */
    static final class Node {
        /** The operation. */
        Oper opcode;

        /** The argument nodes, as many as the operation takes. */
        Node[] arg;

        /** The constant an {@link Oper#LDCON}, {@link Oper#LDPI} or {@link Oper#LDE} node loads. */
        double con;

        /** The known unit an {@link Oper#LDVAR} node refers to, or null for another unit. */
        UnitsTable.KnownUnit unit;

        /** The multiplier prefix of an {@link Oper#LDVAR} node, or null. */
        UnitsTable.Multiplier mult;

        /** The name of the unit an {@link Oper#LDVAR} node loads, without its prefix. */
        String name;

        Node(Oper opcode) {
            this.opcode = opcode;
            this.arg = new Node[opcode.narg];
            this.con = AstObject.AST__BAD;
        }

        /** Whether this node loads a constant number (Rust {@code is_const}). */
        boolean isConst() {
            return opcode == Oper.LDCON && con != AstObject.AST__BAD;
        }

        /**
         * Makes this node the head of the tree headed by {@code other}, which C does by replacing
         * a pointer to the node with a pointer to {@code other}; {@code other} is not used after
         * this.
         */
        void assign(Node other) {
            opcode = other.opcode;
            arg = other.arg;
            con = other.con;
            unit = other.unit;
            mult = other.mult;
            name = other.name;
        }
    }

    /**
     * A new node for the operation {@code code}, with null arguments, as C's {@code NewNode}
     * makes it: an {@link Oper#LDPI} or {@link Oper#LDE} node loads its constant, and every
     * other node has {@code con} set to {@code AST__BAD}.
     */
    static Node newNode(Oper code) {
        Node result = new Node(code);
        if (code == Oper.LDPI) {
            result.con = PI;
        } else if (code == Oper.LDE) {
            result.con = E;
        }
        return result;
    }

    /** A new node loading the constant {@code con}. */
    static Node ldcon(double con) {
        Node result = newNode(Oper.LDCON);
        result.con = con;
        return result;
    }

    /** A new node loading the unit named {@code name}, which has no prefix and no known unit. */
    static Node ldvar(String name) {
        Node result = newNode(Oper.LDVAR);
        result.name = name;
        return result;
    }

    /** A complete copy of the tree headed by {@code tree}, or null for null (C {@code CopyTree}). */
    static Node copyTree(Node tree) {
        if (tree == null) {
            return null;
        }
        Node result = new Node(tree.opcode);
        result.unit = tree.unit;
        result.mult = tree.mult;
        result.con = tree.con;
        result.name = tree.name;
        result.arg = new Node[tree.arg.length];
        for (int i = 0; i < tree.arg.length; i++) {
            result.arg[i] = copyTree(tree.arg[i]);
        }
        return result;
    }
}
