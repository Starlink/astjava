package uk.ac.starlink.ast;

/**
 * Renders a {@link NativeNode} as AST's native text format, byte for byte as C's {@code astWrite}
 * writes it through a Channel (channel.c {@code WriteBegin}, {@code WriteIsA}, {@code WriteEnd}
 * and the {@code Write<type>} functions).
 *
 * <p>Ported from Rust {@code serial/native_write.rs}.
 */
final class NativeWriter {

    /** {@code AST__DBL_DIG}: the significant digits written for a double. */
    static final int DBL_DIG = 17;

    private NativeWriter() {}

    /**
     * A double as C's {@code WriteDouble} formats it (channel.c): {@code %.*g} with {@code
     * precision} significant digits, except that zero of either sign is {@code "0"}.
     */
    static String formatDoubleG(double value, int precision) {
        if (value == 0.0) {
            return "0";
        }
        return CFormat.format("%." + precision + "g", value);
    }

    /** Renders {@code node} with {@code options}, as lines each ending in a newline. */
    static String render(NativeNode node, ChannelOptions options) {
        StringBuilder buf = new StringBuilder();
        writeObject(buf, node, 0, options);
        return buf.toString();
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Appends {@code " \t# comment"} when comments are on and the comment is not empty: each C
     * {@code Write} function tests {@code astGetComment(this) && *comment}.
     */
    private static void pushComment(StringBuilder buf, ChannelOptions options, String comment) {
        if (options.comment && comment != null && !comment.isEmpty()) {
            buf.append(" \t# ").append(comment);
        }
    }

    /**
     * Writes one Object at {@code base} spaces of indentation: {@code Begin}, {@code IsA} and
     * {@code End} lines start after {@code base + 1} characters, items after {@code base + indent
     * + 1}, an unset item with {@code #} and {@code base + indent} spaces, and nested Objects at
     * {@code base + 2 * indent}.
     */
    private static void writeObject(StringBuilder buf, NativeNode node, int base, ChannelOptions options) {
        int indent = options.indent;
        String beginIndent = spaces(base) + " ";
        String attrIndent = spaces(base + indent + 1);
        String unsetPrefix = "#" + spaces(base + indent);

        buf.append(beginIndent).append("Begin ").append(node.className);
        pushComment(buf, options, node.classComment);
        buf.append('\n');

        // C's astWriteIsA (channel.c) writes a marker only when something has been written since
        // the previous Begin or IsA, or when Full is positive.
        boolean itemsWritten = false;
        for (NativeNode.Item item : node.items) {
            if (item.isIsA()) {
                if (!itemsWritten && options.full <= 0) {
                    continue;
                }
                if (item.value.kind == NativeNode.Kind.STRING) {
                    buf.append(beginIndent).append("IsA ").append(item.value.stringValue);
                    pushComment(buf, options, item.comment);
                    buf.append('\n');
                }
                itemsWritten = false;
                continue;
            }
            if (!item.set) {
                // channel.c Use: no unset value at Full -1, only the helpful ones at Full 0.
                if (options.full < 0 || (options.full == 0 && !item.helpful)) {
                    continue;
                }
                buf.append(unsetPrefix).append(item.key);
                if (item.value.kind == NativeNode.Kind.OBJECT) {
                    // An unset Object is its name alone (channel.c WriteObject).
                    buf.append(" =");
                } else {
                    buf.append(" = ");
                    writeScalarValue(buf, item.value);
                }
                pushComment(buf, options, item.comment);
                buf.append('\n');
                itemsWritten = true;
            } else if (item.value.kind == NativeNode.Kind.OBJECT) {
                // channel.c WriteObject: the name and " =", with no value on the line.
                buf.append(attrIndent).append(item.key).append(" =");
                pushComment(buf, options, item.comment);
                buf.append('\n');
                NativeNode child = item.value.object;
                ChannelOptions childOptions = child.fullCapped && options.full <= 0 ? options.withFull(-1) : options;
                writeObject(buf, child, base + 2 * indent, childOptions);
                itemsWritten = true;
            } else {
                buf.append(attrIndent).append(item.key).append(" = ");
                writeScalarValue(buf, item.value);
                pushComment(buf, options, item.comment);
                buf.append('\n');
                itemsWritten = true;
            }
        }

        // The End line never has a comment.
        buf.append(beginIndent).append("End ").append(node.className).append('\n');
    }

    /** A scalar value: an integer, a double ({@code <bad>} for AST__BAD) or a quoted string. */
    private static void writeScalarValue(StringBuilder buf, NativeNode.Value value) {
        switch (value.kind) {
            case INT:
                buf.append(value.intValue);
                break;
            case DOUBLE:
                // C's astWriteDouble writes AST__BAD as the unquoted token <bad> (channel.c:146).
                if (value.doubleValue == AstObject.AST__BAD) {
                    buf.append("<bad>");
                } else {
                    buf.append(formatDoubleG(value.doubleValue, DBL_DIG));
                }
                break;
            case STRING:
                // Strings are quoted, with each embedded quote doubled.
                buf.append('"').append(value.stringValue.replace("\"", "\"\"")).append('"');
                break;
            case OBJECT:
                throw Ast.err(AstException.AST__INTER, "NativeWriter: an Object is not a scalar value.");
            default:
                throw Ast.err(AstException.AST__INTER, "NativeWriter: unknown value kind " + value.kind + ".");
        }
    }
}
