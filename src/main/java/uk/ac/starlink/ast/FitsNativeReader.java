package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * The data items of the NATIVE encoding held in a FitsChan's cards, as fitschan.c {@code
 * GetNextData} gives them to the Channel reader: the FitsChan's override of the Channel method that
 * reads the next name and value.
 *
 * <p>An Object is written as keyword cards whose names end in two sequence characters (from
 * {@link #SEQ_CHARS}) that make them unique: {@code BEGASTxx} holds the class name of an Object
 * that begins, {@code ISAxx} that of a class whose data have been given, and {@code ENDASTxx} that
 * of an Object that ends, each as a string; any other such card holds an item, named by its
 * keyword without the sequence characters, in lower case, with a string, integer or floating
 * value. An empty string stands for an Object, given by the cards that follow. A long string
 * ending in {@code &} continues on {@code CONTINUE} cards. Comment cards are passed over.
 *
 * <p>Reading starts at the current card and moves it on past each item. With skip set, cards are
 * passed over until a {@code BEGASTxx} card; otherwise each card read is marked as provisionally
 * used, and a card that is not an item is an error. The AST banner comment blocks before a {@code
 * BEGASTxx} card and after an {@code ENDASTxx} card are marked with it.
 *
 * <p>Ported from fitschan.c {@code GetNextData}; Rust {@code fitschan/enc_native.rs} instead turns
 * the cards into native text and parses that.
 */
final class FitsNativeReader implements NativeStream.DataSource {

    /** The characters of the two-character sequence suffix on each keyword (fitschan.c:1584). */
    static final String SEQ_CHARS = "_ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** The method C names in its error messages. */
    private static final String METHOD = "astRead";

    /** How far a read had got: the current card and the cards then provisionally used. */
    // MODERN-JAVA(21): record
    private static final class Snapshot {
        final int position;
        final BitSet provisional;

        Snapshot(int position, BitSet provisional) {
            this.position = position;
            this.provisional = provisional;
        }
    }

    private final FitsCardList cards;

    /** The FitsRounding attribute, with which floating values are formatted. */
    private final int fitsRounding;

    /** The states {@link #mark} has recorded, indexed by the marks it gave. */
    private final List<Snapshot> snapshots = new ArrayList<>();

    /**
     * A reader of the cards of {@code cards} from the current card on.
     *
     * @param fitsRounding the FitsChan's FitsRounding attribute
     */
    FitsNativeReader(FitsCardList cards, int fitsRounding) {
        this.cards = cards;
        this.fitsRounding = fitsRounding;
    }

    /** Whether {@code c} is one of the {@link #SEQ_CHARS}. */
    static boolean isSeqChar(char c) {
        return SEQ_CHARS.indexOf(c) >= 0;
    }

    /**
     * Whether {@code keyword} is {@code prefix} followed by exactly two sequence characters, C's
     * test {@code astSscanf( keyword, "<prefix>%*1[SEQ_CHARS]%*1[SEQ_CHARS]%n", &nc )} with {@code nc}
     * reaching the end of the keyword.
     */
    static boolean isMarker(String keyword, String prefix) {
        int n = prefix.length();
        return keyword.length() == n + 2
                && keyword.startsWith(prefix)
                && isSeqChar(keyword.charAt(n))
                && isSeqChar(keyword.charAt(n + 1));
    }

    /** Whether {@code keyword} has more than two characters and ends in two sequence characters. */
    static boolean isDataKeyword(String keyword) {
        int len = keyword.length();
        return len > 2 && isSeqChar(keyword.charAt(len - 1)) && isSeqChar(keyword.charAt(len - 2));
    }

    /**
     * C's {@code UnPreQuote}: {@code s} without its first and last characters if it has at least
     * two and both are double quotes, which the writer adds to keep leading and trailing spaces.
     */
    static String unPreQuote(String s) {
        int n = s.length();
        if (n >= 2 && s.charAt(0) == '"' && s.charAt(n - 1) == '"') {
            return s.substring(1, n - 1);
        }
        return s;
    }

    /** {@code s} without trailing characters that are not graphic, as C's {@code ChrLen} measures it. */
    private static String stripped(String s) {
        return s.substring(0, FitsCard.chrLen(s));
    }

    /** C's {@code MarkCard}: marks the current card as provisionally used. */
    private void markCurrent() {
        cards.markAt(cards.position());
    }

    @Override
    public NativeStream.Datum next(boolean skip, List<NativeNode.Item> defaults) {
        // fitschan.c:17917 GetNextData. Each card considered moves the current card on, even after
        // an error, since C's MoveCard does not check the error status.
        while (cards.current() != null) {
            FitsCard card = cards.current();
            if (!skip) {
                markCurrent();
            }
            NativeStream.Datum datum = null;
            AstException error = null;
            if (card.value.kind != FitsCard.Kind.COMMENT) {
                try {
                    datum = item(card, skip);
                } catch (AstException e) {
                    error = e;
                }
            }
            cards.moveCard(1);
            if (error != null) {
                throw error;
            }
            if (datum != null) {
                return datum;
            }
        }
        return null;
    }

    /**
     * The data item {@code card}, the current card, holds; null if it holds none and is to be
     * passed over.
     *
     * @throws AstException with status {@code AST__BADIN} if skip is not set and the card is not
     *     an item, or a continued string is not followed by a {@code CONTINUE} card
     */
    private NativeStream.Datum item(FitsCard card, boolean skip) {
        String keyword = card.cName();
        FitsCard.Kind type = card.value.kind;
        boolean string = type == FitsCard.Kind.STRING;
        // Trailing white space is removed from a string, so that a null string can be told from a
        // blank one.
        String data = string ? stripped(card.value.s) : null;
        if (string && isMarker(keyword, "BEGAST")) {
            // fitschan.c:18103
            markCurrent();
            cards.markComBlock(cards.position(), -1);
            return new NativeStream.Datum("begin", unPreQuote(data), "", null, null);
        } else if (!skip && string && isMarker(keyword, "ISA")) {
            return new NativeStream.Datum("isa", unPreQuote(data), "", null, null);
        } else if (!skip && string && isMarker(keyword, "ENDAST")) {
            cards.markComBlock(cards.position(), 1);
            return new NativeStream.Datum("end", unPreQuote(data), "", null, null);
        } else if (!skip
                && (string || type == FitsCard.Kind.INT || type == FitsCard.Kind.KINT || type == FitsCard.Kind.FLOAT)
                && isDataKeyword(keyword)) {
            String name = keyword.substring(0, keyword.length() - 2).toLowerCase(Locale.ROOT);
            String val;
            NativeNode.Value value;
            switch (type) {
                case STRING:
                    if (data.isEmpty()) {
                        // A null string introduces an Object, whose definition follows.
                        return new NativeStream.Datum(name, null, name, null, null);
                    }
                    val = data.charAt(data.length() - 1) == '&' ? continued(data, keyword) : unPreQuote(data);
                    value = NativeNode.Value.ofString(val);
                    break;
                case INT:
                case KINT:
                    val = Long.toString(card.value.i);
                    value = NativeReader.parseScalarValue(val);
                    break;
                case FLOAT:
                    // fitschan.c:18297
                    val = FitsFormat.checkZero(CFormat.format("%.17g", card.value.d), card.value.d, 0, fitsRounding);
                    value = NativeReader.parseScalarValue(val);
                    break;
                default:
                    throw Ast.err(AstException.AST__INTER, "FitsNativeReader: unexpected card type " + type + ".");
            }
            return new NativeStream.Datum(name, val, name, value, null);
        } else if (!skip) {
            // fitschan.c:18310
            throw Ast.err(
                    AstException.AST__BADIN,
                    METHOD + "(FitsChan): Cannot interpret the input data given by FITS keyword \"" + keyword + "\".");
        }
        return null;
    }

    /**
     * A string value ending in {@code &}, {@code data}, joined with the {@code CONTINUE} cards that
     * follow, each of which is marked as used and made current in turn (fitschan.c:18226). The
     * first part is kept as it is; each continuation has its double quotes removed and replaces
     * the last character so far.
     */
    private String continued(String data, String keyword) {
        StringBuilder val = new StringBuilder(data);
        boolean cont = true;
        while (cont && cards.moveCard(1) != 0) {
            FitsCard next = cards.current();
            if (next.value.kind != FitsCard.Kind.CONTINUE) {
                // fitschan.c:18270. C's loop test moves the current card once more before it
                // sees the error.
                cards.moveCard(1);
                throw Ast.err(
                        AstException.AST__BADIN,
                        METHOD + "(FitsChan): One or more FITS \"CONTINUE\" cards are missing after the card for "
                                + "keyword \"" + keyword + "\".");
            }
            String part = next.value.s;
            // C looks at the character before the trailing blanks; a part that is all blank has
            // none, and is taken as not continued.
            int len = FitsCard.chrLen(part);
            cont = len > 0 && part.charAt(len - 1) == '&';
            val.setLength(val.length() - 1);
            val.append(unPreQuote(part));
            markCurrent();
        }
        return val.toString();
    }

    @Override
    public int mark() {
        BitSet provisional = new BitSet();
        List<FitsCard> list = cards.cards();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).flags.consumption == FitsCard.Consumption.PROVISIONAL) {
                provisional.set(i);
            }
        }
        snapshots.add(new Snapshot(cards.position(), provisional));
        return snapshots.size() - 1;
    }

    /**
     * Returns to the state {@link #mark} recorded as {@code mark}: that current card, and the cards
     * marked since then made available again. C stops reading where it raises an error that this
     * reader, which reads a whole Object first, raises later.
     */
    void rewind(int mark) {
        Snapshot s = snapshots.get(mark);
        List<FitsCard> list = cards.cards();
        for (int i = 0; i < list.size(); i++) {
            FitsCard c = list.get(i);
            if (c.flags.consumption == FitsCard.Consumption.PROVISIONAL && !s.provisional.get(i)) {
                c.flags.consumption = FitsCard.Consumption.FREE;
            }
        }
        cards.setPosition(s.position);
    }
}
