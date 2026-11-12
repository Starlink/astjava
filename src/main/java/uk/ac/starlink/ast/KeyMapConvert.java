package uk.ac.starlink.ast;

/**
 * C's {@code ConvertValue} (keymap.c): the conversions between a KeyMap's data types, which every
 * read and every element store goes through.
 *
 * <p>Ported from Rust {@code keymap/convert.rs}.
 */
final class KeyMapConvert {

    /** {@code AST__BAD_STRING}: how {@code AST__BAD} formats, and the string read as it. */
    static final String BAD_STRING = "<bad>";

    /** {@code AST__DBL_DIG}: the significant digits of a full-precision double. */
    private static final int DBL_DIG = 17;

    /** {@code FLT_DIG}: the significant digits used for a float. */
    private static final int FLT_DIG = 6;

    /** 2^63, the bound of a 64-bit integer as a double. */
    private static final double TWO_POW_63 = 0x1p63;

    private KeyMapConvert() {}

    /** One value with its data type: C's {@code void *raw} with its {@code raw_type}. */
    // MODERN-JAVA(21): record
    static final class Raw {
        final int type;
        final Object value;

        Raw(int type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    /**
     * A double as {@code ConvertValue} formats it (keymap.c): {@code "<bad>"} for AST__BAD,
     * otherwise {@code %.*g} with 17 significant digits, or 14 when that is at least ten characters
     * shorter. C's comment explains the choice: "If reducing the number of decimal places by three
     * produces a saving of 10 or more characters, assume the least significant two characters are
     * rounding error." This heuristic is C's own. There is no negative-zero fixup on this path.
     */
    static String doubleToString(double value) {
        if (value == AstObject.AST__BAD) {
            return BAD_STRING;
        }
        String reduced = CFormat.format("%." + (DBL_DIG - 3) + "g", value);
        String full = CFormat.format("%." + DBL_DIG + "g", value);
        return full.length() - reduced.length() > 9 ? reduced : full;
    }

    /** A float as {@code ConvertValue} formats it: {@code %.*g} with 6 significant digits. */
    static String floatToString(float value) {
        return CFormat.format("%." + FLT_DIG + "g", (double) value);
    }

    /**
     * A double rounded to the nearest integer and clamped to {@code [min, max]}, with NaN giving
     * zero (keymap.c {@code DtoI} and friends).
     */
    private static double dToInt(double d, double min, double max) {
        if (Double.isNaN(d)) {
            return 0.0;
        }
        double r = roundHalfAway(d);
        if (r >= max) {
            return max;
        }
        if (r <= min) {
            return min;
        }
        return r;
    }

    /** C's {@code round}: to the nearest integer, halves away from zero. */
    private static double roundHalfAway(double d) {
        if (Double.isInfinite(d)) {
            return d;
        }
        double t = d < 0 ? Math.ceil(d) : Math.floor(d);
        double frac = Math.abs(d - t);
        if (frac >= 0.5) {
            t += d < 0 ? -1.0 : 1.0;
        }
        return t;
    }

    static int dToI32(double d) {
        return (int) dToInt(d, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    static short dToI16(double d) {
        return (short) dToInt(d, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    static int dToU8(double d) {
        return (int) dToInt(d, 0.0, 255.0);
    }

    static long dToI64(double d) {
        if (Double.isNaN(d)) {
            return 0;
        }
        double r = roundHalfAway(d);
        if (r >= TWO_POW_63) {
            return Long.MAX_VALUE;
        }
        if (r <= -TWO_POW_63) {
            return Long.MIN_VALUE;
        }
        return (long) r;
    }

    /** C's {@code sscanf(s, " %d %n")}, falling back to {@code " %lf %n"}, as a double. */
    private static Double scanIntOrDouble(String s) {
        Long l = CScan.scanLong(s);
        if (l == null) {
            return CScan.scanDouble(s);
        }
        double d = l;
        return d;
    }

    /**
     * Converts {@code raw} to {@code outType}, or gives null when C's {@code ConvertValue} cannot
     * (it returns 0 and raises no error; the caller decides whether that is {@code AST__MPGER}).
     */
    static Raw convert(Raw raw, int outType) {
        // UNDEF pairs only with UNDEF.
        if (raw.type == KeyMap.AST__UNDEFTYPE || outType == KeyMap.AST__UNDEFTYPE) {
            return raw.type == outType ? raw : null;
        }
        // An Object converts only to an Object, and nothing else converts to one.
        if (raw.type == KeyMap.AST__OBJECTTYPE || outType == KeyMap.AST__OBJECTTYPE) {
            return raw.type == outType ? raw : null;
        }
        if (raw.type == KeyMap.AST__STRINGTYPE) {
            return fromString((String) raw.value, outType);
        }
        boolean isFloat;
        long asLong = 0;
        double asDouble;
        switch (raw.type) {
            case KeyMap.AST__INTTYPE:
            case KeyMap.AST__BYTETYPE:
                asLong = (Integer) raw.value;
                asDouble = asLong;
                isFloat = false;
                break;
            case KeyMap.AST__SINTTYPE:
                asLong = (Short) raw.value;
                asDouble = asLong;
                isFloat = false;
                break;
            case KeyMap.AST__KINTTYPE:
                asLong = (Long) raw.value;
                asDouble = asLong;
                isFloat = false;
                break;
            case KeyMap.AST__FLOATTYPE:
                asDouble = (Float) raw.value;
                isFloat = true;
                break;
            case KeyMap.AST__DOUBLETYPE:
                asDouble = (Double) raw.value;
                isFloat = true;
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "KeyMap: cannot convert data type " + raw.type + ".");
        }
        switch (outType) {
            case KeyMap.AST__INTTYPE:
                return new Raw(outType, dToI32(asDouble));
            case KeyMap.AST__SINTTYPE:
                return new Raw(outType, dToI16(asDouble));
            case KeyMap.AST__KINTTYPE:
                return new Raw(outType, isFloat ? dToI64(asDouble) : asLong);
            case KeyMap.AST__BYTETYPE:
                return new Raw(outType, dToU8(asDouble));
            case KeyMap.AST__FLOATTYPE:
                return new Raw(outType, (float) asDouble);
            case KeyMap.AST__DOUBLETYPE:
                return new Raw(outType, asDouble);
            case KeyMap.AST__STRINGTYPE:
                if (raw.type == KeyMap.AST__FLOATTYPE) {
                    return new Raw(outType, floatToString((Float) raw.value));
                } else if (raw.type == KeyMap.AST__DOUBLETYPE) {
                    return new Raw(outType, doubleToString((Double) raw.value));
                } else {
                    return new Raw(outType, Long.toString(asLong));
                }
            default:
                throw Ast.err(AstException.AST__INTER, "KeyMap: cannot convert to data type " + outType + ".");
        }
    }

    /** {@code ConvertValue} from a string: the whole string must be read as the number. */
    private static Raw fromString(String s, int outType) {
        Double d;
        switch (outType) {
            case KeyMap.AST__STRINGTYPE:
                return new Raw(outType, s);
            case KeyMap.AST__DOUBLETYPE:
                if (BAD_STRING.equals(NativeReader.trim(s))) {
                    return new Raw(outType, AstObject.AST__BAD);
                }
                d = CScan.scanDouble(s);
                return d == null ? null : new Raw(outType, d);
            case KeyMap.AST__FLOATTYPE:
                // C scans "%f" only: no integer first, and no AST__BAD_STRING.
                Float f = CScan.scanFloat(s);
                return f == null ? null : new Raw(outType, f);
            case KeyMap.AST__INTTYPE:
                d = scanIntOrDouble(s);
                return d == null ? null : new Raw(outType, dToI32(d));
            case KeyMap.AST__SINTTYPE:
                d = scanIntOrDouble(s);
                return d == null ? null : new Raw(outType, dToI16(d));
            case KeyMap.AST__BYTETYPE:
                d = scanIntOrDouble(s);
                return d == null ? null : new Raw(outType, dToU8(d));
            case KeyMap.AST__KINTTYPE:
                Long l = CScan.scanLong(s);
                if (l != null) {
                    return new Raw(outType, l);
                }
                d = CScan.scanDouble(s);
                return d == null ? null : new Raw(outType, dToI64(d));
            default:
                throw Ast.err(AstException.AST__INTER, "KeyMap: cannot convert to data type " + outType + ".");
        }
    }

    /** Element {@code index} (zero-based) of an entry, or null if there is none. */
    static Raw elementAt(KeyMapEntry entry, int index) {
        if (!entry.defined()) {
            // An undefined entry has no values, but C still lets index 0 through.
            return index == 0 ? new Raw(KeyMap.AST__UNDEFTYPE, null) : null;
        }
        if (index < 0 || index >= entry.values.size()) {
            return null;
        }
        return new Raw(entry.type, entry.values.get(index));
    }
}
