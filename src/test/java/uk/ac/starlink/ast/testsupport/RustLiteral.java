package uk.ac.starlink.ast.testsupport;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reader for the constants of a generated {@code cases.rs} file, the C probe tables committed in
 * the Rust repository.
 *
 * <p>It understands the subset of Rust those files use for data: {@code const} items (public or
 * not) whose values are integer, float, string, raw string and boolean literals, tuples, arrays and
 * {@code &[...]} slices, {@code Some(x)} and {@code None}, negation, the {@code f64} constants
 * ({@code f64::NAN}, {@code f64::INFINITY}, {@code f64::NEG_INFINITY}, {@code f64::MAX}), AST's bad
 * value ({@code BAD_VALUE}, however qualified, or the name a {@code use ... BAD_VALUE as NAME}
 * item gives it), and names of constants defined earlier in the same
 * file, and the variants of an {@code enum} the file declares. {@code type} and other {@code use} items,
 * {@code enum} declarations, attributes and comments are skipped. Anything else throws {@link
 * IllegalArgumentException} naming its line.
 *
 * <p>Values are {@link Double}, {@link Long}, {@link BigInteger} (an unsigned integer too large for
 * a {@code long}), {@link String}, {@link Boolean}, {@link List} (tuples, arrays and slices), {@link
 * Map} (an enum variant: key {@code variant} its name and, unless it is a unit variant, key {@code
 * value} its field, or a list of its fields if it has more than one) and null ({@code None}); {@code
 * Some(x)} is read as {@code x}.
 */
public final class RustLiteral {

    private static final Pattern BAD_ALIAS = Pattern.compile("(?s).*\\bBAD_VALUE\\s+as\\s+(\\w+)\\s*");

    private final String src;
    private int pos;
    private final Map<String, Object> consts = new LinkedHashMap<>();
    private final Map<String, Object> all = new HashMap<>();
    private final Set<String> enums = new HashSet<>();

    private RustLiteral(String src) {
        this.src = src;
    }

    /**
     * Parses the {@code const} items of {@code source}, in order.
     *
     * @throws IllegalArgumentException for any construct outside the subset
     */
    public static Map<String, Object> parse(String source) {
        RustLiteral p = new RustLiteral(source);
        p.file();
        return p.consts;
    }

    private IllegalArgumentException error(String what) {
        int line = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        int end = src.indexOf('\n', pos);
        String rest = src.substring(Math.min(pos, src.length()), end < 0 ? src.length() : end);
        return new IllegalArgumentException("line " + line + ": " + what + " at \"" + rest + "\"");
    }

    private void file() {
        while (true) {
            skipSpace();
            if (pos >= src.length()) {
                return;
            }
            if (peek() == '#') {
                attribute();
                continue;
            }
            String word = ident();
            if ("pub".equals(word)) {
                skipSpace();
                word = ident();
            }
            if ("const".equals(word)) {
                constItem();
            } else if ("type".equals(word)) {
                skipItem();
            } else if ("use".equals(word)) {
                useItem();
            } else if ("enum".equals(word)) {
                skipSpace();
                enums.add(ident());
                skipSpace();
                if (peek() != '{') {
                    throw error("expected { after enum name");
                }
                skipBalanced();
            } else {
                throw error("unsupported item \"" + word + "\"");
            }
        }
    }

    private void attribute() {
        expect('#');
        if (peek() == '!') {
            pos++;
        }
        skipSpace();
        if (peek() != '[') {
            throw error("expected [ after #");
        }
        skipBalanced();
    }

    private void constItem() {
        skipSpace();
        String name = ident();
        skipSpace();
        expect(':');
        // The type: everything up to the '=' outside brackets.
        int depth = 0;
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated const type");
            }
            char c = src.charAt(pos);
            if (c == '[' || c == '(' || c == '<') {
                depth++;
            } else if (c == ']' || c == ')' || c == '>') {
                depth--;
            } else if (c == '=' && depth == 0) {
                break;
            }
            pos++;
        }
        pos++;
        Object value = expr();
        skipSpace();
        expect(';');
        all.put(name, value);
        consts.put(name, value);
    }

    private Object expr() {
        skipSpace();
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        char c = peek();
        if (c == '&') {
            pos++;
            return expr();
        }
        if (c == '-') {
            pos++;
            return negate(expr());
        }
        if (c == '[') {
            pos++;
            return list(']').values;
        }
        if (c == '(') {
            pos++;
            Seq s = list(')');
            return s.values.size() == 1 && !s.trailingComma ? s.values.get(0) : s.values;
        }
        if (c == '"') {
            pos++;
            return string();
        }
        if (c == 'r' && pos + 1 < src.length() && (src.charAt(pos + 1) == '"' || src.charAt(pos + 1) == '#')) {
            pos++;
            return rawString();
        }
        if (Character.isDigit(c)) {
            return number();
        }
        if (Character.isLetter(c) || c == '_') {
            return path();
        }
        throw error("unsupported expression");
    }

    private Object negate(Object v) {
        if (v instanceof Double) {
            return -(Double) v;
        }
        if (v instanceof Long) {
            return -(Long) v;
        }
        throw error("cannot negate " + v);
    }

    /** A comma-separated sequence; {@code trailingComma} distinguishes {@code (x,)} from {@code (x)}. */
    private static final class Seq {
        final List<Object> values = new ArrayList<>();
        boolean trailingComma;
    }

    private Seq list(char close) {
        Seq s = new Seq();
        while (true) {
            skipSpace();
            if (peek() == close) {
                pos++;
                return s;
            }
            s.values.add(expr());
            s.trailingComma = false;
            skipSpace();
            if (peek() == ',') {
                pos++;
                s.trailingComma = true;
            } else if (peek() != close) {
                throw error("expected , or " + close);
            }
        }
    }

    private Object path() {
        StringBuilder sb = new StringBuilder(ident());
        while (src.startsWith("::", pos)) {
            pos += 2;
            sb.append("::").append(ident());
        }
        String p = sb.toString();
        String last = p.substring(p.lastIndexOf(':') + 1);
        int sep = p.indexOf("::");
        if (sep > 0 && enums.contains(p.substring(0, sep))) {
            return variant(last);
        }
        switch (p) {
            case "true":
                return true;
            case "false":
                return false;
            case "None":
                return null;
            case "Some":
                skipSpace();
                expect('(');
                Object v = expr();
                skipSpace();
                expect(')');
                return v;
            case "f64::NAN":
                return Double.NaN;
            case "f64::INFINITY":
                return Double.POSITIVE_INFINITY;
            case "f64::NEG_INFINITY":
                return Double.NEGATIVE_INFINITY;
            case "f64::MAX":
                return Double.MAX_VALUE;
            default:
                if ("BAD_VALUE".equals(last) && !all.containsKey(p)) {
                    return CValues.BAD;
                }
                if (all.containsKey(p)) {
                    return all.get(p);
                }
                throw error("unknown name \"" + p + "\"");
        }
    }

    /** An enum variant named {@code name}, with its fields if any follow. */
    private Map<String, Object> variant(String name) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("variant", name);
        skipSpace();
        if (pos < src.length() && peek() == '(') {
            pos++;
            List<Object> fields = list(')').values;
            v.put("value", fields.size() == 1 ? fields.get(0) : fields);
        }
        return v;
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && (Character.isDigit(peek()) || peek() == '_')) {
            pos++;
        }
        boolean isFloat = false;
        if (pos < src.length()
                && peek() == '.'
                && !(pos + 1 < src.length()
                        && (src.charAt(pos + 1) == '.' || Character.isLetter(src.charAt(pos + 1))))) {
            isFloat = true;
            pos++;
            while (pos < src.length() && (Character.isDigit(peek()) || peek() == '_')) {
                pos++;
            }
        }
        if (pos < src.length() && (peek() == 'e' || peek() == 'E')) {
            isFloat = true;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            while (pos < src.length() && (Character.isDigit(peek()) || peek() == '_')) {
                pos++;
            }
        }
        String text = src.substring(start, pos).replace("_", "");
        if (pos < src.length() && (Character.isLetter(peek()) || peek() == '_')) {
            throw error("unsupported number suffix");
        }
        try {
            if (isFloat) {
                return Double.parseDouble(text);
            }
            BigInteger big = new BigInteger(text);
            return big.bitLength() < Long.SIZE ? (Object) big.longValue() : (Object) big;
        } catch (NumberFormatException e) {
            IllegalArgumentException err = error("bad number \"" + text + "\"");
            err.initCause(e);
            throw err;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case '0':
                    sb.append('\0');
                    break;
                case '\\':
                case '"':
                case '\'':
                    sb.append(e);
                    break;
                case 'x':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 2), 16));
                    pos += 2;
                    break;
                case 'u': {
                    expect('{');
                    int close = src.indexOf('}', pos);
                    sb.appendCodePoint(
                            Integer.parseInt(src.substring(pos, close).replace("_", ""), 16));
                    pos = close + 1;
                    break;
                }
                case '\n':
                    while (pos < src.length() && Character.isWhitespace(peek())) {
                        pos++;
                    }
                    break;
                default:
                    throw error("unsupported string escape \\" + e);
            }
        }
    }

    private String rawString() {
        int hashes = 0;
        while (peek() == '#') {
            hashes++;
            pos++;
        }
        expect('"');
        StringBuilder close = new StringBuilder("\"");
        for (int i = 0; i < hashes; i++) {
            close.append('#');
        }
        int end = src.indexOf(close.toString(), pos);
        if (end < 0) {
            throw error("unterminated raw string");
        }
        String s = src.substring(pos, end);
        pos = end + close.length();
        return s;
    }

    private String ident() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            pos++;
        }
        if (pos == start) {
            throw error("expected a name");
        }
        return src.substring(start, pos);
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected " + c);
        }
        pos++;
    }

    /** Skips a bracketed group starting at the current '[' or '(', strings included. */
    private void skipBalanced() {
        int depth = 0;
        do {
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                string();
                continue;
            }
            if (c == '[' || c == '(' || c == '{') {
                depth++;
            } else if (c == ']' || c == ')' || c == '}') {
                depth--;
            }
            pos++;
        } while (depth > 0 && pos < src.length());
    }

    /** A {@code use} item: skipped, except that {@code BAD_VALUE as NAME} makes NAME the bad value. */
    private void useItem() {
        int start = pos;
        skipItem();
        Matcher m = BAD_ALIAS.matcher(src.substring(start, pos - 1));
        if (m.matches()) {
            all.put(m.group(1), CValues.BAD);
        }
    }

    /** Skips past the ';' ending an item, ignoring any inside brackets, as in {@code [f64; 2]}. */
    private void skipItem() {
        int depth = 0;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                string();
                continue;
            }
            pos++;
            if (c == '[' || c == '(' || c == '{') {
                depth++;
            } else if (c == ']' || c == ')' || c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                return;
            }
        }
        throw error("unterminated item");
    }

    private void skipSpace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (src.startsWith("//", pos)) {
                int nl = src.indexOf('\n', pos);
                pos = nl < 0 ? src.length() : nl + 1;
            } else if (src.startsWith("/*", pos)) {
                int end = src.indexOf("*/", pos + 2);
                if (end < 0) {
                    throw error("unterminated comment");
                }
                pos = end + 2;
            } else {
                return;
            }
        }
    }
}
