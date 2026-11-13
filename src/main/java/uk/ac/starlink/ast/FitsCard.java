package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A single FITS header card: keyword, typed value and comment, with the flags a FitsChan keeps for
 * it. Parsing follows C's {@code Split} and formatting C's {@code FormatCard} and {@code
 * EncodeValue} (fitschan.c), for the 80-character card format of the FITS standard.
 *
 * <p>Ported from Rust {@code fitschan/card.rs}.
 */
final class FitsCard {

    /** The length of a header card. */
    static final int FITS_CARD_LEN = 80;

    /** The length of the keyword name field, columns 1 to 8. */
    static final int FITS_NAME_LEN = 8;

    /** The column (1-based) in which a comment starts when it fits, C's {@code FITSCOMCOL}. */
    static final int FITS_COM_COL = 32;

    /** The column (0-based) in which right-justified numbers and logical values end. */
    private static final int FITS_RL_COL = 29;

    /** The width of the imaginary part of a complex value, C's {@code FITSIMCOL - FITSRLCOL}. */
    private static final int IMAG_WIDTH = 20;

    /** The column (1-based) in which a quoted string ends at the earliest, C's {@code FITSSTCOL}. */
    private static final int FITS_ST_COL = 20;

    /**
     * The number of decimal digits in {@code INT_MAX}, C's {@code int_dig} in {@code Split}: an
     * integer with this many digits or more is held as a 64-bit integer.
     */
    private static final int INT_DIG = 10;

    /** The kinds of value a card holds. */
    enum Kind {
        /** An integer whose value fits an {@code int}. */
        INT,
        /** An integer held as a {@code long}. */
        KINT,
        /** A floating point value. */
        FLOAT,
        /** A string. */
        STRING,
        /** A logical value. */
        LOGICAL,
        /** A complex floating point value. */
        COMPLEX_FLOAT,
        /** A complex integer value. */
        COMPLEX_INT,
        /** A commentary card (COMMENT, HISTORY, a blank keyword, or no '=' in column 9). */
        COMMENT,
        /** A keyword with no value. */
        UNDEF,
        /** A {@code CONTINUE} card continuing a long string. */
        CONTINUE
    }

    /**
     * The data type of a card's value as the CardType attribute gives it, with C's codes {@code
     * AST__NOTYPE} (-1) to {@code AST__KINT} (9).
     */
    enum CardType {
        /** No current card: the FitsChan is at end-of-file. */
        NO_TYPE(-1),
        /** A commentary card. */
        COMMENT(0),
        /** An integer that fits an {@code int}. */
        INT(1),
        /** A floating value. */
        FLOAT(2),
        /** A string. */
        STRING(3),
        /** A complex floating value. */
        COMPLEXF(4),
        /** A complex integer value. */
        COMPLEXI(5),
        /** A logical value. */
        LOGICAL(6),
        /** A {@code CONTINUE} card. */
        CONTINUE(7),
        /** A keyword with no value. */
        UNDEF(8),
        /** An integer held as a 64-bit integer. */
        KINT(9);

        /** The names a dump gives the types, indexed by code (fitschan.c {@code type_names}). */
        private static final String[] TYPE_NAMES = {
            "comment",
            "integer",
            "floating point",
            "string",
            "complex floating point",
            "complex integer",
            "logical",
            "continuation string",
            "undef",
            "64 bit integer",
        };

        private final int cCode;

        CardType(int cCode) {
            this.cCode = cCode;
        }

        /** C's integer code for the type. */
        int code() {
            return cCode;
        }

        /** The name a dump gives the type, or null for {@link #NO_TYPE}. */
        String dumpName() {
            return cCode < 0 ? null : TYPE_NAMES[cCode];
        }

        /** The type a dump names, ignoring case, as C's {@code FindString} matches it; or null. */
        static CardType fromDumpName(String name) {
            for (int i = 0; i < TYPE_NAMES.length; i++) {
                if (TYPE_NAMES[i].equalsIgnoreCase(name)) {
                    return values()[i + 1];
                }
            }
            return null;
        }
    }

    /** A typed card value. */
    static final class Value {
        /** The undefined value of a keyword with no value. */
        static final Value UNDEF = new Value(Kind.UNDEF, 0, 0, 0, 0, null, false);

        final Kind kind;

        /** An integer value, or the real part of a complex integer. */
        final long i;

        /** The imaginary part of a complex integer. */
        final long iIm;

        /** A floating value, or the real part of a complex floating value. */
        final double d;

        /** The imaginary part of a complex floating value. */
        final double dIm;

        /** A string, continuation string or commentary text. */
        final String s;

        /** A logical value. */
        final boolean b;

        private Value(Kind kind, long i, long iIm, double d, double dIm, String s, boolean b) {
            this.kind = kind;
            this.i = i;
            this.iIm = iIm;
            this.d = d;
            this.dIm = dIm;
            this.s = s;
            this.b = b;
        }

        static Value ofInt(long v) {
            return new Value(Kind.INT, v, 0, 0, 0, null, false);
        }

        static Value ofKInt(long v) {
            return new Value(Kind.KINT, v, 0, 0, 0, null, false);
        }

        static Value ofFloat(double v) {
            return new Value(Kind.FLOAT, 0, 0, v, 0, null, false);
        }

        static Value ofString(String v) {
            return new Value(Kind.STRING, 0, 0, 0, 0, v, false);
        }

        static Value ofContinue(String v) {
            return new Value(Kind.CONTINUE, 0, 0, 0, 0, v, false);
        }

        static Value ofComment(String v) {
            return new Value(Kind.COMMENT, 0, 0, 0, 0, v, false);
        }

        static Value ofLogical(boolean v) {
            return new Value(Kind.LOGICAL, 0, 0, 0, 0, null, v);
        }

        static Value ofComplexFloat(double re, double im) {
            return new Value(Kind.COMPLEX_FLOAT, 0, 0, re, im, null, false);
        }

        static Value ofComplexInt(long re, long im) {
            return new Value(Kind.COMPLEX_INT, re, im, 0, 0, null, false);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Value)) {
                return false;
            }
            Value v = (Value) o;
            return kind == v.kind
                    && i == v.i
                    && iIm == v.iIm
                    && Double.doubleToLongBits(d) == Double.doubleToLongBits(v.d)
                    && Double.doubleToLongBits(dIm) == Double.doubleToLongBits(v.dIm)
                    && Objects.equals(s, v.s)
                    && b == v.b;
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, i, iIm, d, dIm, s, b);
        }

        @Override
        public String toString() {
            return kind + "(" + i + "," + iIm + "," + d + "," + dIm + "," + s + "," + b + ")";
        }
    }

    /**
     * How a read has used a card, C's {@code USED} and {@code PROVISIONALLY_USED} flags: a card
     * consumed by a read is treated as no longer existing, but stays in the list to position cards
     * written later.
     */
    enum Consumption {
        /** Not consumed. */
        FREE,
        /** Used by the read in progress. */
        PROVISIONAL,
        /** Consumed by a completed read. */
        USED
    }

    /** A card's flags, C's bit mask (fitschan.c): consumption, protection and the "new" marks. */
    static final class Flags {
        /** How a read has used the card. */
        Consumption consumption = Consumption.FREE;

        /** Set by {@code astRetainFits}: the card is never consumed. */
        boolean retained;

        /** Added during the current write, C's {@code NEW1}. */
        boolean newWrite;

        /** Added during the current axis-description pass of a write, C's {@code NEW2}. */
        boolean newVersion;

        Flags copy() {
            Flags f = new Flags();
            f.consumption = consumption;
            f.retained = retained;
            f.newWrite = newWrite;
            f.newVersion = newVersion;
            return f;
        }

        /** The flags as C's bit mask: USED 1, PROVISIONALLY_USED 2, NEW1 4, NEW2 8, PROTECTED 16. */
        long cBits() {
            long bits;
            switch (consumption) {
                case FREE:
                    bits = 0;
                    break;
                case USED:
                    bits = 1;
                    break;
                case PROVISIONAL:
                    bits = 2;
                    break;
                default:
                    throw Ast.err(AstException.AST__INTER, "FitsCard: unexpected consumption " + consumption);
            }
            return bits | (newWrite ? 4 : 0) | (newVersion ? 8 : 0) | (retained ? 16 : 0);
        }

        /** The flags C's bit mask holds; a card both used and provisionally used is used. */
        static Flags fromCBits(long bits) {
            Flags f = new Flags();
            if ((bits & 1) != 0) {
                f.consumption = Consumption.USED;
            } else if ((bits & 2) != 0) {
                f.consumption = Consumption.PROVISIONAL;
            } else {
                f.consumption = Consumption.FREE;
            }
            f.newWrite = (bits & 4) != 0;
            f.newVersion = (bits & 8) != 0;
            f.retained = (bits & 16) != 0;
            return f;
        }
    }

    /** A card and whether its value was one whose type could not be determined. */
    static final class Split {
        final FitsCard card;

        /** Whether C's {@code Split} reports a {@code badkeyvalue} warning for the card. */
        final boolean badValue;

        Split(FitsCard card, boolean badValue) {
            this.card = card;
            this.badValue = badValue;
        }
    }

    /** The keyword: at most 8 characters, upper case, without trailing spaces; empty if blank. */
    String keyword;

    /** The value. */
    Value value;

    /** The comment after the value, or null if there is none. */
    String comment;

    /** The flags. */
    Flags flags = new Flags();

    FitsCard(String keyword, Value value, String comment) {
        this.keyword = keyword;
        this.value = value;
        this.comment = comment;
    }

    /** A copy of the card, with its own flags. */
    FitsCard copy() {
        FitsCard c = new FitsCard(keyword, value, comment);
        c.flags = flags.copy();
        return c;
    }

    // MODERN-JAVA(21): a switch expression over a sealed Value hierarchy.
    /** The card's data type, as the CardType attribute gives it for the current card. */
    CardType cardType() {
        switch (value.kind) {
            case INT:
                return CardType.INT;
            case KINT:
                return CardType.KINT;
            case FLOAT:
                return CardType.FLOAT;
            case STRING:
                return CardType.STRING;
            case LOGICAL:
                return CardType.LOGICAL;
            case COMPLEX_FLOAT:
                return CardType.COMPLEXF;
            case COMPLEX_INT:
                return CardType.COMPLEXI;
            case COMMENT:
                return CardType.COMMENT;
            case UNDEF:
                return CardType.UNDEF;
            case CONTINUE:
                return CardType.CONTINUE;
            default:
                throw Ast.err(AstException.AST__INTER, "FitsCard: unexpected value kind " + value.kind);
        }
    }

    /** The keyword as C's {@code CardName} gives it: 8 spaces for a blank keyword. */
    String cName() {
        return keyword.isEmpty() ? "        " : keyword;
    }

    /**
     * The comment as C's {@code CardComm} gives it: for a commentary card the text from column 9,
     * leading and trailing blanks included, as C's {@code NewCard} keeps them; otherwise the
     * comment after the value. Null when there is none.
     */
    String cComment() {
        if (value.kind == Kind.COMMENT) {
            return isBlank(value.s) ? null : value.s;
        }
        return comment == null || comment.isEmpty() ? null : comment;
    }

    /** Whether a completed read has consumed the card, so it is excluded from output. */
    boolean isConsumed() {
        return flags.consumption == Consumption.USED;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Whether {@code c} may appear in a keyword name: a letter, digit, '-' or '_' (C's {@code isFits}). */
    private static boolean isFitsNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    /** Whether {@code c} is a printing ASCII character other than a space. */
    private static boolean isGraphic(char c) {
        return c > ' ' && c < 127;
    }

    /** Whether {@code s} is empty or all white space. */
    static boolean isBlank(String s) {
        for (int k = 0; k < s.length(); k++) {
            if (!CScan.isCSpace(s.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    /** {@code s} without trailing white space. */
    static String trimEnd(String s) {
        int n = s.length();
        while (n > 0 && CScan.isCSpace(s.charAt(n - 1))) {
            n--;
        }
        return s.substring(0, n);
    }

    /** {@code s} without leading or trailing white space. */
    static String trim(String s) {
        int a = 0;
        while (a < s.length() && CScan.isCSpace(s.charAt(a))) {
            a++;
        }
        return trimEnd(s.substring(a));
    }

    /** C's {@code ChrLen}: the length of {@code s} without trailing blanks and non-printing characters. */
    static int chrLen(String s) {
        int n = s.length();
        while (n > 0 && !isGraphic(s.charAt(n - 1))) {
            n--;
        }
        return n;
    }

    /**
     * Validates the keyword name of a card (its first 8 columns, in their own case), giving the
     * text of the {@code badkeyname} warning C's {@code CheckFitsName} gives for an illegal
     * character, or null for a legal or blank name. An '=' is an error in C, not a warning, so it
     * gives null here; so does a space, for which C's chain has no branch (fitschan.c:6072-6095).
     */
    static String checkFitsName(String rawName) {
        String name = rawName.length() >= FITS_NAME_LEN ? rawName.substring(0, FITS_NAME_LEN) : rawName;
        String trimmed = trimEnd(name);
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int k = 0; k < trimmed.length(); k++) {
            char c = trimmed.charAt(k);
            if (!isFitsNameChar(c)) {
                if (c == '=') {
                    return null;
                }
                if (c < ' ') {
                    return "The FITS keyword name ('" + trimmed
                            + "') contains an illegal non-printing character (ascii value " + (int) c + ").";
                }
                if (c > ' ') {
                    return "The FITS keyword name ('" + trimmed + "') contains an illegal character ('" + c + "').";
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Breaks warning text into lines of about 60 characters at word boundaries, as C's {@code
     * Warn} (fitschan.c:35129-35223) does; each line is the value of one {@code ASTWARN} card.
     */
    static List<String> wrapWarningText(String text) {
        List<String> lines = new ArrayList<>();
        int b = text.length();
        int a = 0;
        while (a < b) {
            int c = a + 60;
            if (c >= b) {
                c = b - 1;
            } else {
                while (c > a && !isAsciiWhitespace(text.charAt(c))) {
                    c--;
                }
            }
            lines.add(text.substring(a, c + 1));
            a = c + 1;
        }
        return lines;
    }

    /** White space as Rust's {@code is_ascii_whitespace}: C's {@code isspace} without the vertical tab. */
    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    /** The text of the {@code badkeyvalue} warning C's {@code Split} gives for {@code card}. */
    static String badValueWarning(String card) {
        int nul = card.indexOf('\0');
        String c = nul < 0 ? card : card.substring(0, nul);
        if (c.length() > FITS_CARD_LEN) {
            c = c.substring(0, FITS_CARD_LEN);
        }
        return "The keyword value is illegal in '" + c + "'";
    }

    /**
     * Parses a card. A card shorter than 80 characters is padded with spaces, a longer one cut at
     * 80, and non-printing characters that end it are ignored. A value whose type cannot be
     * determined gives a commentary card holding just the keyword, as a FitsChan stores it.
     *
     * @throws AstException with status {@code AST__BDFTS} if the keyword name holds an '='
     */
    static FitsCard parse(String card) {
        return split(card).card;
    }

    /**
     * C's {@code Split}: the card {@link #parse} gives, and whether its value was one whose type
     * could not be determined.
     *
     * @throws AstException with status {@code AST__BDFTS} if the keyword name holds an '=' before
     *     any other illegal character
     */
    static Split split(String input) {
        return split(input, "astPutFits");
    }

    /**
     * {@link #split}, naming {@code method} in the error for an '=' in the keyword name, as C's
     * {@code CheckFitsName} names the method that called it.
     */
    static Split split(String input, String method) {
        String truncated = input.length() > FITS_CARD_LEN ? input.substring(0, FITS_CARD_LEN) : input;
        int nc = truncated.length();
        while (nc > 0 && !isGraphic(truncated.charAt(nc - 1)) && truncated.charAt(nc - 1) != ' ') {
            nc--;
        }
        StringBuilder sb = new StringBuilder(FITS_CARD_LEN).append(truncated, 0, nc);
        while (sb.length() < FITS_CARD_LEN) {
            sb.append(' ');
        }
        String card = sb.toString();

        String rawName = trimEnd(card.substring(0, FITS_NAME_LEN));
        String keyword = rawName.toUpperCase(Locale.ROOT);
        boolean blankName = keyword.isEmpty();
        // C's CheckFitsName stops at the first character that is not legal, and only an '=' is an
        // error.
        for (int k = 0; k < rawName.length(); k++) {
            char c = rawName.charAt(k);
            if (!isFitsNameChar(c)) {
                if (c == '=') {
                    throw Ast.err(
                            AstException.AST__BDFTS,
                            method + "(FitsChan): An equals sign ('=') was found before column 9 within a FITS"
                                    + " keyword name or header card.");
                }
                break;
            }
        }

        boolean isContinue = "CONTINUE".equals(keyword)
                && nc > FITS_NAME_LEN + 3
                && card.charAt(FITS_NAME_LEN) == ' '
                && card.charAt(FITS_NAME_LEN + 2) == '\'';
        boolean commentary = !isContinue
                && (blankName
                        || "COMMENT".equals(keyword)
                        || "HISTORY".equals(keyword)
                        || card.charAt(FITS_NAME_LEN) != '=');
        if (commentary) {
            // C copies the characters given after column 8, not the padding.
            String text = nc > FITS_NAME_LEN ? card.substring(FITS_NAME_LEN, nc) : "";
            return new Split(new FitsCard(blankName ? "" : keyword, Value.ofComment(text), null), false);
        }

        int valueStart = isContinue ? FITS_NAME_LEN + 2 : FITS_NAME_LEN + 1;
        String valueArea = card.substring(valueStart);
        int pos = 0;
        while (pos < valueArea.length() && isAsciiWhitespace(valueArea.charAt(pos))) {
            pos++;
        }
        if (pos < valueArea.length() && valueArea.charAt(pos) == '\'') {
            // The string, and any comment after it, end at character nc, not at the padding.
            String area = card.substring(valueStart, Math.max(nc, valueStart + pos + 1));
            return new Split(parseStringValue(keyword, area, pos, isContinue), false);
        }

        // Other values end at the first '/', which starts the comment.
        int slash = valueArea.indexOf('/');
        String valStr = slash < 0 ? valueArea : valueArea.substring(0, slash);
        String commentStr = slash < 0 ? null : valueArea.substring(slash + 1);
        Value value = splitValue(valStr);
        if (value == null) {
            // C stores a value it cannot type as a commentary card holding just the keyword.
            return new Split(new FitsCard(keyword, Value.ofComment(""), null), true);
        }
        return new Split(new FitsCard(keyword, value, trimComment(commentStr)), false);
    }

    /** Parses a string value starting with the quote at {@code quotePos} of {@code area}. */
    private static FitsCard parseStringValue(String keyword, String area, int quotePos, boolean isContinue) {
        StringBuilder result = new StringBuilder();
        int k = quotePos + 1;
        boolean lastWasQuote = false;
        while (k < area.length()) {
            char ch = area.charAt(k);
            if (ch == '\'') {
                if (lastWasQuote) {
                    // Two adjacent quotes are one literal quote.
                    result.append('\'');
                    lastWasQuote = false;
                } else {
                    lastWasQuote = true;
                }
            } else {
                if (lastWasQuote) {
                    break;
                }
                result.append(ch);
            }
            k++;
        }
        // The value is kept as written, trailing spaces included, as C's Split stores it.
        String remainder = k < area.length() ? area.substring(k) : "";
        String com = null;
        int slash = remainder.indexOf('/');
        if (slash >= 0) {
            com = trimComment(remainder.substring(slash + 1));
        }
        String s = result.toString();
        return new FitsCard(keyword, isContinue ? Value.ofContinue(s) : Value.ofString(s), com);
    }

    /** The comment without surrounding white space, or null if blank or absent. */
    private static String trimComment(String c) {
        if (c == null) {
            return null;
        }
        String t = trim(c);
        return t.isEmpty() ? null : t;
    }

    /**
     * C's {@code Split} on the value of a card that is not a string: the value, or null when its
     * type cannot be determined.
     *
     * <p>Any case-insensitive abbreviation of YES, NO, TRUE or FALSE is a logical. A value with no
     * '.', 'E' or 'D' (either case) is read as two integers, then as one, as {@code sscanf} reads
     * {@code %d} and {@code %PRId64}; any other as two floating values, then one, as {@code %lf}
     * reads them, and failing that the same again with each 'D' replaced by 'e'. Each reading must
     * use every character but trailing blanks.
     */
    private static Value splitValue(String v) {
        int start = 0;
        while (start < v.length() && CScan.isCSpace(v.charAt(start))) {
            start++;
        }
        int ff = fullForm(new String[] {"YES", "NO", "TRUE", "FALSE"}, v.substring(start));
        if (ff == 0 || ff == 2) {
            return Value.ofLogical(true);
        } else if (ff == 1 || ff == 3) {
            return Value.ofLogical(false);
        }
        if (chrLen(v) == 0) {
            return Value.UNDEF;
        }
        boolean floating = false;
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '.' || c == 'E' || c == 'e' || c == 'D' || c == 'd') {
                floating = true;
                break;
            }
        }
        if (!floating) {
            long[] ri = FitsCardValue.scanAllInts(v, 2);
            if (ri != null) {
                // %d narrows each part to an int.
                int re = (int) ri[0];
                int im = (int) ri[1];
                return Value.ofComplexInt(re, im);
            }
            long[] one = FitsCardValue.scanAllInts(v, 1);
            if (one == null) {
                return null;
            }
            long k = one[0];
            int ndig = 0;
            for (int j = 0; j < v.length(); j++) {
                char c = v.charAt(j);
                if (c >= '0' && c <= '9') {
                    ndig++;
                }
            }
            // C keeps an int only when the value fits one and has fewer digits than INT_MAX.
            return k == (int) k && ndig < INT_DIG ? Value.ofInt(k) : Value.ofKInt(k);
        }
        Value f = floats(v);
        return f != null ? f : floats(v.replace('d', 'e').replace('D', 'e'));
    }

    private static Value floats(String v) {
        double[] ri = FitsCardValue.scanAllDoubles(v, 2);
        if (ri != null) {
            return Value.ofComplexFloat(ri[0], ri[1]);
        }
        double[] one = FitsCardValue.scanAllDoubles(v, 1);
        return one == null ? null : Value.ofFloat(one[0]);
    }

    /**
     * C's {@code FullForm} allowing abbreviations: the index of the option in {@code list} of
     * which {@code test}, ignoring trailing blanks and case, is an abbreviation, or -1 if there is
     * no such option or more than one.
     */
    private static int fullForm(String[] list, String test) {
        String t = test.substring(0, chrLen(test));
        int found = -1;
        for (int k = 0; k < list.length; k++) {
            String o = list[k];
            if (t.length() <= o.length() && o.substring(0, t.length()).equalsIgnoreCase(t)) {
                if (found >= 0) {
                    return -1;
                }
                found = k;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /**
     * The card as an 80-character header string, with 15 significant digits for a floating value.
     *
     * @throws AstException with status {@code AST__BDFTS} as {@link #formatWithDigitsAndRounding}
     */
    String format() {
        return formatWithDigitsAndRounding(15, 10);
    }

    /**
     * The card with {@code digits} significant digits for a floating value and the default
     * FitsRounding, 10.
     *
     * @throws AstException with status {@code AST__BDFTS} as {@link #formatWithDigitsAndRounding}
     */
    String formatWithDigits(int digits) {
        return formatWithDigitsAndRounding(digits, 10);
    }

    /**
     * C's {@code FormatCard}: the card as an 80-character header string, a floating value formatted
     * with the FitsDigits {@code digits} and the FitsRounding {@code fitsrnd}.
     *
     * @throws AstException with status {@code AST__BDFTS} if a string value cannot fit in the card,
     *     or a floating value cannot be encoded in it
     */
    // MODERN-JAVA(21): a switch expression over a sealed Value hierarchy.
    String formatWithDigitsAndRounding(int digits, int fitsrnd) {
        StringBuilder buf = new StringBuilder(FITS_CARD_LEN);
        buf.append(keyword);
        while (buf.length() < FITS_NAME_LEN) {
            buf.append(' ');
        }
        int width = FITS_RL_COL + 1 - (FITS_NAME_LEN + 2);
        switch (value.kind) {
            case COMMENT: {
                int room = FITS_CARD_LEN - buf.length();
                buf.append(value.s, 0, Math.min(value.s.length(), Math.max(room, 0)));
                break;
            }
            case CONTINUE:
                buf.append("  ");
                formatStringValue(buf, value.s);
                appendComment(buf, comment);
                break;
            case UNDEF:
                buf.append("= ");
                appendComment(buf, comment);
                break;
            case INT:
            case KINT:
                buf.append("= ");
                padLeft(buf, Long.toString(value.i), width);
                appendComment(buf, comment);
                break;
            case FLOAT: {
                buf.append("= ");
                String text = FitsFormat.encodeFloat(value.d, digits, fitsrnd);
                if (text == null) {
                    throw Ast.err(
                            AstException.AST__BDFTS,
                            "Cannot encode floating point value " + CFormat.format("%g", value.d)
                                    + " into a FITS header card for keyword '" + keyword + "'.");
                }
                buf.append(text);
                appendComment(buf, comment);
                break;
            }
            case LOGICAL:
                buf.append("= ");
                padLeft(buf, value.b ? "T" : "F", width);
                appendComment(buf, comment);
                break;
            case STRING:
                buf.append("= ");
                formatStringValue(buf, value.s);
                appendComment(buf, comment);
                break;
            case COMPLEX_FLOAT: {
                buf.append("= ");
                int room = FITS_CARD_LEN - (FITS_NAME_LEN + 3);
                String real = FitsFormat.encodeFloatIn(
                        value.d, digits, FitsFormat.VALUE_WIDTH, FitsFormat.VALUE_MAXWIDTH, fitsrnd);
                if (real == null || real.length() > room) {
                    throw cannotEncodeComplex("real");
                }
                String imag = FitsFormat.encodeFloatIn(
                        value.dIm, digits, IMAG_WIDTH, FitsFormat.VALUE_MAXWIDTH - 1 - real.length(), fitsrnd);
                if (imag == null) {
                    throw cannotEncodeComplex("imaginary");
                }
                buf.append(real).append(imag);
                appendComment(buf, comment);
                break;
            }
            case COMPLEX_INT:
                buf.append("= ");
                padLeft(buf, Long.toString(value.i), width);
                // C's %*d with width FITSIMCOL - FITSRLCOL + 1 (fitschan.c EncodeValue).
                padLeft(buf, Long.toString(value.iIm), 21);
                appendComment(buf, comment);
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "FitsCard: unexpected value kind " + value.kind);
        }
        while (buf.length() < FITS_CARD_LEN) {
            buf.append(' ');
        }
        buf.setLength(FITS_CARD_LEN);
        return buf.toString();
    }

    private AstException cannotEncodeComplex(String part) {
        return Ast.err(
                AstException.AST__BDFTS,
                "Cannot encode " + part + " part of a complex floating point value [" + CFormat.format("%g", value.d)
                        + "," + CFormat.format("%g", value.dIm) + "] into a FITS header card for keyword '" + keyword
                        + "'.");
    }

    private static void padLeft(StringBuilder buf, String text, int width) {
        for (int k = text.length(); k < width; k++) {
            buf.append(' ');
        }
        buf.append(text);
    }

    /**
     * C's {@code EncodeValue} for a string value starting in column 11: the string in quotes, each
     * quote doubled, padded to end in column 20 at least. Characters that will not fit in the card
     * are dropped if they are blanks.
     *
     * @throws AstException with status {@code AST__BDFTS} if any other character will not fit
     */
    private void formatStringValue(StringBuilder buf, String s) {
        int room = FITS_CARD_LEN - (FITS_NAME_LEN + 3);
        buf.append('\'');
        int len = 1;
        String rest = "";
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            boolean quote = ch == '\'';
            if (len + 1 + (quote ? 1 : 0) > room) {
                rest = s.substring(k);
                break;
            }
            buf.append(ch);
            len++;
            if (quote) {
                buf.append('\'');
                len++;
            }
        }
        while (len < FITS_ST_COL - (FITS_NAME_LEN + 3)) {
            buf.append(' ');
            len++;
        }
        buf.append('\'');
        if (!isBlank(rest)) {
            throw Ast.err(
                    AstException.AST__BDFTS,
                    "Cannot encode string '" + s + "' into a FITS header card for keyword '" + keyword + "'.");
        }
    }

    /**
     * C's {@code FormatCard} for the comment after a value: "/ " and the comment, starting in
     * column 32 if it fits there, otherwise far enough left to end in column 80 though never closer
     * than one blank after the value, and cut at column 80.
     */
    private static void appendComment(StringBuilder buf, String com) {
        if (com == null || com.isEmpty()) {
            return;
        }
        int comstart = Math.max(Math.min(Math.max(FITS_CARD_LEN + 3 - chrLen(com), 0), FITS_COM_COL), buf.length() + 2);
        while (buf.length() < comstart - 1) {
            buf.append(' ');
        }
        String text = "/ " + com;
        for (int k = 0; k < text.length() && buf.length() < FITS_CARD_LEN; k++) {
            buf.append(text.charAt(k));
        }
    }
}
