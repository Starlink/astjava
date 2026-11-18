package uk.ac.starlink.ast;

import java.util.Map;

/**
 * Writes an Object to a FitsChan in the NATIVE encoding: as header cards holding the items of the
 * Object's description, each with a keyword made unique by a two-character sequence suffix ({@code
 * BEGAST_A}, {@code NIN_A}, {@code ISA_A}, {@code ENDAST_A}, ...), framed by banners of comment
 * cards.
 *
 * <p>C's FitsChan {@code Write} adds the banners and calls the Channel's {@code Write}, which has
 * the Object dump itself through the FitsChan's {@code WriteBegin}, {@code WriteIsA}, {@code
 * WriteEnd}, {@code WriteInt}, {@code WriteDouble}, {@code WriteString} and {@code WriteObject}
 * (fitschan.c); a nested Object is written by a recursive {@code Write}. Here the description is
 * the Object's {@link NativeNode}, as for the Channel's text ({@link NativeWriter}), and its items
 * are stored as those functions store them, one card at a time in front of the current card.
 *
 * <p>Ported from the write half of Rust {@code fitschan/enc_native.rs} and {@code
 * FitsChan::store_keyword_native}; the cards are stored in the FitsChan as C stores them, so a
 * keyword already used is found among the FitsChan's cards as C's {@code HasCard} finds it.
 */
final class FitsNativeWriter {

    /** C's {@code INDENT_INC}: the indentation added for each level of nesting. */
    static final int INDENT_INC = 3;

    /** C's {@code HEADER_TEXT}. */
    private static final String HEADER_TEXT = "Beginning of AST data for ";

    /** C's {@code FOOTER_TEXT}. */
    private static final String FOOTER_TEXT = "End of AST data for ";

    private static final String PLUSES = "++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++";
    private static final String DOTS = "................................................................";
    private static final String DASHES = "----------------------------------------------------------------";

    /** A blank keyword, as C passes it to {@code astSetFitsCom}. */
    private static final String BLANK_NAME = "        ";

    /** The characters of a commentary card after the keyword: {@code FITSCARDLEN - FITSNAMLEN}. */
    private static final int MXLEN = FitsCard.FITS_CARD_LEN - FitsCard.FITS_NAME_LEN;

    private final FitsChan fc;

    /** The FitsChan's Comment attribute. */
    private final boolean comm;

    /** C's {@code current_indent}: the indentation of the comments marking nested Objects. */
    private int currentIndent;

    /** C's {@code items_written}: the items written since the last Begin or IsA. */
    private int itemsWritten;

    FitsNativeWriter(FitsChan fc) {
        this.fc = fc;
        this.comm = fc.getComment();
    }

    /**
     * Writes {@code obj} as fitschan.c {@code Write} does for the NATIVE encoding: unless Comment
     * is clear, a blank card and a banner of COMMENT cards (with two more lines for a FrameSet),
     * then the Object, then a closing banner.
     */
    void write(AstObject obj) {
        String cls = obj.astClass();
        if (comm) {
            fc.setFitsCom(BLANK_NAME, "", false);
            banner(PLUSES, "", "");
            if (obj instanceof FrameSet) {
                banner("WCS information in AST format", "", "");
                banner("See http://www.starlink.ac.uk/ast/", "", "");
            }
            banner(HEADER_TEXT, cls, " object");
            banner(DOTS, "", "");
        }
        writeObject(NativeNode.of(obj), fc.getFull(), false);
        if (comm) {
            banner(DOTS, "", "");
            banner(FOOTER_TEXT, cls, " object");
            banner(DASHES, "", "");
        }
    }

    private void banner(String prefix, String middle, String suffix) {
        fc.setFitsCom("COMMENT", makeBanner(prefix, middle, suffix), false);
    }

    /** The comment, if comments are written (C's {@code astGetComment( this ) ? comment : NULL}). */
    private String com(String comment) {
        return comm ? comment : null;
    }

    /**
     * Writes one Object's description as channel.c {@code Write} has the Object dump itself: its
     * Begin item, its items and its End item. {@code full} is the Full attribute in force for it
     * (Full -1 for an Object whose writer caps it, as frame.c does for its Axes) and {@code nested}
     * is false for the top-level Object (C's {@code write_nest} zero).
     */
    void writeObject(NativeNode node, int full, boolean nested) {
        writeBegin(node.className, node.classComment, full, nested);
        for (NativeNode.Item item : node.items) {
            if (item.isIsA()) {
                if (item.value.kind != NativeNode.Kind.STRING) {
                    throw Ast.err(AstException.AST__INTER, "astWrite(FitsChan): an IsA item holds " + item.value + ".");
                }
                writeIsA(item.value.stringValue, item.comment, full);
            } else if (use(item.set, item.helpful, full)) {
                writeItem(item, full);
            }
        }
        writeEnd(node.className, full, nested);
    }

    /** C's {@code Use}: whether a value is written, given whether it is set and helpful. */
    private static boolean use(boolean set, boolean helpful, int full) {
        return set || (helpful && full > -1) || full > 0;
    }

    /** One item that is written, as C's {@code Write<type>} functions write it. */
    private void writeItem(NativeNode.Item item, int full) {
        NativeNode.Value value = item.value;
        // MODERN-JAVA(21): switch expression
        switch (value.kind) {
            case INT:
                // C's WriteInt.
                fc.setFitsI(createKeyword(item.key), value.intValue, com(item.comment), false);
                if (!item.set) {
                    makeIntoComment();
                }
                itemsWritten++;
                break;
            case DOUBLE:
                // C's WriteDouble.
                fc.setFits(createKeyword(item.key), value.doubleValue, com(item.comment), false);
                if (!item.set) {
                    makeIntoComment();
                }
                itemsWritten++;
                break;
            case STRING:
                writeString(item.key, item.set, value.stringValue, item.comment);
                break;
            case OBJECT:
                writeObjectItem(item, full);
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "astWrite(FitsChan): unexpected value kind " + value.kind + ".");
        }
    }

    /**
     * C's {@code WriteObject}: a blank string value, not pre-quoted, marking the description that
     * follows, then the Object's description written as a nested Object; for a value that is not
     * set the card is made a COMMENT card and no description follows.
     */
    private void writeObjectItem(NativeNode.Item item, int full) {
        fc.setFits(createKeyword(item.key), "", com(item.comment), false);
        if (item.set) {
            NativeNode child = item.value.object;
            writeObject(child, child.fullCapped && full <= 0 ? -1 : full, true);
        } else {
            makeIntoComment();
        }
        itemsWritten++;
    }

    /**
     * C's {@code WriteBegin}: increments the indentation, marks a nested Object with an indented
     * comment when Full is not negative, and writes the class name with a {@code BEGAST} keyword.
     */
    private void writeBegin(String cls, String comment, int full, boolean nested) {
        currentIndent += INDENT_INC;
        if (nested && full >= 0) {
            fc.setFitsCom(BLANK_NAME, makeIndentedComment(currentIndent, '+', "Beginning of ", cls), false);
        }
        fc.setFits(createKeyword("BEGAST"), preQuote(cls), com(comment), false);
        itemsWritten = 0;
    }

    /**
     * C's {@code WriteIsA}: the class name with an {@code ISA} keyword, and a comment marking the
     * class boundary when Full is not negative, written only if an item has been written since
     * the last Begin or IsA or Full is positive.
     */
    private void writeIsA(String cls, String comment, int full) {
        if (itemsWritten > 0 || full > 0) {
            fc.setFits(createKeyword("ISA"), preQuote(cls), com(comment), false);
            if (full >= 0) {
                fc.setFitsCom(BLANK_NAME, makeIndentedComment(currentIndent, '.', "Class boundary", ""), false);
            }
        }
        itemsWritten = 0;
    }

    /**
     * C's {@code WriteEnd}: the class name with an {@code ENDAST} keyword, a comment marking the
     * end of a nested Object when Full is not negative, and the indentation decremented.
     */
    private void writeEnd(String cls, int full, boolean nested) {
        fc.setFits(createKeyword("ENDAST"), preQuote(cls), com("End of object definition"), false);
        if (nested && full >= 0) {
            fc.setFitsCom(BLANK_NAME, makeIndentedComment(currentIndent, '-', "End of ", cls), false);
        }
        currentIndent -= INDENT_INC;
    }

    /**
     * C's {@code WriteString}: the value, pre-quoted, in as many cards as it needs, the first with
     * a keyword made from {@code name} and the others {@code CONTINUE} cards, each card but the
     * last ending in an ampersand. The first card leaves room for the comment, whether or not
     * comments are written, and, for a value that is not set, for the keyword of the COMMENT card
     * it is made into. An empty value writes no card.
     */
    void writeString(String name, boolean set, String value, String comment) {
        String keyword = createKeyword(name);
        int start = 0;
        boolean first = true;
        while (start < value.length()) {
            // The columns for this part: the card less the keyword, "= ", the delimiting quotes
            // and the two double quotes PreQuote may add.
            int nc = FitsCard.FITS_CARD_LEN - FitsCard.FITS_NAME_LEN - 6;
            if (first) {
                if (comment != null && !comment.isEmpty()) {
                    nc -= FitsCard.chrLen(comment) + 3;
                }
                if (!set) {
                    nc -= FitsCard.FITS_NAME_LEN + 5;
                }
            }
            if (nc <= 0) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astWrite(FitsChan): no room for the value of " + keyword + " beside its comment.");
            }
            // A single quote takes two columns, as it is doubled when the card is formatted.
            StringBuilder buf = new StringBuilder();
            while (start < value.length() && nc > 0) {
                char cc = value.charAt(start++);
                buf.append(cc);
                nc -= cc == '\'' ? 2 : 1;
            }
            // A last single quote that leaves no room for its double goes to the next part.
            if (nc < 0) {
                start--;
                buf.setLength(buf.length() - 1);
            }
            // If the value goes on, the last character makes room for an ampersand.
            if (start < value.length()) {
                start--;
                buf.setLength(buf.length() - 1);
                buf.append('&');
            }
            String part = preQuote(buf.toString());
            if (first) {
                fc.setFits(keyword, part, com(comment), false);
                if (!set) {
                    makeIntoComment();
                }
            } else {
                fc.setFitsContinue("CONTINUE", part, null, false);
            }
            first = false;
        }
        itemsWritten++;
    }

    /**
     * C's {@code MakeIntoComment}: replaces the card before the current one, the card just
     * written, by a COMMENT card holding its text, formatted with the FitsChan's FitsDigits and
     * FitsRounding.
     */
    private void makeIntoComment() {
        FitsCardList cards = fc.cards();
        cards.moveCard(-1);
        String text = cards.current().formatWithDigitsAndRounding(fc.getFitsDigits(), fc.getFitsRounding());
        fc.setFitsCom("COMMENT", text, true);
    }

    /**
     * C's {@code CreateKeyword}: a keyword for the item {@code name}, its first {@code FITSNAMLEN -
     * 2} characters in upper case followed by two of {@link FitsNativeReader#SEQ_CHARS} encoding
     * the next sequence number for that base. The FitsChan keeps the last number used for each
     * base from one write to the next, and a number whose keyword a card in the FitsChan already
     * has is passed over; once the two characters run out the last number is used again.
     */
    String createKeyword(String name) {
        String seqChars = FitsNativeReader.SEQ_CHARS;
        int nseq = seqChars.length();
        StringBuilder base = new StringBuilder();
        for (int nc = 0; nc < FitsCard.FITS_NAME_LEN - 2 && nc < name.length(); nc++) {
            base.append(CScan.toUpper(name.charAt(nc)));
        }
        Map<String, Integer> keySeq = fc.keySequences();
        Integer last = keySeq.get(base.toString());
        int seq = last == null ? 0 : last;
        String keyword;
        while (true) {
            boolean limit = seq >= nseq * nseq - 1;
            if (!limit) {
                seq++;
            }
            keyword = base.toString() + seqChars.charAt(seq / nseq) + seqChars.charAt(seq % nseq);
            if (limit || !fc.cards().hasCard(keyword)) {
                break;
            }
        }
        keySeq.put(base.toString(), seq);
        return keyword;
    }

    /**
     * C's {@code MakeBanner}: {@code prefix}, {@code middle} and {@code suffix} centred in the 72
     * columns of a comment card with the token {@code AST} at each end, the text truncated so it
     * never reaches the last token.
     */
    static String makeBanner(String prefix, String middle, String suffix) {
        String token = "AST";
        int ltok = token.length();
        int start =
                ltok + 2 + (MXLEN - ltok - 1 - (prefix.length() + middle.length() + suffix.length()) - 1 - ltok) / 2;
        if (start < ltok + 2) {
            start = ltok + 2;
        }
        StringBuilder banner = new StringBuilder(MXLEN);
        for (int i = 0; i < ltok && banner.length() < MXLEN; i++) {
            banner.append(token.charAt(i));
        }
        while (banner.length() < start - 1) {
            banner.append(' ');
        }
        for (String s : new String[] {prefix, middle, suffix}) {
            for (int i = 0; i < s.length() && banner.length() < MXLEN - ltok - 1; i++) {
                banner.append(s.charAt(i));
            }
        }
        while (banner.length() < MXLEN - ltok) {
            banner.append(' ');
        }
        for (int i = 0; i < ltok && banner.length() < MXLEN; i++) {
            banner.append(token.charAt(i));
        }
        return banner.toString();
    }

    /**
     * C's {@code MakeIndentedComment}: {@code indent} copies of {@code token}, padded to the
     * comment column of a card, then {@code "/ "}, {@code comment} and {@code data}, all within the
     * 72 columns of a comment card.
     */
    static String makeIndentedComment(int indent, char token, String comment, String data) {
        StringBuilder s = new StringBuilder(MXLEN);
        while (s.length() < indent && s.length() < MXLEN) {
            s.append(token);
        }
        while (s.length() < FitsCard.FITS_COM_COL - FitsCard.FITS_NAME_LEN - 1) {
            s.append(' ');
        }
        String text = "/ " + comment + data;
        for (int i = 0; i < text.length() && s.length() < MXLEN; i++) {
            s.append(text.charAt(i));
        }
        return s.toString();
    }

    /**
     * C's {@code PreQuote}: as much of {@code value} as a card's string value holds, its single
     * quotes counted twice as the card doubles them, in double quotes when the last character kept
     * is white space (which FITS does not keep) or the value starts with a double quote and the
     * last character kept is one (so that removing the quotes on reading leaves both). An empty
     * value is two double quotes.
     */
    static String preQuote(String value) {
        int dquotes = 2;
        int nc = 0;
        int sq = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                sq++;
            }
            int dq = CScan.isCSpace(c) || (value.charAt(0) == '"' && c == '"') ? 2 : 0;
            if (nc + 1 + dq + sq > FitsCard.FITS_CARD_LEN - FitsCard.FITS_NAME_LEN - 4) {
                break;
            }
            nc = i + 1;
            dquotes = dq;
        }
        String body = value.substring(0, nc);
        return dquotes != 0 ? "\"" + body + "\"" : body;
    }
}
