package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The ordered list of a FitsChan's cards, with the current card.
 *
 * <p>C keeps a pointer to the current card, or NULL at end-of-file, and its public functions skip
 * cards a read has consumed. Here the current card is {@link #position()}, an index into the list,
 * which still holds the consumed cards; the length of the list is end-of-file.
 *
 * <p>Ported from Rust {@code fitschan/card_list.rs}.
 */
final class FitsCardList {

    /** Which of C's two "new card" flags {@link #fixNew} acts on (fitschan.c:1652-1653). */
    enum NewScope {
        /** C's {@code NEW1}: cards added anywhere in the current write. */
        WRITE,
        /** C's {@code NEW2}: cards added during the current axis-description pass. */
        VERSION
    }

    /** The integer fields C's {@code KeyFields} finds. */
    static final class KeyFields {
        /** The number of cards matching. */
        final int n;

        /** The lowest value of each field. */
        final int[] lo;

        /** The highest value of each field. */
        final int[] hi;

        KeyFields(int n, int[] lo, int[] hi) {
            this.n = n;
            this.lo = lo;
            this.hi = hi;
        }
    }

    /** A card's keyword and the number of cards with it before it. */
    static final class Occurrence {
        final String keyword;
        final int n;

        Occurrence(String keyword, int n) {
            this.keyword = keyword;
            this.n = n;
        }
    }

    private final List<FitsCard> list = new ArrayList<>();

    private int pos;

    /** Keywords recorded as consumed by the current read pass (C's {@code MarkCard} of a read). */
    private List<String> consumedNames = new ArrayList<>();

    /** The keywords {@link #setFloat} and {@link #setString} have written while recording, or null. */
    private List<String> writtenNames;

    /** Whether cards added now are flagged new, C's {@code mark_new}. */
    private boolean markNew;

    /** An empty list. */
    FitsCardList() {}

    /** A deep copy: the same cards, each with its own flags, and the same current card. */
    FitsCardList copy() {
        FitsCardList c = new FitsCardList();
        for (FitsCard card : list) {
            c.list.add(card.copy());
        }
        c.pos = pos;
        c.consumedNames = new ArrayList<>(consumedNames);
        c.writtenNames = writtenNames == null ? null : new ArrayList<>(writtenNames);
        c.markNew = markNew;
        return c;
    }

    /** The cards, consumed ones included, in order; the list is live. */
    List<FitsCard> cards() {
        return list;
    }

    /** The number of cards, consumed ones included. */
    int len() {
        return list.size();
    }

    /** Whether there are no cards. */
    boolean isEmpty() {
        return list.isEmpty();
    }

    /** A list of copies of the cards {@code from} to {@code to} inclusive, leaving out consumed ones. */
    FitsCardList subList(int from, int to) {
        FitsCardList out = new FitsCardList();
        for (FitsCard card : list.subList(from, to + 1)) {
            if (!card.isConsumed()) {
                out.list.add(card.copy());
            }
        }
        return out;
    }

    /** Appends a card. */
    void push(FitsCard card) {
        list.add(card);
    }

    /**
     * Inserts a block of cards in front of the current card, in order, and leaves that card current
     * (C's {@code InsCard}, one card at a time).
     */
    void insertAtCurrent(List<FitsCard> block) {
        int at = Math.min(pos, list.size());
        list.addAll(at, block);
        pos = at + block.size();
    }

    /**
     * Inserts a block of {@code ASTWARN} cards holding {@code text} in front of the current card,
     * as C's {@code Warn} (fitschan.c:35184) does: a blank card, one card per line of the text
     * wrapped at about 60 characters, and another blank card, each a string-valued {@code ASTWARN}
     * keyword inserted as {@code astSetFitsS} with no overwrite inserts it, so the current card stays
     * current. The block is not added if the same block of adjacent cards is already present
     * (fitschan.c:35189-35247).
     */
    void pushAstwarn(String text) {
        List<String> lines = FitsCard.wrapWarningText(text);
        if (lines.isEmpty() || astwarnBlockExists(lines)) {
            return;
        }
        List<FitsCard> block = new ArrayList<>(lines.size() + 2);
        block.add(astwarn(" "));
        for (String line : lines) {
            block.add(astwarn(line));
        }
        block.add(astwarn(" "));
        insertAtCurrent(block);
    }

    /** An {@code ASTWARN} card, flagged new during a write so that a failed write removes it. */
    private FitsCard astwarn(String s) {
        FitsCard c = new FitsCard("ASTWARN", FitsCard.Value.ofString(s), null);
        if (markNew) {
            c.flags.newWrite = true;
            c.flags.newVersion = true;
        }
        return c;
    }

    private static boolean isAstwarnHolding(FitsCard card, String want) {
        return "ASTWARN".equalsIgnoreCase(card.keyword)
                && card.value.kind == FitsCard.Kind.STRING
                && card.value.s.equals(want);
    }

    /**
     * Whether the list holds {@code lines} as a run of adjacent {@code ASTWARN} cards: C's {@code
     * Warn} searches for the first line, then needs each later one on the next card.
     */
    private boolean astwarnBlockExists(List<String> lines) {
        for (int start = 0; start < list.size(); start++) {
            if (!isAstwarnHolding(list.get(start), lines.get(0))) {
                continue;
            }
            boolean all = true;
            for (int k = 1; k < lines.size() && all; k++) {
                int idx = start + k;
                all = idx < list.size() && isAstwarnHolding(list.get(idx), lines.get(k));
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stores a card as {@code astPutFits} stores one: inserted in front of the current card, or
     * with {@code overwrite} replacing it, whatever its keyword, a card with no comment then taking
     * the comment of the card replaced; at end-of-file it is appended. The card after it is then
     * current (fitschan.c:25613).
     */
    void put(FitsCard card, boolean overwrite) {
        if (overwrite && (card.comment == null || FitsCard.isBlank(card.comment))) {
            FitsCard cur = current();
            String com = cur == null ? null : cur.cComment();
            com = com == null ? null : FitsCard.trim(com);
            card.comment = com == null || com.isEmpty() ? null : com;
        }
        insCard(card, overwrite);
    }

    /** The first card not consumed with {@code keyword}, ignoring case, or null. */
    FitsCard find(String keyword) {
        for (FitsCard c : list) {
            if (!c.isConsumed() && c.keyword.equalsIgnoreCase(keyword)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Searches forward from the current card, consumed cards included, for one with {@code
     * keyword}; if one is found, the card after it becomes current. Null if there is none, leaving
     * the current card unchanged.
     */
    FitsCard findNext(String keyword) {
        for (int k = pos; k < list.size(); k++) {
            if (list.get(k).keyword.equalsIgnoreCase(keyword)) {
                pos = k + 1;
                return list.get(k);
            }
        }
        return null;
    }

    /** {@link #findNext}, passing over cards a read has consumed. */
    FitsCard findNextVisible(String keyword) {
        for (int k = pos; k < list.size(); k++) {
            FitsCard c = list.get(k);
            if (!c.isConsumed() && c.keyword.equalsIgnoreCase(keyword)) {
                pos = k + 1;
                return c;
            }
        }
        return null;
    }

    /** The integer value of the first card with {@code keyword} (a float truncated), or null. */
    Long getInt(String keyword) {
        FitsCard c = find(keyword);
        if (c == null) {
            return null;
        }
        switch (c.value.kind) {
            case INT:
            case KINT:
                return c.value.i;
            case FLOAT:
                return (long) c.value.d;
            default:
                return null;
        }
    }

    /**
     * The floating value of the first card with {@code keyword}, converted as {@code astGetFitsF}
     * converts it (C's {@code CnvType}); null if there is no such card or it does not convert.
     */
    Double getFloat(String keyword) {
        FitsCard c = find(keyword);
        if (c == null) {
            return null;
        }
        try {
            FitsCardValue.Got g = FitsCardValue.cnvType(c.value, FitsCardValue.Want.FLOAT, 0, c.keyword);
            return g == null ? null : g.d;
        } catch (AstException e) {
            return null;
        }
    }

    /** The string value of the first card with {@code keyword}, or null. */
    String getString(String keyword) {
        FitsCard c = find(keyword);
        return (c != null && c.value.kind == FitsCard.Kind.STRING) ? c.value.s : null;
    }

    /** The logical value of the first card with {@code keyword}, or null. */
    Boolean getLogical(String keyword) {
        FitsCard c = find(keyword);
        return (c != null && c.value.kind == FitsCard.Kind.LOGICAL) ? (Boolean) c.value.b : null;
    }

    // ------------------------------------------------------------------
    // Consumption by a read (C's USED cards)
    // ------------------------------------------------------------------

    /** Records {@code keyword} as consumed by the current read pass. */
    void recordConsumed(String keyword) {
        consumedNames.add(keyword);
    }

    /** {@link #getFloat}, recording the keyword as consumed when a card is found. */
    Double getFloatMarked(String keyword) {
        Double v = getFloat(keyword);
        if (v != null) {
            recordConsumed(keyword);
        }
        return v;
    }

    /** {@link #getString}, recording the keyword as consumed when a card is found. */
    String getStringMarked(String keyword) {
        String v = getString(keyword);
        if (v != null) {
            recordConsumed(keyword);
        }
        return v;
    }

    /**
     * The string with trailing blanks and non-printing characters removed, as C's {@code WcsFcRead}
     * strips every string it stores in a FitsStore (fitschan.c:37035), or null for null.
     */
    static String stripWcsStoreString(String value) {
        return value == null ? null : value.substring(0, FitsCard.chrLen(value));
    }

    // ------------------------------------------------------------------
    // The values C's WcsFcRead stores in a FitsStore
    // ------------------------------------------------------------------

    /**
     * The value C's {@code WcsFcRead} stores for {@code keyword}: it visits the cards not consumed
     * in order, and stores the value of each card with that keyword that {@code CnvValue} converts
     * to {@code want}, so that a later card replaces an earlier one's value, while a card whose
     * value does not convert stores nothing (fitschan.c:37357-37373). Null if no card converts.
     */
    private FitsCardValue.Got lastConverted(String keyword, FitsCardValue.Want want, int fitsrnd) {
        FitsCardValue.Got got = null;
        for (FitsCard c : list) {
            if (!c.isConsumed() && c.keyword.equalsIgnoreCase(keyword)) {
                FitsCardValue.Got g;
                try {
                    g = FitsCardValue.cnvType(c.value, want, fitsrnd, c.keyword);
                } catch (AstException e) {
                    // A card with no value; the sweep of the cards reports it.
                    g = null;
                }
                if (g != null) {
                    got = g;
                }
            }
        }
        return got;
    }

    /** The floating value C's {@code WcsFcRead} stores for {@code keyword}, or null. */
    Double getStoredFloat(String keyword) {
        FitsCardValue.Got g = lastConverted(keyword, FitsCardValue.Want.FLOAT, 0);
        return g == null ? null : g.d;
    }

    /**
     * The string value C's {@code WcsFcRead} stores for {@code keyword}, or null; {@code fitsrnd}
     * is the FitsChan's FitsRounding, which shapes the text of a floating value.
     */
    String getStoredString(String keyword, int fitsrnd) {
        FitsCardValue.Got g = lastConverted(keyword, FitsCardValue.Want.STRING, fitsrnd);
        return g == null ? null : g.s;
    }

    /** {@link #getStoredFloat}, recording the keyword as consumed when a value is stored. */
    Double getStoredFloatMarked(String keyword) {
        Double v = getStoredFloat(keyword);
        if (v != null) {
            recordConsumed(keyword);
        }
        return v;
    }

    /** {@link #getStoredString}, recording the keyword as consumed when a value is stored. */
    String getStoredStringMarked(String keyword, int fitsrnd) {
        String v = getStoredString(keyword, fitsrnd);
        if (v != null) {
            recordConsumed(keyword);
        }
        return v;
    }

    /**
     * The comment C's {@code WcsFcRead} stores for a CTYPE keyword: that of the last card not
     * consumed with {@code keyword}, whether or not its value converts (fitschan.c:36848-36860),
     * or null if there is none.
     */
    String getStoredComment(String keyword) {
        FitsCard last = null;
        for (FitsCard c : list) {
            if (!c.isConsumed() && c.keyword.equalsIgnoreCase(keyword)) {
                last = c;
            }
        }
        return last == null ? null : last.cComment();
    }

    /** {@link #find}, recording the keyword as consumed when a card is found. */
    FitsCard findMarked(String keyword) {
        FitsCard c = find(keyword);
        if (c != null) {
            recordConsumed(keyword);
        }
        return c;
    }

    /** Starts recording the keywords {@link #setFloat} and {@link #setString} write. */
    void recordWrites() {
        writtenNames = new ArrayList<>();
    }

    /** The keywords written since {@link #recordWrites}, which stops recording. */
    List<String> takeWrittenNames() {
        List<String> names = writtenNames == null ? new ArrayList<>() : writtenNames;
        writtenNames = null;
        return names;
    }

    private void noteWritten(String keyword) {
        if (writtenNames != null) {
            writtenNames.add(keyword.toUpperCase(Locale.ROOT));
        }
    }

    /** The keywords recorded as consumed this pass, which are forgotten. */
    List<String> takeConsumedNames() {
        List<String> names = consumedNames;
        consumedNames = new ArrayList<>();
        return names;
    }

    /** Whether a read may mark {@code c}: neither used nor retained. */
    private static boolean markable(FitsCard c) {
        return c.flags.consumption != FitsCard.Consumption.USED && !c.flags.retained;
    }

    /**
     * Marks the first card with {@code keyword} that is neither used nor retained as provisionally
     * used, C's {@code MarkCard} (fitschan.c:23098); nothing if there is none.
     */
    void markCard(String keyword) {
        for (FitsCard c : list) {
            if (c.keyword.equalsIgnoreCase(keyword) && markable(c)) {
                c.flags.consumption = FitsCard.Consumption.PROVISIONAL;
                return;
            }
        }
    }

    /** Marks every card with {@code keyword} that is neither used nor retained, C's {@code MarkAllCards}. */
    void markAllCards(String keyword) {
        for (FitsCard c : list) {
            if (c.keyword.equalsIgnoreCase(keyword) && markable(c)) {
                c.flags.consumption = FitsCard.Consumption.PROVISIONAL;
            }
        }
    }

    /** Marks the card at {@code index} as provisionally used, unless it is used or retained. */
    void markAt(int index) {
        if (index >= 0 && index < list.size() && markable(list.get(index))) {
            list.get(index).flags.consumption = FitsCard.Consumption.PROVISIONAL;
        }
    }

    /**
     * Validates and marks the AST banner block next to the card at {@code index}, C's {@code
     * ComBlock} (fitschan.c:7972): {@code incr} -1 scans back from the card before it (delimiter
     * '+'), 1 forward from the card after it (delimiter '-'). A block is complete when every card is
     * a COMMENT whose text starts and ends with {@code AST}, ending with a card whose interior is
     * only the delimiter and spaces. Nothing is marked unless the whole block is valid.
     *
     * @return whether the block is valid
     */
    boolean markComBlock(int index, int incr) {
        char del = incr == 1 ? '-' : '+';
        List<Integer> found = new ArrayList<>();
        int k = index;
        while (true) {
            k += incr;
            if (k < 0 || k >= list.size()) {
                return false;
            }
            FitsCard c = list.get(k);
            if (c.value.kind != FitsCard.Kind.COMMENT) {
                return false;
            }
            String t = FitsCard.trimEnd(c.value.s);
            if (t.length() < 6 || !t.startsWith("AST") || !t.endsWith("AST")) {
                return false;
            }
            found.add(k);
            boolean onlyDel = true;
            for (int j = 3; j < t.length() - 3; j++) {
                char ch = t.charAt(j);
                if (ch != del && ch != ' ') {
                    onlyDel = false;
                    break;
                }
            }
            if (onlyDel) {
                break;
            }
        }
        for (int idx : found) {
            markAt(idx);
        }
        return true;
    }

    /** Makes every card available again, as C's {@code FixUsed} with {@code reset} does. */
    void resetUsed() {
        for (FitsCard c : list) {
            c.flags.consumption = FitsCard.Consumption.FREE;
        }
    }

    /**
     * Resolves provisional use after a read, C's {@code FixUsed} (fitschan.c:16488): with {@code
     * promote} provisional cards become used, otherwise free again; with {@code remove} (a NATIVE
     * read) they are deleted instead, a deleted current card passing currency to the next card
     * kept, as C's {@code DeleteCard} does.
     */
    void fixUsed(boolean promote, boolean remove) {
        if (remove) {
            int before = 0;
            int lim = Math.min(pos, list.size());
            for (int k = 0; k < lim; k++) {
                if (list.get(k).flags.consumption != FitsCard.Consumption.PROVISIONAL) {
                    before++;
                }
            }
            list.removeIf(c -> c.flags.consumption == FitsCard.Consumption.PROVISIONAL);
            pos = before;
        } else {
            FitsCard.Consumption target = promote ? FitsCard.Consumption.USED : FitsCard.Consumption.FREE;
            for (FitsCard c : list) {
                if (c.flags.consumption == FitsCard.Consumption.PROVISIONAL) {
                    c.flags.consumption = target;
                }
            }
        }
        pos = Math.min(pos, list.size());
    }

    /** Retains the first card with {@code keyword}; whether there was one. */
    boolean setProtected(String keyword) {
        for (FitsCard c : list) {
            if (c.keyword.equalsIgnoreCase(keyword)) {
                c.flags.retained = true;
                return true;
            }
        }
        return false;
    }

    /** Records {@code keyword} as consumed, then deletes the first card with it. */
    boolean deleteConsumed(String keyword) {
        recordConsumed(keyword);
        return delete(keyword);
    }

    /** Deletes the first card with {@code keyword}; whether there was one. */
    boolean delete(String keyword) {
        for (int idx = 0; idx < list.size(); idx++) {
            if (list.get(idx).keyword.equalsIgnoreCase(keyword)) {
                list.remove(idx);
                if (pos > idx && pos > 0) {
                    pos--;
                }
                return true;
            }
        }
        return false;
    }

    /** Deletes the card at the current position; nothing at end-of-file. */
    void deleteAtPosition() {
        if (pos < list.size()) {
            list.remove(pos);
        }
    }

    /** Moves to the first card, consumed or not. */
    void rewind() {
        pos = 0;
    }

    /** Sets the position, at most end-of-file ({@link Integer#MAX_VALUE} is end-of-file). */
    void setPosition(int position) {
        pos = Math.min(position, list.size());
    }

    /** The current position. */
    int position() {
        return pos;
    }

    /**
     * The keyword of the card at {@code index} and how many cards with it precede it, by which
     * {@link #occurrenceIndex} finds it again after other cards change; null at end-of-file.
     */
    Occurrence cardOccurrence(int index) {
        if (index < 0 || index >= list.size()) {
            return null;
        }
        String keyword = list.get(index).keyword;
        int n = 0;
        for (int k = 0; k < index; k++) {
            if (list.get(k).keyword.equals(keyword)) {
                n++;
            }
        }
        return new Occurrence(keyword, n);
    }

    /** The index of the card with {@code keyword} preceded by {@code n} others with it, or -1. */
    int occurrenceIndex(String keyword, int n) {
        int seen = 0;
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).keyword.equals(keyword)) {
                if (seen == n) {
                    return k;
                }
                seen++;
            }
        }
        return -1;
    }

    /** Flags every card added from now on as new, C's {@code mark_new = 1} at the start of a write. */
    void beginMarkingNew() {
        markNew = true;
    }

    /** Stops flagging added cards as new. */
    void endMarkingNew() {
        markNew = false;
    }

    /**
     * Resolves the cards flagged new, C's {@code FixNew} (fitschan.c:16472): with {@code remove}
     * they are deleted, otherwise the flag is cleared. When a card is removed the list is left at
     * end-of-file, and otherwise the position is kept.
     */
    void fixNew(NewScope scope, boolean remove) {
        if (remove) {
            int before = list.size();
            list.removeIf(c -> scope == NewScope.WRITE ? c.flags.newWrite : c.flags.newVersion);
            if (list.size() != before) {
                pos = list.size();
                return;
            }
        } else {
            for (FitsCard c : list) {
                if (scope == NewScope.WRITE) {
                    c.flags.newWrite = false;
                } else {
                    c.flags.newVersion = false;
                }
            }
        }
        pos = Math.min(pos, list.size());
    }

    // ------------------------------------------------------------------
    // The current card, as C's FitsChan models it
    // ------------------------------------------------------------------

    private boolean hidden(int k) {
        return list.get(k).isConsumed();
    }

    /** The current card, or null at end-of-file. */
    FitsCard current() {
        return pos < list.size() ? list.get(pos) : null;
    }

    /**
     * C's {@code MoveCard}: moves {@code mv} cards towards end-of-file, or if negative towards the
     * first card, counting only cards not consumed.
     *
     * @return the number of cards moved
     */
    long moveCard(long mv) {
        if (list.isEmpty() || mv == 0) {
            return 0;
        }
        int n = list.size();
        int card = pos;
        long moved = 0;
        if (mv > 0) {
            while (moved < mv && card < n) {
                card++;
                if (card < n && !hidden(card)) {
                    moved++;
                }
            }
        } else if (card != 0) {
            // From end-of-file the first step back reaches the last card.
            while (moved < -mv) {
                int card0 = card;
                do {
                    card0--;
                } while (card0 != 0 && hidden(card0));
                if (hidden(card0)) {
                    break;
                }
                card = card0;
                moved++;
                if (card == 0) {
                    break;
                }
            }
        }
        pos = card;
        return moved;
    }

    /** C's {@code ClearCard}: makes the first card not consumed current. */
    void clearCard() {
        if (list.isEmpty()) {
            return;
        }
        pos = 0;
        if (hidden(0)) {
            moveCard(1);
        }
    }

    /**
     * C's {@code GetCard}: the index of the current card among those not consumed, the first being
     * 1, one more than their number at end-of-file, and 0 for an empty list.
     */
    int getCard() {
        if (list.isEmpty()) {
            return 0;
        }
        int card0 = pos;
        clearCard();
        int index = 1;
        while (pos != card0 && pos < list.size()) {
            index++;
            moveCard(1);
        }
        return index;
    }

    /** C's {@code SetCard}: rewinds, then moves forward {@code icard - 1} cards. */
    void setCard(long icard) {
        clearCard();
        moveCard(icard == Long.MIN_VALUE ? Long.MIN_VALUE : icard - 1);
    }

    /** C's {@code TestCard}: whether the current card is not the first. */
    boolean testCard() {
        int card = getCard();
        clearCard();
        if (getCard() == card) {
            return false;
        }
        setCard(card);
        return true;
    }

    /** C's {@code GetNcard}: the number of cards not consumed. */
    int ncard() {
        int n = 0;
        for (FitsCard c : list) {
            if (!c.isConsumed()) {
                n++;
            }
        }
        return n;
    }

    /** C's {@code GetNkey}: the number of different keywords among the cards not consumed. */
    int nkey() {
        Set<String> names = new HashSet<>();
        for (FitsCard c : list) {
            if (!c.isConsumed()) {
                names.add(c.cName());
            }
        }
        return names.size();
    }

    /**
     * C's {@code KeyFields}: the number of cards not consumed whose keyword matches {@code
     * template}, and the lowest and highest value of each of the first {@code maxfld} integer
     * fields among them.
     */
    KeyFields keyFields(String template, int maxfld) {
        int[] lo = new int[maxfld];
        int[] hi = new int[maxfld];
        java.util.Arrays.fill(lo, Integer.MAX_VALUE);
        java.util.Arrays.fill(hi, -Integer.MAX_VALUE);
        int n = 0;
        for (FitsCard c : list) {
            if (c.isConsumed()) {
                continue;
            }
            List<Integer> fields = FitsMatch.matchKeyword(c.cName(), template, maxfld);
            if (fields != null) {
                n++;
                for (int k = 0; k < fields.size(); k++) {
                    lo[k] = Math.min(lo[k], fields.get(k));
                    hi[k] = Math.max(hi[k], fields.get(k));
                }
            }
        }
        return new KeyFields(n, lo, hi);
    }

    /** The first card not consumed with keyword {@code keyword}, ignoring case, or null. */
    FitsCard firstVisible(String keyword) {
        for (FitsCard c : list) {
            if (!c.isConsumed() && c.cName().equalsIgnoreCase(keyword)) {
                return c;
            }
        }
        return null;
    }

    /** C's {@code RetainFits}: retains the current card, so a read never consumes it. */
    void protectCurrent() {
        FitsCard c = current();
        if (c != null) {
            c.flags.retained = true;
        }
    }

    /**
     * The second part of C's {@code PurgeWCS}: gives every card not consumed whose keyword {@code
     * FindWcs} recognises the flag {@code USED} alone, so it is hidden and no longer retained.
     */
    void consumeWcsCards() {
        for (FitsCard c : list) {
            if (!c.isConsumed() && isFindWcsKeyword(c.keyword)) {
                c.flags = new FitsCard.Flags();
                c.flags.consumption = FitsCard.Consumption.USED;
            }
        }
    }

    /**
     * C's {@code DeleteCard}: deletes the current card, making the next card not consumed current;
     * nothing at end-of-file.
     */
    void deleteCard() {
        int card = pos;
        if (card >= list.size()) {
            return;
        }
        moveCard(1);
        list.remove(card);
        if (pos > card) {
            pos--;
        }
        if (list.isEmpty()) {
            pos = 0;
        }
    }

    /**
     * C's {@code InsCard}: with {@code overwrite}, deletes the current card; then inserts {@code
     * card} in front of the current card (appending at end-of-file), which stays current. The card
     * is flagged new while new cards are being marked.
     */
    void insCard(FitsCard card, boolean overwrite) {
        if (overwrite) {
            deleteCard();
        }
        if (markNew) {
            card.flags.newWrite = true;
            card.flags.newVersion = true;
        }
        int at = Math.min(pos, list.size());
        list.add(at, card);
        pos = at + 1;
    }

    /**
     * C's {@code FindKeyCard}: searches forward from the current card for one whose keyword matches
     * the template {@code name}, leaving it current, or end-of-file if there is none.
     *
     * @return whether a card was found
     * @throws AstException with status {@code AST__BDFMT} for a faulty template, after moving on
     *     from the card being tested, as C's loop does
     */
    boolean findKeyCard(String name, String method) {
        FitsCard c;
        while ((c = current()) != null) {
            boolean matched;
            try {
                matched = FitsMatch.matchKeywordChecked(c.cName(), name, method);
            } catch (AstException e) {
                moveCard(1);
                throw e;
            }
            if (matched) {
                return true;
            }
            moveCard(1);
        }
        return false;
    }

    /**
     * C's {@code SearchCard}: makes the card after the current one current if it has keyword
     * {@code name} (its first 8 characters, ignoring case); otherwise searches from the first card
     * with {@link #findKeyCard}.
     */
    boolean searchCard(String name, String method) {
        moveCard(1);
        FitsCard c = current();
        if (c != null) {
            String a = c.cName();
            a = a.length() > 8 ? a.substring(0, 8) : a;
            String b = name.length() > 8 ? name.substring(0, 8) : name;
            if (a.equalsIgnoreCase(b)) {
                return true;
            }
        }
        clearCard();
        return findKeyCard(name, method);
    }

    /** Whether the list is at end-of-file. */
    boolean atEof() {
        return pos >= list.size();
    }

    private static String nullIfEmpty(String comment) {
        return comment.isEmpty() ? null : comment;
    }

    /** Stores an integer as C's {@code SetValue}: overwriting a card with the keyword, or inserting. */
    void setInt(String keyword, long value, String comment) {
        putOrAppend(new FitsCard(keyword.toUpperCase(Locale.ROOT), FitsCard.Value.ofInt(value), nullIfEmpty(comment)));
    }

    /** Stores a floating value as {@link #setInt} stores an integer. */
    void setFloat(String keyword, double value, String comment) {
        noteWritten(keyword);
        putOrAppend(
                new FitsCard(keyword.toUpperCase(Locale.ROOT), FitsCard.Value.ofFloat(value), nullIfEmpty(comment)));
    }

    /** Stores a string as {@link #setInt} stores an integer. */
    void setString(String keyword, String value, String comment) {
        noteWritten(keyword);
        putOrAppend(
                new FitsCard(keyword.toUpperCase(Locale.ROOT), FitsCard.Value.ofString(value), nullIfEmpty(comment)));
    }

    /**
     * Puts the current card where a write inserts the cards it adds: one past the last card whose
     * keyword C's {@code FindWcs} recognises, or end-of-file if there is none.
     */
    void positionWriteCursor() {
        int last = -1;
        for (int k = 0; k < list.size(); k++) {
            if (isFindWcsKeyword(list.get(k).keyword)) {
                last = k;
            }
        }
        pos = last >= 0 ? last + 1 : list.size();
    }

    /**
     * Stores a card as C's {@code SetValue} does: overwriting a card with the same keyword (the card
     * after the current one, else the first, used or not), which keeps its comment if the value is
     * unchanged, or else inserting the card in front of the current card.
     */
    private void putOrAppend(FitsCard card) {
        if (markNew) {
            card.flags.newWrite = true;
            card.flags.newVersion = true;
        }
        int found = -1;
        if (pos + 1 < list.size() && list.get(pos + 1).keyword.equalsIgnoreCase(card.keyword)) {
            found = pos + 1;
        } else {
            for (int k = 0; k < list.size(); k++) {
                if (list.get(k).keyword.equalsIgnoreCase(card.keyword)) {
                    found = k;
                    break;
                }
            }
        }
        if (found >= 0) {
            FitsCard old = list.get(found);
            if (old.comment != null && valuesSimilar(old.value, card.value)) {
                card.comment = old.comment;
            }
            // SetValue (fitschan.c:28510) leaves the current card as it was.
            list.set(found, card);
        } else {
            int at = Math.min(pos, list.size());
            list.add(at, card);
            pos = at + 1;
        }
    }

    /**
     * Parses header text, one card per line; a line that cannot be read as a card is left out. A
     * card whose keyword name holds an illegal character, or whose value cannot be typed, is
     * preceded by {@code ASTWARN} cards describing the problem.
     */
    static FitsCardList fromHeader(String text) {
        return fromHeaderWithWarnings(text, new ArrayList<>());
    }

    /**
     * {@link #fromHeader}, adding to {@code warnings} the text of each {@code ASTWARN} block stored,
     * in order, as C's {@code Warn} adds it to the Channel's warnings.
     */
    static FitsCardList fromHeaderWithWarnings(String text, List<String> warnings) {
        FitsCardList list = new FitsCardList();
        for (String line : lines(text)) {
            FitsCard.Split split;
            try {
                split = FitsCard.split(line);
            } catch (AstException e) {
                continue;
            }
            String nameWarning = FitsCard.checkFitsName(line);
            if (nameWarning != null) {
                list.pos = list.list.size();
                list.pushAstwarn(nameWarning);
                warnings.add(nameWarning);
            }
            if (split.badValue) {
                String t = FitsCard.badValueWarning(line);
                list.pos = list.list.size();
                list.pushAstwarn(t);
                warnings.add(t);
            }
            // The setter PutFits hands the card to warns again of an illegal name; that block
            // matches the first, so adds no cards.
            if (nameWarning != null) {
                warnings.add(nameWarning);
            }
            list.push(split.card);
        }
        return list;
    }

    /**
     * The lines of {@code text}, as Rust's {@code str::lines} gives them: split at each newline,
     * a final {@code \r} removed from each line, and no line after a final newline.
     */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            int e = end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
            out.add(text.substring(start, e));
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // WCS keywords
    // ------------------------------------------------------------------

    private static boolean allDigits(String s) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean allDigitsOr(String s, char extra) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if ((c < '0' || c > '9') && c != extra) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUpper(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static final Set<String> FINDWCS_ALT = new HashSet<>(java.util.Arrays.asList(
            "LONPOLE", "LATPOLE", "EQUINOX", "RADESYS", "RESTFRQ", "WCSNAME", "SPECSYS", "SSYSSRC", "ZSOURCE",
            "VELOSYS", "MJD_AVG"));

    private static final Set<String> FINDWCS_EXACT = new HashSet<>(java.util.Arrays.asList(
            "LONGPOLE",
            "LONPOLE",
            "LATPOLE",
            "EPOCH",
            "EQUINOX",
            "MJD-OBS",
            "DATE-OBS",
            "TIMESYS",
            "RADECSYS",
            "RADESYS",
            "WCSNAME",
            "SPECSYS",
            "SSYSSRC",
            "ZSOURCE",
            "VELOSYS",
            "RESTFRQ",
            "MJD_AVG",
            "OBSGEO-X",
            "OBSGEO-Y",
            "OBSGEO-Z",
            "CNPIX1",
            "CNPIX2",
            "XPIXELSZ",
            "YPIXELSZ",
            "PLTRAH",
            "PLTRAM",
            "PLTRAS",
            "PLTDECD",
            "PLTDECM",
            "PLTDECS",
            "PLTDECSN",
            "PLTSCALE",
            "PPO1",
            "PPO2",
            "PPO4",
            "PPO5"));

    /**
     * Whether a keyword is one of those C's {@code FindWcs} (fitschan.c:10904-10960) recognises as
     * describing WCS, which places the cards a write adds and which {@code astPurgeWCS} removes.
     */
    static boolean isFindWcsKeyword(String keyword) {
        String kw = keyword.toUpperCase(Locale.ROOT);
        String bare = kw;
        if (!kw.isEmpty() && isUpper(kw.charAt(kw.length() - 1))) {
            String stripped = kw.substring(0, kw.length() - 1);
            if (FINDWCS_ALT.contains(stripped)) {
                return true;
            }
        }
        if (FINDWCS_EXACT.contains(bare)) {
            return true;
        }
        if (kw.length() >= 6) {
            String prefix5 = kw.substring(0, 5);
            if ("CRPIX".equals(prefix5)
                    || "CRVAL".equals(prefix5)
                    || "CDELT".equals(prefix5)
                    || "CTYPE".equals(prefix5)
                    || "CUNIT".equals(prefix5)) {
                boolean ok = true;
                for (int k = 5; k < kw.length(); k++) {
                    char c = kw.charAt(k);
                    if (!(c >= '0' && c <= '9') && !isUpper(c)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return true;
                }
            }
            if (kw.startsWith("CROTA")) {
                return true;
            }
        }
        if ((kw.startsWith("CD") || kw.startsWith("PC")) && kw.length() >= 4) {
            String rest = kw.substring(2);
            if ((rest.indexOf('_') >= 0 && allDigitsOr(rest, '_')) || allDigits(rest)) {
                return true;
            }
        }
        if ((kw.startsWith("PV") || kw.startsWith("PS")) && kw.length() >= 4) {
            String rest = kw.substring(2);
            if (rest.indexOf('_') >= 0 && allDigitsOr(rest, '_')) {
                return true;
            }
        }
        if (kw.startsWith("PROJP") || kw.startsWith("AMDX") || kw.startsWith("AMDY")) {
            return true;
        }
        if (kw.startsWith("PPO") && kw.length() >= 4 && allDigits(kw.substring(3))) {
            return true;
        }
        if (kw.length() >= 5 && kw.charAt(0) == 'C' && kw.charAt(1) >= '0' && kw.charAt(1) <= '9') {
            String suffix = kw.substring(2);
            return suffix.startsWith("VAL")
                    || suffix.startsWith("PIX")
                    || suffix.startsWith("ELT")
                    || suffix.startsWith("YPE")
                    || suffix.startsWith("NIT");
        }
        return false;
    }

    /**
     * Whether two values are the same for keeping a comment on overwrite, as C's {@code SetFits}
     * compares them (fitschan.c:15744): strings ignoring case and trailing spaces, floating values
     * with C's {@code EQUAL}, others exactly.
     */
    // MODERN-JAVA(21): a switch over a sealed Value hierarchy.
    private static boolean valuesSimilar(FitsCard.Value a, FitsCard.Value b) {
        if (a.kind != b.kind) {
            return false;
        }
        switch (a.kind) {
            case STRING:
                return FitsCard.trimEnd(a.s).equalsIgnoreCase(FitsCard.trimEnd(b.s));
            case FLOAT:
                return floatsSimilar(a.d, b.d);
            case INT:
            case KINT:
                return a.i == b.i;
            case LOGICAL:
                return a.b == b.b;
            case COMPLEX_FLOAT:
                return floatsSimilar(a.d, b.d) && floatsSimilar(a.dIm, b.dIm);
            case COMPLEX_INT:
                return a.i == b.i && a.iIm == b.iIm;
            default:
                return false;
        }
    }

    /**
     * C's {@code EQUAL} macro (object.h): {@code fabs(a - b) <= 1.0E5 * max((|a| + |b|) *
     * DBL_EPSILON, DBL_MIN)}.
     */
    private static boolean floatsSimilar(double a, double b) {
        if (a == b) {
            return true;
        }
        double scale = (Math.abs(a) + Math.abs(b)) * Math.ulp(1.0);
        return Math.abs(a - b) <= 1.0e5 * Math.max(scale, Double.MIN_NORMAL);
    }

    /**
     * Stores a value of any type as {@link #setFloat} stores a floating value: C's {@code SetValue}
     * given the data and type of another card, as {@code SpecTrans} copies a QVi_m card to PVi_m.
     */
    void setValue(String keyword, FitsCard.Value value, String comment) {
        noteWritten(keyword);
        putOrAppend(new FitsCard(keyword.toUpperCase(Locale.ROOT), value, nullIfEmpty(comment)));
    }
}
