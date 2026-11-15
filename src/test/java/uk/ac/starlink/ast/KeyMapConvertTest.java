package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Translation of the unit tests in Rust {@code src/keymap/convert.rs}. */
class KeyMapConvertTest {

    private static final int I = KeyMap.AST__INTTYPE;
    private static final int S = KeyMap.AST__SINTTYPE;
    private static final int K = KeyMap.AST__KINTTYPE;
    private static final int B = KeyMap.AST__BYTETYPE;
    private static final int F = KeyMap.AST__FLOATTYPE;
    private static final int D = KeyMap.AST__DOUBLETYPE;
    private static final int C = KeyMap.AST__STRINGTYPE;
    private static final int U = KeyMap.AST__UNDEFTYPE;

    private static KeyMapConvert.Raw conv(int type, Object value, int to) {
        return KeyMapConvert.convert(new KeyMapConvert.Raw(type, value), to);
    }

    /** Asserts that converting gives {@code want} of type {@code wantType}. */
    private static void assertConv(int type, Object value, int to, int wantType, Object want) {
        KeyMapConvert.Raw got = conv(type, value, to);
        assertNotNull(got, value + " to " + to);
        assertEquals(wantType, got.type);
        assertEquals(want, got.value, value + " to " + to);
    }

    @Test
    void objectConvertsOnlyToObject() {
        KeyMapConvert.Raw obj = new KeyMapConvert.Raw(KeyMap.AST__OBJECTTYPE, new UnitMap(1));
        KeyMapConvert.Raw same = KeyMapConvert.convert(obj, KeyMap.AST__OBJECTTYPE);
        assertNotNull(same);
        assertEquals(KeyMap.AST__OBJECTTYPE, same.type);
        for (int to : new int[] {I, S, K, B, F, D, C, U}) {
            assertNull(KeyMapConvert.convert(obj, to), "type " + to);
        }
        // Nothing converts to an Object either.
        assertNull(conv(I, 1, KeyMap.AST__OBJECTTYPE));
        assertNull(conv(C, "x", KeyMap.AST__OBJECTTYPE));
    }

    @Test
    void undefConvertsOnlyToUndef() {
        KeyMapConvert.Raw u = conv(U, null, U);
        assertNotNull(u);
        assertEquals(U, u.type);
        assertNull(conv(U, null, I));
        assertNull(conv(I, 1, U));
    }

    @Test
    void numericNarrowingSaturates() {
        // keymap.c DtoI / DtoS / DtoB / DtoK clamp at the target's limits.
        assertConv(I, 1000, B, B, 255);
        assertConv(I, -1, B, B, 0);
        assertConv(I, 70000, S, S, Short.MAX_VALUE);
        assertConv(K, (long) Integer.MAX_VALUE + 1, I, I, Integer.MAX_VALUE);
        // Widening is exact.
        assertConv(S, (short) -5, K, K, -5L);
        assertConv(B, 255, I, I, 255);
    }

    @Test
    void floatToIntegerRoundsToNearest() {
        // C rounds with round(), halves away from zero (keymap.c, upstream 7b084bbf).
        assertConv(D, 2.5, I, I, 3);
        assertConv(D, 2.4, I, I, 2);
        assertConv(D, -2.5, I, I, -3);
        assertConv(D, -2.6, I, I, -3);
        assertConv(F, 2.5f, K, K, 3L);
    }

    @Test
    void doubleToStringDropsRoundingNoise() {
        assertEquals("1", KeyMapConvert.doubleToString(1.0));
        assertEquals("-1.5", KeyMapConvert.doubleToString(-1.5));
        assertEquals("0", KeyMapConvert.doubleToString(0.0));
        // "0.10000000000000001" is 16 characters longer than "0.1", over C's threshold.
        assertEquals("0.1", KeyMapConvert.doubleToString(0.1));
        assertEquals(KeyMapConvert.BAD_STRING, KeyMapConvert.doubleToString(AstObject.AST__BAD));
    }

    @Test
    void doubleToStringKeepsTheSignOfNegativeZero() {
        // ConvertValue's double-to-string arm has no "-0" fixup, unlike WriteDouble.
        assertEquals("-0", KeyMapConvert.doubleToString(-0.0));
        assertEquals("0", KeyMapConvert.doubleToString(0.0));
    }

    @Test
    void floatToStringUsesSixSignificantDigits() {
        assertEquals("1", KeyMapConvert.floatToString(1.0f));
        assertEquals("1.5", KeyMapConvert.floatToString(1.5f));
        assertEquals("0.333333", KeyMapConvert.floatToString(1.0f / 3.0f));
    }

    @Test
    void floatToStringKeepsTheSignOfNegativeZero() {
        assertEquals("-0", KeyMapConvert.floatToString(-0.0f));
        assertEquals("0", KeyMapConvert.floatToString(0.0f));
    }

    @Test
    void intToStringIsPlainDecimal() {
        assertConv(I, -42, C, C, "-42");
        assertConv(K, Long.MIN_VALUE, C, C, Long.toString(Long.MIN_VALUE));
        assertConv(B, 7, C, C, "7");
        assertConv(S, (short) -7, C, C, "-7");
    }

    @Test
    void stringToNumericTriesIntThenDouble() {
        assertConv(C, "42", I, I, 42);
        // Leading and trailing white space is skipped by the scan.
        assertConv(C, "  42 ", I, I, 42);
        // Not an integer, but a valid double: the fallback rounds it.
        assertConv(C, "2.6", I, I, 3);
        // Trailing junk means the scan did not consume the string.
        assertNull(conv(C, "42x", I));
        assertNull(conv(C, "", I));
        assertNull(conv(C, "hello", D));
        assertConv(C, "1.5", D, D, 1.5);
    }

    @Test
    void stringToNumericAcceptsC99HexNotation() {
        // "%d" consumes only the "0" of "0x10", so the "%lf" fallback reads the hex value.
        assertConv(C, "0x10", I, I, 16);
        assertConv(C, "0x10", D, D, 16.0);
        assertConv(C, "0x1p4", D, D, 16.0);
        assertConv(C, "0x1.8p2", D, D, 6.0);
        assertConv(C, "-0x10", D, D, -16.0);
        assertConv(C, "0x1p4", F, F, 16.0f);
    }

    @Test
    void stringToDoubleRejectsHexExponentOnBareDotMantissa() {
        // glibc's sscanf("%lf") never attaches a "p" exponent to a mantissa with no hex digits.
        assertNull(conv(C, "0x.p9", D));
        assertConv(C, "0x1.p9", D, D, 512.0);
        assertConv(C, "0x.5p9", D, D, 160.0);
    }

    @Test
    void stringToNumericKeepsCurrentlyCorrectBehaviours() {
        assertConv(C, "+5", I, I, 5);
        assertConv(C, "1e5", I, I, 100_000);
        assertConv(C, "2.6", I, I, 3);
        assertConv(C, "-2.5", I, I, -3);
        assertConv(C, " 42 ", I, I, 42);
        assertNull(conv(C, KeyMapConvert.BAD_STRING, I));
        assertNull(conv(C, "  <bad> ", I));
        assertConv(C, "inf", D, D, Double.POSITIVE_INFINITY);
        KeyMapConvert.Raw nan = conv(C, "nan", D);
        assertNotNull(nan);
        assertTrue(Double.isNaN((Double) nan.value));
        assertNull(conv(C, "1 2", I));
        assertNull(conv(C, "12abc", I));
        assertNull(conv(C, "   ", I));
        assertNull(conv(C, "", I));
    }

    @Test
    void stringToDoubleAcceptsTheBadMarker() {
        assertConv(C, KeyMapConvert.BAD_STRING, D, D, AstObject.AST__BAD);
        // Only on the double path.
        assertNull(conv(C, KeyMapConvert.BAD_STRING, F));
        assertNull(conv(C, KeyMapConvert.BAD_STRING, I));
    }

    @Test
    void stringToFloatHasNoIntegerFallback() {
        // C scans "%f" only on this path, which still accepts "42".
        assertConv(C, "42", F, F, 42.0f);
        assertNull(conv(C, "42x", F));
    }

    @Test
    void stringToStringIsTheIdentity() {
        assertConv(C, "Hello", C, C, "Hello");
    }

    @Test
    void elementAtIndexesScalarsAndVectorsZeroBased() {
        KeyMapEntry s = KeyMapEntry.scalar(I, 7, null);
        assertEquals(7, KeyMapConvert.elementAt(s, 0).value);
        assertNull(KeyMapConvert.elementAt(s, 1));

        KeyMapEntry v = KeyMapEntry.vector(D, Arrays.<Object>asList(1.0, 2.0, 3.0), null);
        assertEquals(1.0, KeyMapConvert.elementAt(v, 0).value);
        assertEquals(3.0, KeyMapConvert.elementAt(v, 2).value);
        assertNull(KeyMapConvert.elementAt(v, 3));

        // An undefined entry gives AST__UNDEFTYPE at index 0.
        KeyMapConvert.Raw u = KeyMapConvert.elementAt(KeyMapEntry.undefined(null), 0);
        assertNotNull(u);
        assertEquals(U, u.type);
    }

    @Test
    void doubleToNarrowIntSaturatesAndDefinesNan() {
        // keymap.c, upstream 6d114495 and 4e5c33dc: saturate, and NaN gives zero.
        double d = 5_000_000_100.0;
        assertConv(D, d, I, I, Integer.MAX_VALUE);
        assertConv(D, d, S, S, Short.MAX_VALUE);
        assertConv(D, d, B, B, 255);
        assertConv(D, d, K, K, 5_000_000_100L);

        assertConv(D, -d, I, I, Integer.MIN_VALUE);
        assertConv(D, -d, B, B, 0);

        assertConv(D, 1.0e30, K, K, Long.MAX_VALUE);
        assertConv(D, -1.0e30, K, K, Long.MIN_VALUE);

        assertConv(D, Double.NaN, I, I, 0);
        assertConv(D, Double.NaN, S, S, (short) 0);
        assertConv(D, Double.NaN, K, K, 0L);
        assertConv(D, Double.NaN, B, B, 0);
    }

    @Test
    void stringToIntOverflowSaturates() {
        // keymap.c, upstream a76964c7: an overflowing "%d" scan falls back to "%lf", which
        // saturates.
        assertConv(C, "9999999999", I, I, Integer.MAX_VALUE);
        char[] nines = new char[38];
        Arrays.fill(nines, '9');
        assertConv(C, new String(nines), I, I, Integer.MAX_VALUE);
    }
}
