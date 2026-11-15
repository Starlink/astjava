package uk.ac.starlink.ast;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses AST's native text format into a {@link NativeNode}, class by class, without loading it.
 * The format is line-oriented:
 *
 * <ul>
 *   <li>{@code Begin ClassName # comment} starts an Object.
 *   <li>{@code Key = Value # comment} is a scalar item.
 *   <li>{@code Key = # comment}, with no value, introduces a nested Object on the next line.
 *   <li>{@code IsA ClassName # comment} ends the items of one class.
 *   <li>{@code End ClassName} ends the Object.
 *   <li>{@code # Key = Value # comment} is an unset value; other {@code #} lines are comments.
 * </ul>
 *
 * <p>A Channel reads through {@link NativeStream}, which follows C's reader line by line; this
 * parser reads a whole description at once. Ported from Rust {@code serial/native_read.rs}.
 */
final class NativeReader {

    /** A decimal number as Rust's {@code f64} parser accepts it. */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    /** An integer as Rust's {@code i64} parser accepts it. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");

    private NativeReader() {}

    /** Whether {@code c} is white space as Rust's {@code char::is_whitespace} defines it. */
    static boolean isWhite(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || c == '\u0085' || Character.isSpaceChar(c);
    }

    /** {@code s} without leading or trailing white space (Rust {@code str::trim}). */
    static String trim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isWhite(s.charAt(a))) {
            a++;
        }
        while (b > a && isWhite(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    /** {@code s} without leading white space (Rust {@code str::trim_start}). */
    static String trimStart(String s) {
        int a = 0;
        while (a < s.length() && isWhite(s.charAt(a))) {
            a++;
        }
        return s.substring(a);
    }

    /** The lines of {@code text}, as Rust's {@code str::lines} splits them. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            int e = end;
            if (e > start && text.charAt(e - 1) == '\r') {
                e--;
            }
            out.add(text.substring(start, e));
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return out;
    }

    /**
     * Parses native text holding one top-level Object. Blank lines and comment lines before the
     * {@code Begin} line are skipped.
     *
     * @throws AstException with status {@code AST__RDERR} if the text is not one well-formed
     *     Object
     */
    static NativeNode parse(String text) {
        List<String> lines = lines(text);
        int pos = 0;
        while (pos < lines.size() && isBlankOrComment(trim(lines.get(pos)))) {
            pos++;
        }
        if (pos >= lines.size()) {
            throw Ast.err(AstException.AST__RDERR, "no Begin found in native text");
        }
        String[] begin = parseBeginLine(trim(lines.get(pos)));
        int[] next = new int[1];
        return parseObject(lines, pos + 1, begin[0], begin[1], next);
    }

    private static boolean isBlankOrComment(String t) {
        return t.isEmpty() || t.startsWith("#");
    }

    /** A {@code Begin ClassName # comment} line: the class name and the comment (or null). */
    private static String[] parseBeginLine(String trimmed) {
        if (!trimmed.startsWith("Begin ")) {
            throw Ast.err(AstException.AST__RDERR, "expected 'Begin', got: " + trimmed);
        }
        String[] split = splitComment(trimmed.substring("Begin ".length()));
        String cls = trim(split[0]);
        if (cls.isEmpty()) {
            throw Ast.err(AstException.AST__RDERR, "empty class name in Begin");
        }
        return new String[] {cls, split[1]};
    }

    /**
     * Splits a line at the first {@code #} outside double quotes: the text before it and the
     * comment after it, trimmed, or null if there is none or it is blank.
     */
    static String[] splitComment(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '#' && !inQuotes) {
                String after = trim(line.substring(i + 1));
                return new String[] {line.substring(0, i), after.isEmpty() ? null : after};
            }
        }
        return new String[] {line, null};
    }

    /** The index of the first {@code =} outside double quotes, or -1. */
    private static int findEquals(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '=' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code s} equals {@code word} ignoring the case of ASCII letters. */
    private static boolean equalsIgnoreAsciiCase(String s, String word) {
        return NativeNode.keyMatches(s, word);
    }

    /**
     * A scalar value: a quoted string, with its quotes removed and doubled quotes made single;
     * {@code <bad>}, which is {@code AST__BAD}; an integer; a decimal number; or otherwise an
     * unquoted string such as {@code GALACTIC}.
     */
    static NativeNode.Value parseScalarValue(String str) {
        String s = trim(str);
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return NativeNode.Value.ofString(s.substring(1, s.length() - 1).replace("\"\"", "\""));
        }
        // AST__BAD is written as the unquoted token <bad> (channel.c:146).
        if ("<bad>".equals(s)) {
            return NativeNode.Value.ofDouble(AstObject.AST__BAD);
        }
        if (INTEGER.matcher(s).matches()) {
            // An integer too large for a long is read as a double, as Rust's i64 parse fails.
            BigInteger big = new BigInteger(s.startsWith("+") ? s.substring(1) : s);
            if (big.bitLength() < 64) {
                return NativeNode.Value.ofInt(big.longValue());
            }
        }
        if (!equalsIgnoreAsciiCase(s, "inf")
                && !equalsIgnoreAsciiCase(s, "infinity")
                && !equalsIgnoreAsciiCase(s, "nan")) {
            Double d = parseRustDouble(s);
            if (d != null) {
                return NativeNode.Value.ofDouble(d);
            }
        }
        return NativeNode.Value.ofString(s);
    }

    /** Rust's {@code str::parse::<f64>}: a decimal number, or a signed infinity or NaN. */
    private static Double parseRustDouble(String s) {
        if (DECIMAL.matcher(s).matches()) {
            return Double.parseDouble(s);
        }
        String body = s;
        boolean neg = false;
        if (!body.isEmpty() && (body.charAt(0) == '+' || body.charAt(0) == '-')) {
            neg = body.charAt(0) == '-';
            body = body.substring(1);
        }
        if (equalsIgnoreAsciiCase(body, "inf") || equalsIgnoreAsciiCase(body, "infinity")) {
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (equalsIgnoreAsciiCase(body, "nan")) {
            return Double.NaN;
        }
        return null;
    }

    /**
     * The text after a leading {@code #} as an unset value ({@code # Key = Value # comment}), or
     * null if it is a plain comment.
     */
    static NativeNode.Item parseCommentedDefault(String afterHash) {
        String t = trimStart(afterHash);
        int keyEnd = 0;
        while (keyEnd < t.length() && (Character.isLetterOrDigit(t.charAt(keyEnd)) || t.charAt(keyEnd) == '_')) {
            keyEnd++;
        }
        if (keyEnd == t.length()) {
            return null;
        }
        String key = t.substring(0, keyEnd);
        char first = key.isEmpty() ? ' ' : key.charAt(0);
        if (key.isEmpty() || !((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) {
            return null;
        }
        String rest = trimStart(t.substring(keyEnd));
        if (!rest.startsWith("=")) {
            return null;
        }
        String[] split = splitComment(rest.substring(1));
        String valueStr = trim(split[0]);
        NativeNode.Value inner = valueStr.isEmpty() ? NativeNode.Value.ofString("") : parseScalarValue(valueStr);
        return new NativeNode.Item(key, inner, false, split[1], true);
    }

    /** The body of an Object, from the line after its {@code Begin} to its {@code End}. */
    private static NativeNode parseObject(List<String> lines, int start, String cls, String classComment, int[] next) {
        NativeNode node = new NativeNode(cls, classComment);
        int pos = start;
        while (pos < lines.size()) {
            String trimmed = trim(lines.get(pos));
            if (trimmed.isEmpty()) {
                pos++;
                continue;
            }
            if (trimmed.startsWith("End ")) {
                String endClass = trim(splitComment(trimmed.substring(4))[0]);
                if (!endClass.equals(cls)) {
                    throw Ast.err(
                            AstException.AST__RDERR,
                            "mismatched End on line " + (pos + 1) + ": expected '" + cls + "', got '" + endClass + "'");
                }
                next[0] = pos + 1;
                return node;
            }
            if (trimmed.startsWith("IsA ")) {
                String[] split = splitComment(trimmed.substring(4));
                node.writeIsA(trim(split[0]), split[1]);
                pos++;
                continue;
            }
            if (trimmed.startsWith("#")) {
                NativeNode.Item item = parseCommentedDefault(trimmed.substring(1));
                if (item != null) {
                    node.items.add(item);
                }
                pos++;
                continue;
            }
            int eq = findEquals(trimmed);
            if (eq < 0) {
                throw Ast.err(AstException.AST__RDERR, "unexpected line " + (pos + 1) + ": " + trimmed);
            }
            String key = trim(trimmed.substring(0, eq));
            String[] split = splitComment(trimmed.substring(eq + 1));
            String valueStr = trim(split[0]);
            if (valueStr.isEmpty()) {
                pos++;
                while (pos < lines.size() && trim(lines.get(pos)).isEmpty()) {
                    pos++;
                }
                if (pos >= lines.size()) {
                    throw Ast.err(
                            AstException.AST__RDERR,
                            "expected Begin for nested object after '" + key + "', got end of input");
                }
                String[] begin = parseBeginLine(trim(lines.get(pos)));
                NativeNode child = parseObject(lines, pos + 1, begin[0], begin[1], next);
                node.writeObject(key, true, true, child, split[1]);
                pos = next[0];
            } else {
                node.items.add(new NativeNode.Item(key, parseScalarValue(valueStr), true, split[1], true));
                pos++;
            }
        }
        throw Ast.err(AstException.AST__RDERR, "unexpected end of input: no End found for class '" + cls + "'");
    }
}
