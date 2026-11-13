package uk.ac.starlink.ast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * An I/O Channel that uses FITS header cards to represent Objects.
 *
 * <p>A FitsChan holds a list of 80-character FITS header cards. Any sequence of cards may be held
 * apart from the {@code END} card, whose existence is implied. The Card attribute identifies a
 * current card, to which most operations apply, so that the cards can be visited in any order:
 * {@link #findFits} searches for a keyword, {@link #putFits}, {@link #putCards} and the {@code
 * setFits} methods add or replace cards, and {@link #delFits} deletes the current card.
 *
 * <p>The cards a FitsChan starts with come from the Iterator given to its constructor, which is
 * read through {@link #source} the first time the cards are used. When the FitsChan is no longer
 * needed, {@link #close} writes its remaining cards through {@link #sink}, or to the file named by
 * the SinkFile attribute, and deletes them. A subclass may override {@link #sink} to deliver the
 * cards somewhere useful; cards not written by {@code close} are lost.
 *
 * <p>Coordinate systems are described in FITS headers by several conventions, called encodings,
 * which the Encoding attribute chooses between. Reading an Object from a FitsChan with {@link
 * #read} and writing one with {@link #write} use the encoding; reading is destructive, removing the
 * cards that describe the Object read, unless they are kept with {@link #retainFits}.
 *
 * <p>Besides those of a Channel, the attributes of a FitsChan are AllWarnings, AltAxes, Card,
 * CardComm, CardName, CardType, CarLin, CDMatrix, Clean, DefB1950, Encoding, FitsAxisOrder,
 * FitsDigits, FitsRounding, FitsTol, ForceTab, IgnoreBadAlt, Iwc, Ncard, Nkey, PolyTan, SipOK,
 * SipReplace, TabOK and Warnings. A FitsChan's Full attribute defaults to -1 and its Skip
 * attribute to 1.
 *
 * <p>Ported from Rust {@code fitschan/mod.rs}, {@code fitschan/source_sink.rs} and {@code
 * fitschan/dump.rs}, with the attribute handling of C's fitschan.c.
 */
public class FitsChan extends Channel {

    /** The conventions by which a FitsChan describes Objects, with C's codes and names. */
    enum Encoding {
        NATIVE("NATIVE"),
        FITS_PC("FITS-PC"),
        DSS("DSS"),
        FITS_WCS("FITS-WCS"),
        FITS_IRAF("FITS-IRAF"),
        FITS_AIPS("FITS-AIPS"),
        FITS_AIPSPP("FITS-AIPS++"),
        FITS_CLASS("FITS-CLASS");

        private final String text;

        Encoding(String text) {
            this.text = text;
        }

        /** The encoding's name (fitschan.c {@code xencod}). */
        String cName() {
            return text;
        }

        /** The encoding called {@code name}, ignoring case, or null. */
        static Encoding fromName(String name) {
            for (Encoding e : values()) {
                if (e.text.equalsIgnoreCase(name)) {
                    return e;
                }
            }
            return null;
        }
    }

    /** The names of the AltAxes values, indexed by C's codes (fitschan.c {@code xaltax}). */
    private static final String[] ALT_AXES = {"ALL", "IDENT", "NONE"};

    /** The default of FitsDigits, C's {@code AST__DBL_DIG}. */
    private static final int DEFAULT_FITS_DIGITS = 17;

    /** The default of FitsTol, in pixels. */
    static final double FITS_TOL_DEFAULT = 0.1;

    /** The names the Encoding attribute accepts, in the order C's SetAttrib tries them. */
    private static final String[][] ENCODING_NAMES = {
        {"NATIVE", "NATIVE"},
        {"FITS-PC", "FITS-PC"},
        {"FITS_PC", "FITS-PC"},
        {"FITS-WCS", "FITS-WCS"},
        {"FITS_WCS", "FITS-WCS"},
        {"FITS-IRAF", "FITS-IRAF"},
        {"FITS_IRAF", "FITS-IRAF"},
        {"FITS-AIPS", "FITS-AIPS"},
        {"FITS_AIPS", "FITS-AIPS"},
        {"FITS-AIPS++", "FITS-AIPS++"},
        {"FITS_AIPS++", "FITS-AIPS++"},
        {"FITS-CLASS", "FITS-CLASS"},
        {"FITS_CLASS", "FITS-CLASS"},
        {"DSS", "DSS"},
    };

    /** The Iterator {@link #source} reads, or null for a copy or a FitsChan read from a dump. */
    private Iterator<?> cardIt;

    /** Whether the source has still to be read, C's non-NULL {@code source}. */
    private boolean sourcePending;

    /** Whether the source has been read and may be read again, C's {@code saved_source}. */
    private boolean sourceSaved;

    /** Whether {@link #sink} receives the cards; a copy has no sink, as in C. */
    private boolean hasSink;

    private FitsCardList cardList = new FitsCardList();

    /** The Encoding attribute, or null if unset. */
    private Encoding encodingSet;

    private int fitsDigits = DEFAULT_FITS_DIGITS;

    /** The FitsRounding attribute, or null if unset. */
    private Integer fitsRounding;

    /** The CDMatrix attribute, 0 or 1, or null if unset. */
    private Integer cdMatrix;

    /** The SipReplace attribute, 0 or 1, or null if unset. */
    private Integer sipReplace;

    /** The CarLin attribute, 0 or 1, or null if unset. */
    private Integer carLin;

    /** The FitsTol attribute, or null if unset. */
    private Double fitsTol;

    /** The DefB1950 attribute, 0 or 1, or null if unset. */
    private Integer defB1950;

    /** The PolyTan attribute, or null if unset. */
    private Integer polyTan;

    /** The IgnoreBadAlt attribute, any integer as C stores it, or null if unset. */
    private Integer ignoreBadAlt;

    /** The Warnings attribute, or null if unset. */
    private String warningConditions;

    /** The AltAxes attribute, C's code 0 (ALL), 1 (IDENT) or 2 (NONE), or null if unset. */
    private Integer altAxes;

    /** The Iwc attribute, 0 or 1, or null if unset. */
    private Integer iwc;

    /** The FitsAxisOrder attribute, or null if unset. */
    private String fitsAxisOrder;

    /** The Clean attribute, 0 or 1, or null if unset. */
    private Integer clean;

    /** The SipOK attribute, any integer as C stores it, or null if unset. */
    private Integer sipOk;

    /** The TabOK attribute, or null if unset. */
    private Integer tabOk;

    /** The ForceTab attribute, or null if unset. */
    private Integer forceTab;

    /** The FitsTables the {@code -TAB} algorithm uses, C's {@code tables}, or null if none was stored. */
    private KeyMap tables;

    /**
     * Creates a FitsChan whose cards are the Strings {@code cardIt} gives. They are read through
     * {@link #source}, as C reads a source function, the first time the cards are used, and
     * appended in order with the first of them current. Only the first 80 characters of each are
     * used.
     *
     * <p>The FitsChan takes the attribute settings in the {@code FITSCHAN_OPTIONS} environment
     * variable, if it is set: settings separated by commas, as {@link #set} takes them. The
     * variable is read when a thread makes its first FitsChan, and the value then is used for every
     * FitsChan the thread makes.
     *
     * @param cardIt the cards, or null for none
     * @throws AstException if a setting in {@code FITSCHAN_OPTIONS} is invalid
     */
    public FitsChan(Iterator<?> cardIt) {
        this(cardIt, true);
    }

    /**
     * Creates an empty FitsChan, as {@link #FitsChan(Iterator)} does with no cards.
     *
     * @throws AstException if a setting in {@code FITSCHAN_OPTIONS} is invalid
     */
    public FitsChan() {
        this(null, true);
    }

    // The settings of FITSCHAN_OPTIONS are applied to the FitsChan being made, as C's constructor
    // applies them.
    @SuppressWarnings("this-escape")
    private FitsChan(Iterator<?> cardIt, boolean applyEnv) {
        // With no Iterator the FitsChan has no source, as a C FitsChan made without a source
        // function has none, so its first use does not move the current card to end-of-file.
        this.cardIt = cardIt;
        this.sourcePending = cardIt != null;
        this.hasSink = overridesSink(getClass());
        if (applyEnv) {
            String settings = FitsChanOptions.envOptions();
            if (settings != null) {
                FitsChanOptions.apply(this, settings);
            }
        }
    }

    /**
     * Whether {@code cls}, a FitsChan class, overrides {@link #sink}. A FitsChan whose class does
     * not has no sink, as a C FitsChan made without a sink function has none, so that {@link
     * #close} does not format the cards only to discard them.
     */
    private static boolean overridesSink(Class<?> cls) {
        for (Class<?> c = cls; c != FitsChan.class && c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if ("sink".equals(m.getName()) && params.length == 1 && params[0] == String.class) {
                    return true;
                }
            }
        }
        return false;
    }

    /** An empty FitsChan with no attribute set, ignoring {@code FITSCHAN_OPTIONS}. */
    static FitsChan bare() {
        return new FitsChan(null, false);
    }

    /**
     * Constructs a FitsChan from its description, as C's {@code astLoadFitsChan} does: with no
     * source or sink, the attributes through their setters apart from FitsAxisOrder and Warnings,
     * which are stored as read, then the cards up to the first with no type, then any tables.
     */
    FitsChan(NativeNode node) {
        super(node);
        this.cardIt = null;
        this.hasSink = false;
        NativeNode part = node.section("FitsChan");
        String name = part.getStringIfSet("Encod");
        if (name != null && !"UNKNOWN".equals(name)) {
            Encoding e = Encoding.fromName(name);
            if (e == null) {
                throw illegal(name, "the FitsChan component 'Encod'");
            }
            encodingSet = e;
        }
        fitsAxisOrder = part.getStringIfSet("FAxOrd");
        name = part.getStringIfSet("AltAx");
        if (name != null) {
            int code = -1;
            for (int k = 0; k < ALT_AXES.length; k++) {
                if (ALT_AXES[k].equalsIgnoreCase(name)) {
                    code = k;
                }
            }
            if (code < 0) {
                throw illegal(name, "the FitsChan component 'AltAx'");
            }
            altAxes = code;
        }
        Long v = part.getIntIfSet("FitsDg");
        if (v != null) {
            fitsDigits = (int) (long) v;
        }
        v = part.getIntIfSet("FitsRn");
        if (v != null) {
            fitsRounding = Math.max(0, (int) (long) v);
        }
        v = part.getIntIfSet("DfB1950");
        if (v != null) {
            defB1950 = v != 0 ? 1 : 0;
        }
        v = part.getIntIfSet("TabOK");
        if (v != null) {
            tabOk = (int) (long) v;
        }
        v = part.getIntIfSet("FrcTab");
        if (v != null) {
            forceTab = (int) (long) v;
        }
        v = part.getIntIfSet("IgBdAl");
        if (v != null) {
            ignoreBadAlt = (int) (long) v;
        }
        v = part.getIntIfSet("CdMat");
        if (v != null) {
            cdMatrix = v != 0 ? 1 : 0;
        }
        v = part.getIntIfSet("CarLin");
        if (v != null) {
            carLin = v != 0 ? 1 : 0;
        }
        v = part.getIntIfSet("SipRpl");
        if (v != null) {
            sipReplace = v != 0 ? 1 : 0;
        }
        Double tol = part.getDoubleIfSet("FitsTol");
        if (tol != null) {
            fitsTol = Math.max(tol, 0.0);
        }
        v = part.getIntIfSet("PolyTan");
        if (v != null) {
            polyTan = (int) (long) v;
        }
        v = part.getIntIfSet("SipOK");
        if (v != null) {
            sipOk = (int) (long) v;
        }
        v = part.getIntIfSet("Iwc");
        if (v != null) {
            iwc = v != 0 ? 1 : 0;
        }
        v = part.getIntIfSet("Clean");
        if (v != null) {
            clean = v != 0 ? 1 : 0;
        }
        warningConditions = part.getStringIfSet("Warn");
        for (int n = 1; ; n++) {
            String ty = part.readString("Ty" + n, " ");
            if (" ".equals(ty)) {
                break;
            }
            FitsCard.CardType type = FitsCard.CardType.fromDumpName(ty);
            if (type == null) {
                throw illegal(ty, "a FitsChan keyword data type");
            }
            cardList.push(readCard(part, n, type));
        }
        cardList.setCard(part.readInt("Card", 0));
        NativeNode t = part.readObject("Tables");
        if (t != null) {
            AstObject obj = ObjectRegistry.loadNested(t, "Tables");
            if (!(obj instanceof KeyMap)) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astLoadFitsChan(FitsChan): The Tables item of a FitsChan is not a KeyMap.");
            }
            tables = (KeyMap) obj;
        }
    }

    /** The error C's {@code FindString} reports for an unknown name read from a dump. */
    private static AstException illegal(String name, String what) {
        return Ast.err(
                AstException.AST__RDERR, "astRead(Channel): Illegal value '" + name + "' supplied for " + what + ".");
    }

    /** Card {@code n} of type {@code type} from a dump, each missing item taking C's default. */
    // MODERN-JAVA(21): a switch expression over CardType.
    private static FitsCard readCard(NativeNode part, int n, FitsCard.CardType type) {
        String keyword = FitsCard.trimEnd(part.readString("Nm" + n, "        "));
        String comment = part.getStringIfSet("Cm" + n);
        FitsCard.Value value;
        switch (type) {
            case FLOAT:
                value = FitsCard.Value.ofFloat(part.readDouble("Dt" + n, AST__BAD));
                break;
            case STRING:
                value = FitsCard.Value.ofString(part.readString("Dt" + n, ""));
                break;
            case CONTINUE:
                value = FitsCard.Value.ofContinue(part.readString("Dt" + n, ""));
                break;
            case INT:
                value = FitsCard.Value.ofInt(part.readInt("Dt" + n, 0));
                break;
            case KINT:
                value = FitsCard.Value.ofKInt(part.readLong("Dt" + n, 0));
                break;
            case LOGICAL:
                value = FitsCard.Value.ofLogical(part.readInt("Dt" + n, 0) != 0);
                break;
            case COMPLEXF:
                value = FitsCard.Value.ofComplexFloat(
                        part.readDouble("Dr" + n, AST__BAD), part.readDouble("Di" + n, AST__BAD));
                break;
            case COMPLEXI:
                value = FitsCard.Value.ofComplexInt(part.readInt("Dr" + n, 0), part.readInt("Di" + n, 0));
                break;
            case COMMENT:
                value = FitsCard.Value.ofComment(comment == null ? "" : comment);
                break;
            case UNDEF:
            case NO_TYPE:
                value = FitsCard.Value.UNDEF;
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "astLoadFitsChan: unexpected card type " + type);
        }
        // Dumps made before AST V1.5 hold only the deletion flag, "Dl".
        long flags = part.readInt("Fl" + n, 0);
        if (flags == 0) {
            flags = part.readInt("Dl" + n, 0);
        }
        FitsCard card = new FitsCard(keyword, value, type == FitsCard.CardType.COMMENT ? null : comment);
        card.flags = FitsCard.Flags.fromCBits(flags);
        return card;
    }

    @Override
    String dumpComment() {
        return "I/O channels to FITS files";
    }

    /**
     * Writes the description of this FitsChan, as fitschan.c {@code Dump} does: the Object and
     * Channel parts, then its attributes, then every card, consumed ones included, with its name,
     * type, flags, value and comment, then any tables. As in C, the source is read first.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Channel", "Basic I/O Channel");
        readFromSource();
        node.writeInt("Card", true, true, cardList.getCard(), "Index of current card");
        node.writeString("Encod", encodingSet != null, true, encoding().cName(), "Encoding system");
        node.writeString(
                "FAxOrd", fitsAxisOrder != null, true, getFitsAxisOrder(), "Order of WCS axes in new FITS headers");
        node.writeInt("FitsDg", testFitsDigits(), true, fitsDigits, "No. of digits for floating point values");
        node.writeInt("FitsRn", fitsRounding != null, true, getFitsRounding(), "No. of digits guarded from rounding");
        node.writeString("AltAx", altAxes != null, true, ALT_AXES[getAltAxes()], "Creation of alternate axes");
        boolean b1950 = getDefB1950();
        node.writeInt(
                "DfB1950", defB1950 != null, true, b1950 ? 1 : 0, b1950 ? "Default to FK4 B1950" : "Default to ICRS");
        int ival = getTabOK();
        node.writeInt(
                "TabOK",
                tabOk != null,
                true,
                ival,
                ival > 0 ? "EXTVER value for -TAB headers" : "Do not support -TAB CTYPE codes");
        ival = getForceTab();
        node.writeInt(
                "FrcTab", forceTab != null, true, ival, ival != 0 ? "Force use of -TAB" : "Only use -TAB if necessary");
        ival = getIgnoreBadAlt();
        node.writeInt(
                "IgBdAl",
                ignoreBadAlt != null,
                true,
                ival,
                ival != 0 ? "Skip unreadable alternate axes" : "Abort on unreadable alternate axes");
        boolean cd = getCDMatrix();
        node.writeInt("CdMat", cdMatrix != null, true, cd ? 1 : 0, cd ? "Use CD Matrix" : "Use PC matrix");
        boolean car = getCarLin();
        node.writeInt(
                "CarLin",
                carLin != null,
                true,
                car ? 1 : 0,
                car ? "Use simple linear CAR projections" : "Use full FITS-WCS CAR projections");
        node.writeInt("SipRpl", sipReplace != null, true, getSipReplace() ? 1 : 0, "Replace SIP inverse coefficients?");
        node.writeDouble(
                "FitsTol", fitsTol != null, true, getFitsTol(), "[pixel] Max allowed departure from linearity");
        ival = getPolyTan();
        node.writeInt(
                "PolyTan",
                polyTan != null,
                false,
                ival,
                ival != 0 ? "Use distorted TAN convention" : "Use standard TAN convention");
        ival = getSipOK();
        node.writeInt(
                "SipOK",
                sipOk != null,
                false,
                ival,
                ival != 0 ? "Use SIP distortion convention" : "Ignore SIP keywords");
        boolean iw = getIwc();
        node.writeInt(
                "Iwc", iwc != null, true, iw ? 1 : 0, iw ? "Include an IWC Frame" : "Do not include an IWC Frame");
        node.writeInt("Clean", clean != null, false, getClean() ? 1 : 0, "Always remove used cards?");
        node.writeString("Warn", warningConditions != null, true, getWarnings(), "Warnings to be reported");
        int n = 1;
        for (FitsCard card : cardList.cards()) {
            writeCard(node, n++, card);
        }
        if (tables != null) {
            node.writeObject("Tables", true, true, NativeNode.of(tables), "A KeyMap holding associated binary tables");
        }
    }

    /** Writes card {@code n} of a dump. */
    // MODERN-JAVA(21): a switch expression over a sealed Value hierarchy.
    private static void writeCard(NativeNode node, int n, FitsCard card) {
        String vc = "FITS keyword value";
        node.writeString("Nm" + n, true, true, card.cName(), "FITS keyword name");
        node.writeString("Ty" + n, true, true, card.cardType().dumpName(), "FITS keyword data type");
        long flags = card.flags.cBits();
        if (flags != 0) {
            node.writeInt("Fl" + n, true, true, flags, "FITS keyword flags");
        }
        FitsCard.Value v = card.value;
        switch (v.kind) {
            case FLOAT:
                node.writeDouble("Dt" + n, true, true, v.d, vc);
                break;
            case STRING:
            case CONTINUE:
                node.writeString("Dt" + n, true, true, v.s, vc);
                break;
            case INT:
            case KINT:
                node.writeInt("Dt" + n, true, true, v.i, vc);
                break;
            case LOGICAL:
                node.writeInt("Dt" + n, true, true, v.b ? 1 : 0, vc);
                break;
            case COMPLEX_FLOAT:
                node.writeDouble("Dr" + n, true, true, v.d, "FITS keyword real value");
                node.writeDouble("Di" + n, true, true, v.dIm, "FITS keyword imaginary value");
                break;
            case COMPLEX_INT:
                node.writeInt("Dr" + n, true, true, v.i, "FITS keyword real value");
                node.writeInt("Di" + n, true, true, v.iIm, "FITS keyword imaginary value");
                break;
            case COMMENT:
            case UNDEF:
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "FitsChan dump: unexpected value kind " + v.kind);
        }
        String com = card.cComment();
        if (com != null) {
            node.writeString("Cm" + n, true, true, com, "FITS keyword comment");
        }
    }

    /**
     * Returns a deep copy of this FitsChan, holding its cards and attributes but no source or sink,
     * as C's {@code astCopy} gives: the source is read first, so that the copy holds its cards, and
     * the copy never writes its cards through {@link #sink}. As for any Channel, the SourceFile and
     * SinkFile attributes are not copied.
     *
     * @return the copy
     */
    @Override
    public AstObject copy() {
        readFromSource();
        return super.copy();
    }

    @Override
    void deepCopy() {
        super.deepCopy();
        cardList = cardList.copy();
        cardIt = null;
        sourcePending = false;
        sourceSaved = false;
        hasSink = false;
        if (tables != null) {
            tables = (KeyMap) tables.copy();
        }
    }

    // ------------------------------------------------------------------
    // Source and sink
    // ------------------------------------------------------------------

    /**
     * Gives the next card from the Iterator given to the constructor, or null when there are no
     * more. It is called repeatedly the first time the cards are used, until it gives null.
     *
     * @return the card, or null
     */
    @Override
    protected final String source() {
        return cardIt != null && cardIt.hasNext() ? (String) cardIt.next() : null;
    }

    /**
     * Disposes of one card. {@link #close} calls it for each card left in the FitsChan, unless the
     * SinkFile attribute is set. This implementation discards the card, and a FitsChan whose class
     * does not override it has no sink: {@code close} then neither formats nor writes the cards. A
     * subclass may override it to write the cards somewhere useful.
     *
     * @param line the 80-character card
     * @throws IOException if the card cannot be written
     */
    @Override
    protected void sink(String line) throws IOException {
        // A FitsChan with no sink discards its cards.
    }

    /**
     * C's {@code ReadFromSource}: if the source has not been read, appends its cards and makes the
     * first of them current.
     */
    void readFromSource() {
        if (!sourcePending) {
            return;
        }
        sourcePending = false;
        sourceSaved = true;
        cardList.setCard(Long.MAX_VALUE);
        int icard = cardList.getCard();
        String card;
        while ((card = source()) != null) {
            putFits(card, false);
        }
        cardList.setCard(icard);
    }

    /**
     * C's {@code astReadFits}: reads the source again, appending any cards it now gives and making
     * the first of them current. The SourceFile is not read again.
     */
    void readFits() {
        readFromSource();
        if (sourceSaved) {
            sourcePending = true;
        }
        readFromSource();
    }

    /**
     * C's {@code astWriteFits}: writes the cards to the SinkFile, if set, or else to the sink, then
     * deletes every card, even when the writing fails.
     *
     * @throws AstException with status {@code AST__WRERR} if the SinkFile cannot be created or a
     *     card cannot be written, or {@code AST__BDFTS} if a card cannot be formatted
     */
    void writeFits() {
        readFromSource();
        try {
            writeToSink();
        } finally {
            emptyFitsInner();
        }
    }

    /**
     * Writes the cards left in this FitsChan, those a read has not consumed, to the file named by
     * the SinkFile attribute, if it is set, or else through {@link #sink}, one card per line, and
     * then deletes every card, as C's {@code astAnnul} does when it deletes a FitsChan. A FitsChan
     * that is not closed loses its cards. It may still be used after it is closed.
     *
     * @throws AstException with status {@code AST__WRERR} if the SinkFile cannot be created or
     *     {@link #sink} throws an {@code IOException}, or {@code AST__BDFTS} if a card cannot be
     *     formatted; the cards are deleted anyway
     */
    public void close() {
        writeFits();
    }

    /** The text of each card a read has not consumed, as {@code astFindFits} with {@code %f} gives it. */
    private List<String> cardLines() {
        List<String> lines = new ArrayList<>();
        for (FitsCard card : cardList.cards()) {
            if (!card.isConsumed()) {
                lines.add(card.formatWithDigitsAndRounding(fitsDigits, getFitsRounding()));
            }
        }
        return lines;
    }

    /** C's {@code WriteToSink}: writes each card not consumed to the SinkFile, if set, or the sink. */
    private void writeToSink() {
        String sinkFile = getSinkFile();
        OutputStream fd = null;
        if (sinkFile != null) {
            try {
                fd = Files.newOutputStream(Paths.get(sinkFile));
            } catch (IOException | InvalidPathException e) {
                AstException err = Ast.err(
                        AstException.AST__WRERR,
                        "astDelete(FitsChan): Failed to open output SinkFile '" + sinkFile + "' - " + e.getMessage()
                                + ".");
                err.initCause(e);
                throw err;
            }
        }
        if (fd == null && !hasSink) {
            return;
        }
        try (OutputStream out = fd) {
            readFromSource();
            for (String line : cardLines()) {
                if (out != null) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                } else {
                    sink(line);
                }
            }
        } catch (IOException e) {
            AstException err = Ast.err(
                    AstException.AST__WRERR, "astDelete(FitsChan): Failed to write a card - " + e.getMessage() + ".");
            err.initCause(e);
            throw err;
        }
    }

    /**
     * C's {@code getline}-like splitting of {@code in} by {@code fgets( buf, max + 1, fd )}: lines
     * of at most {@code max} bytes, each with its newline, a longer line coming in pieces.
     */
    static List<byte[]> fgetsLines(byte[] in, int max) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i < in.length) {
            int start = i;
            while (i < in.length && i - start < max) {
                if (in[i++] == '\n') {
                    break;
                }
            }
            byte[] line = new byte[i - start];
            System.arraycopy(in, start, line, 0, line.length);
            out.add(line);
        }
        return out;
    }

    /**
     * Sets the SourceFile attribute and appends the cards in the file, one per line, leaving the
     * FitsChan at end-of-file, as fitschan.c {@code SetSourceFile} does. A line longer than a card
     * is read as several cards. The attribute is set even if the file cannot be opened.
     *
     * @throws AstException with status {@code AST__RDERR} if the file cannot be opened
     */
    @Override
    void setSourceFile(String name) {
        super.setSourceFile(name);
        byte[] data;
        try {
            data = Files.readAllBytes(Paths.get(name));
        } catch (IOException | InvalidPathException e) {
            AstException err = Ast.err(
                    AstException.AST__RDERR,
                    "astSetSourceFile(FitsChan): Failed to open input SourceFile '" + name + "' - " + e.getMessage()
                            + ".");
            err.initCause(e);
            throw err;
        }
        readFromSource();
        cardList.setCard(Long.MAX_VALUE);
        for (byte[] line : fgetsLines(data, FitsCard.FITS_CARD_LEN + 1)) {
            String s = new String(line, StandardCharsets.UTF_8);
            putFits(s.substring(0, FitsCard.chrLen(s)), false);
        }
    }

    // ------------------------------------------------------------------
    // Reading and writing Objects
    // ------------------------------------------------------------------

    /**
     * Reads an Object from this FitsChan, using the encoding the Encoding attribute gives, from
     * the current card on.
     *
     * <p>With the NATIVE encoding, the cards hold any number of Objects, and the next is read as a
     * Channel reads one: with Skip set, the cards before it are passed over; with it clear, any
     * but a comment card is an error. The cards it was read from are then deleted, and the card
     * after them is current.
     *
     * <p>With any other encoding, the cards hold at most one Object, a FrameSet, which is read only
     * when the first card is current; otherwise null is returned. When a FrameSet is read, the
     * cards it was made from are marked as used, so that later reads and writes pass over them,
     * and the FitsChan is left at end-of-file; when none is read, the first card is made current.
     *
     * <p>When the read fails or finds no Object, the cards it looked at are made available again,
     * unless the Clean attribute is set, when they are removed as after a successful read.
     *
     * @return the Object read, or null if there is none
     * @throws AstException as {@link Channel#read} does for the NATIVE encoding, with status {@code
     *     AST__BADIN} also if a long string has a {@code CONTINUE} card missing; or the error of
     *     making the FrameSet for another encoding
     * @throws UnsupportedOperationException if the encoding is not NATIVE and its reading is not
     *     available
     */
    @Override
    public AstObject read() {
        // fitschan.c:26007 Read.
        clearWarnings();
        readFromSource();
        Encoding enc = encoding();
        boolean remove = enc == Encoding.NATIVE;
        AstObject obj = null;
        try {
            if (enc == Encoding.NATIVE) {
                obj = readNative();
            } else if (!cardList.testCard()) {
                try {
                    obj = readForeign(enc);
                } finally {
                    // C's ClearCard does not check the error status, so a failed read rewinds too.
                    if (obj == null) {
                        cardList.clearCard();
                    } else {
                        cardList.setCard(Integer.MAX_VALUE);
                    }
                }
            }
        } finally {
            // An error leaves no Object, and the provisionally used cards are made available again
            // unless Clean is set.
            if (obj == null && !getClean()) {
                cardList.fixUsed(false, false);
            } else {
                cardList.fixUsed(true, remove);
            }
        }
        return obj;
    }

    /**
     * The next Object of the NATIVE encoding, read through the Channel reader from the cards
     * (fitschan.c {@code GetNextData}), or null if there is none.
     */
    private AstObject readNative() {
        FitsNativeReader data = new FitsNativeReader(cardList, getFitsRounding());
        NativeStream reader = new NativeStream(data, "FitsChan", getStrict(), getReportLevel());
        try {
            return readObject(reader, data::rewind);
        } catch (IOException e) {
            throw (AstException) Ast.err(AstException.AST__INTER, "astRead(FitsChan): " + e.getMessage())
                    .initCause(e);
        } finally {
            for (String w : reader.warnings) {
                addWarning(w);
            }
        }
    }

    /**
     * The FrameSet the cards describe in {@code enc}, an encoding other than NATIVE, or null if they
     * describe none (C's {@code FitsToStore} and {@code FsetFromStore}).
     *
     * @throws UnsupportedOperationException for an encoding whose reading is not available
     */
    private AstObject readForeign(Encoding enc) {
        // MODERN-JAVA(21): switch expression
        switch (enc) {
            case FITS_WCS:
                return FitsWcsRead.read(this);
            case FITS_PC:
            case FITS_IRAF:
            case FITS_AIPS:
            case FITS_AIPSPP:
            case FITS_CLASS:
            case DSS:
                throw new UnsupportedOperationException(
                        "FitsChan: reading the " + enc.cName() + " encoding is not available");
            default:
                throw Ast.err(AstException.AST__INTER, "astRead(FitsChan): unexpected encoding " + enc + ".");
        }
    }

    /**
     * Writes an Object to this FitsChan, using the encoding the Encoding attribute gives.
     *
     * @param obj the Object to write
     * @return the number of Objects written
     * @throws UnsupportedOperationException always: writing Objects to a FitsChan is out of scope
     */
    @Override
    public int write(AstObject obj) {
        throw new UnsupportedOperationException("FitsChan.write is out of scope");
    }

    // ------------------------------------------------------------------
    // Package-private access for the read and the tests
    // ------------------------------------------------------------------

    /** The cards, for the readers of the encodings. */
    FitsCardList cards() {
        return cardList;
    }

    /**
     * An empty list for the warnings {@code method} raises: with Strict in force, a warning that
     * {@link #issueWarning} would store becomes an error where it is raised, as C's {@code Warn} makes it.
     */
    FitsWarnings warnList(String method) {
        if (getStrict() && !getClean() && getReportLevel() >= 1) {
            return FitsWarnings.strict(getWarnings(), method);
        }
        return new FitsWarnings();
    }

    /**
     * C's {@code Warn}: unless Clean is set, a warning whose condition the Warnings attribute names
     * is added to the Channel's warnings, if ReportLevel is at least 1, and stored as an {@code
     * ASTWARN} block in front of the current card.
     *
     * @throws AstException with status {@code AST__BADIN} naming {@code method} instead, if Strict
     *     is set and ReportLevel is at least 1; nothing is then stored
     */
    void issueWarning(FitsWarnings.Warning warning, String method) {
        if (getClean() || warning.text.isEmpty() || !FitsWarnings.reports(getWarnings(), warning.condition)) {
            return;
        }
        if (getReportLevel() >= 1) {
            if (getStrict()) {
                throw FitsWarnings.strictError(warning.method != null ? warning.method : method, warning.text);
            }
            cardList.pushAstwarn(warning.text);
            addWarning(warning.text);
        } else {
            cardList.pushAstwarn(warning.text);
        }
    }

    /** The warnings C's {@code Split} gives for {@code card}: an illegal keyword name, then a bad value. */
    private void splitWarnings(String card, boolean badValue, String method) {
        String text = FitsCard.checkFitsName(card);
        if (text != null) {
            issueWarning(new FitsWarnings.Warning("badkeyname", text), method);
        }
        if (badValue) {
            issueWarning(new FitsWarnings.Warning("badkeyvalue", FitsCard.badValueWarning(card)), method);
        }
    }

    /**
     * The keyword and comment C's {@code Split} finds when it reads a setter's or getter's keyword
     * name as a card: the first 8 characters, in their own case, and any text after them. As for
     * any card, an illegal name or value is warned of.
     */
    private String[] splitName(String name, String method) {
        FitsCard.Split split = FitsCard.split(name, method);
        splitWarnings(name, split.badValue, method);
        String com = split.card.value.kind == FitsCard.Kind.COMMENT
                ? FitsCard.trim(split.card.value.s)
                : (split.card.comment == null ? "" : split.card.comment);
        String raw = name.length() > FitsCard.FITS_NAME_LEN ? name.substring(0, FitsCard.FITS_NAME_LEN) : name;
        return new String[] {FitsCard.trimEnd(raw), com};
    }

    /**
     * A FitsChan holding the cards of header text, one per line, with the first card current, made
     * as {@link #FitsChan()} makes one. A line that cannot be read as a card (one whose keyword name
     * holds an '=') is left out; the warnings for illegal keyword names and values are stored as
     * {@link #putFits} stores them.
     */
    static FitsChan fromHeader(String text) {
        FitsChan fc = new FitsChan();
        for (String line : FitsCardList.lines(text)) {
            FitsCard.Split split;
            try {
                split = FitsCard.split(line);
            } catch (AstException e) {
                continue;
            }
            fc.cardList.setPosition(Integer.MAX_VALUE);
            fc.splitWarnings(line, split.badValue, "astPutFits");
            // The setter PutFits hands the card to splits its keyword name again and warns again.
            fc.splitWarnings(line, false, "astPutFits");
            fc.cardList.push(split.card);
        }
        fc.cardList.clearCard();
        return fc;
    }

    /**
     * The cards a read has not consumed as a FITS header: 80-character lines joined by newlines,
     * with an {@code END} card added when there is none.
     */
    String toHeader() {
        List<String> lines = cardLines();
        boolean hasEnd = false;
        for (FitsCard card : cardList.cards()) {
            hasEnd |= !card.isConsumed() && "END".equals(card.keyword);
        }
        if (!hasEnd) {
            StringBuilder end = new StringBuilder("END");
            while (end.length() < FitsCard.FITS_CARD_LEN) {
                end.append(' ');
            }
            lines.add(end.toString());
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // The cards
    // ------------------------------------------------------------------

    /**
     * Stores a FITS header card, inserted in front of the current card or, with {@code overwrite},
     * replacing it; either way the card after the one stored becomes current, and at end-of-file
     * the card is appended. Only the first 80 characters of {@code card} are used, and trailing
     * non-printing characters are ignored. When a card is overwritten, a blank comment keeps the
     * comment of the card replaced.
     *
     * <p>A keyword name with an illegal character, or a value whose type cannot be determined, is
     * stored with a {@code BadKeyName} or {@code BadKeyValue} warning (see the Warnings attribute);
     * a value whose type cannot be determined gives a commentary card holding just the keyword.
     *
     * @param card the card
     * @param overwrite whether to replace the current card
     * @throws AstException with status {@code AST__BDFTS} if the keyword name (the first 8
     *     characters) holds an '=' before any other illegal character, or {@code AST__BADIN} for a
     *     warning when the Strict attribute is set
     */
    public void putFits(String card, boolean overwrite) {
        readFromSource();
        FitsCard.Split split = FitsCard.split(card);
        splitWarnings(card, split.badValue, "astPutFits");
        if (split.card.value.kind == FitsCard.Kind.COMMENT) {
            // PutFits hands a commentary card to SetFitsCom.
            String name = card.length() > FitsCard.FITS_NAME_LEN ? card.substring(0, FitsCard.FITS_NAME_LEN) : card;
            setFitsCom(name, split.card.value.s, overwrite);
        } else {
            // The setter the card is handed to splits its keyword name again, warning again.
            splitWarnings(card, false, "astPutFits");
            storeCard(
                    split.card.keyword,
                    split.card.value,
                    split.card.comment == null ? "" : split.card.comment,
                    overwrite);
        }
    }

    /**
     * Deletes every card and stores {@code cards}, then makes the first card current. The cards
     * are concatenated, 80 characters each with nothing between them, the last perhaps shorter, as
     * CFITSIO's {@code fits_hdr2str} gives them.
     *
     * @param cards the cards
     * @throws AstException as {@link #putFits}; the cards before the one in error are stored
     */
    public void putCards(String cards) {
        emptyFits();
        for (int i = 0; i < cards.length(); i += FitsCard.FITS_CARD_LEN) {
            putFits(cards.substring(i, Math.min(cards.length(), i + FitsCard.FITS_CARD_LEN)), true);
        }
        cardList.clearCard();
    }

    /**
     * Searches forward from the current card for one whose keyword matches the template {@code
     * name}, and returns the card's text. If one is found it becomes current, or with {@code inc}
     * the card after it does; if none is found the FitsChan is left at end-of-file. Clear the Card
     * attribute first to search the whole FitsChan.
     *
     * <p>The match ignores case and trailing spaces in {@code name}. A field specifier, {@code %}
     * followed by an optional single-digit width and a type, matches a range of characters: {@code
     * %c} upper case letters, {@code %d} decimal digits and {@code %f} any character allowed in a
     * keyword. With no width the field matches one or more characters, with width 0 zero or more,
     * and otherwise exactly that many. So {@code %f} matches any keyword, a blank one included,
     * while a blank template matches no keyword.
     *
     * @param name the keyword template
     * @param inc whether to make the card after the one found current
     * @return the card, formatted with FitsDigits and FitsRounding, or null if none is found
     * @throws AstException with status {@code AST__BDFMT} if {@code name} holds an illegal field
     *     specifier, or {@code AST__BDFTS} if the card found cannot be formatted
     */
    public String findFits(String name, boolean inc) {
        readFromSource();
        String lname = name;
        int n = lname.length();
        while (n > 0 && lname.charAt(n - 1) == ' ') {
            n--;
        }
        lname = lname.substring(0, n);
        boolean found;
        if ("%f".equals(lname) || "%0f".equals(lname)) {
            found = !cardList.atEof();
        } else {
            found = cardList.findKeyCard(lname, "astFindFits");
        }
        if (!found) {
            return null;
        }
        String text = cardList.current().formatWithDigitsAndRounding(fitsDigits, getFitsRounding());
        if (inc) {
            cardList.moveCard(1);
        }
        return text;
    }

    /**
     * Deletes the current card, making the next card current. At end-of-file this does nothing.
     */
    public void delFits() {
        readFromSource();
        cardList.deleteCard();
    }

    /**
     * Marks the current card so that a read does not remove it. A successful read removes the cards
     * it used to make the Object it returns unless they are so marked. The current card is not
     * changed, and at end-of-file this does nothing.
     */
    public void retainFits() {
        readFromSource();
        cardList.protectCurrent();
    }

    /**
     * Deletes every card describing WCS information in any encoding: each Object a read can find
     * is read with Clean set and discarded, which removes the cards it used even when the read
     * fails, and then every remaining card with a WCS keyword is removed as a read removes it. The
     * first remaining card is then current.
     *
     * @throws UnsupportedOperationException if the Encoding is not NATIVE and its reading is not
     *     available
     */
    public void purgeWCS() {
        readFromSource();
        Integer old = clean;
        clean = 1;
        try {
            cardList.clearCard();
            while (true) {
                AstObject obj;
                try {
                    obj = read();
                } catch (AstException e) {
                    break;
                }
                if (obj == null) {
                    break;
                }
                cardList.clearCard();
            }
            cardList.consumeWcsCards();
            cardList.clearCard();
        } finally {
            clean = old;
        }
    }

    /** C's {@code astEmptyFits}: deletes every card, consumed ones included, and every table. */
    void emptyFits() {
        readFromSource();
        emptyFitsInner();
    }

    private void emptyFitsInner() {
        cardList = new FitsCardList();
        tables = null;
    }

    /**
     * Tests whether keyword {@code name} has a value: as C's {@code astTestFits}, true if the
     * keyword is present with a value (a commentary card counts as having one), false if it is
     * absent or has no value. The current card is not changed.
     *
     * @param name the keyword, read as by the typed getters, or null for the current card
     * @return whether the keyword has a value
     * @throws AstException with status {@code AST__BDFTS} if the keyword name holds an '=', or
     *     {@code AST__BDFMT} if it holds an illegal field specifier
     */
    public boolean testFits(String name) {
        Boolean there = testFitsThere(name);
        return there != null && there;
    }

    /**
     * C's {@code astTestFits} with its {@code there} result: null if the keyword is absent (or, with
     * a null name, at end-of-file), false if it has no value, and true if it has one.
     */
    Boolean testFitsThere(String name) {
        readFromSource();
        String lname = name == null ? null : splitName(name, "astTestFits")[0];
        int icard = cardList.getCard();
        try {
            boolean found = lname == null || cardList.searchCard(lname, "astTestFits");
            if (!found) {
                return null;
            }
            FitsCard c = cardList.current();
            if (c == null || c.cardType() == FitsCard.CardType.NO_TYPE) {
                return null;
            }
            return c.cardType() != FitsCard.CardType.UNDEF;
        } finally {
            cardList.setCard(icard);
        }
    }

    // ------------------------------------------------------------------
    // Typed getters (C's astGetFits<X>)
    // ------------------------------------------------------------------

    /**
     * C's {@code MAKE_FGET}: finds the card with keyword {@code name} (the card after the current
     * one if it has the keyword, otherwise the first that does) and makes it current, or with a
     * null name uses the current card; then converts its value. Null if the keyword is absent,
     * leaving the FitsChan at end-of-file.
     */
    private FitsCardValue.Got getValue(String name, FitsCardValue.Want want, String method) {
        readFromSource();
        String lname = name == null ? null : splitName(name, method)[0];
        try {
            if (lname != null && !cardList.searchCard(lname, method)) {
                return null;
            }
            FitsCard current = cardList.current();
            String cardName = current == null ? "" : current.cName();
            FitsCardValue.Got got =
                    FitsCardValue.cnvType(current == null ? null : current.value, want, getFitsRounding(), cardName);
            if (got == null) {
                throw Ast.err(
                        AstException.AST__FTCNV,
                        method + "(FitsChan): Cannot convert FITS keyword '" + cardName + "' to " + want.cName() + ".");
            }
            return got;
        } catch (AstException e) {
            if (lname != null && !lname.isEmpty()) {
                throw Ast.withContext(e, method + "(FitsChan): Cannot get value for FITS keyword '" + lname + "'.");
            }
            throw e;
        }
    }

    /** C's {@code astGetFitsI}: the value as an {@code int}, or null if the keyword is absent. */
    Integer getFitsI(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.INT, "astGetFitsI");
        return g == null ? null : (int) g.i;
    }

    /** C's {@code astGetFitsK}: the value as a {@code long}, or null if the keyword is absent. */
    Long getFitsK(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.KINT, "astGetFitsK");
        return g == null ? null : g.i;
    }

    /**
     * C's {@code astGetFitsF}: the value as a {@code double}, or null if the keyword is absent.
     *
     * @throws AstException with status {@code AST__FUNDEF} if the card has no value, {@code
     *     AST__FTCNV} if the value cannot be converted or the card is a commentary card (or, with a
     *     null name, at end-of-file), {@code AST__BDFTS} if the keyword name holds an '=', or {@code
     *     AST__BDFMT} if it holds an illegal field specifier
     */
    Double getFitsF(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.FLOAT, "astGetFitsF");
        return g == null ? null : g.d;
    }

    /**
     * C's {@code astGetFitsS}: the value as a string, or null if the keyword is absent. A string of
     * 8 characters or fewer, trailing spaces included, is returned without its trailing spaces.
     */
    String getFitsS(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.STRING, "astGetFitsS");
        if (g == null) {
            return null;
        }
        if (g.s.length() <= 8) {
            int n = g.s.length();
            while (n > 0 && g.s.charAt(n - 1) == ' ') {
                n--;
            }
            return g.s.substring(0, n);
        }
        return g.s;
    }

    /** C's {@code astGetFitsCN}: the value as a {@code CONTINUE} string, or null. */
    String getFitsCN(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.CONTINUE, "astGetFitsCN");
        return g == null ? null : g.s;
    }

    /** C's {@code astGetFitsL}: the value as a logical, or null if the keyword is absent. */
    Boolean getFitsL(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.LOGICAL, "astGetFitsL");
        return g == null ? null : g.b;
    }

    /** C's {@code astGetFitsCF}: the real and imaginary parts of the value, or null. */
    double[] getFitsCF(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.COMPLEXF, "astGetFitsCF");
        return g == null ? null : new double[] {g.d, g.dIm};
    }

    /** C's {@code astGetFitsCI}: the real and imaginary parts of the value, or null. */
    int[] getFitsCI(String name) {
        FitsCardValue.Got g = getValue(name, FitsCardValue.Want.COMPLEXI, "astGetFitsCI");
        return g == null ? null : new int[] {(int) g.i, (int) g.iIm};
    }

    // ------------------------------------------------------------------
    // Typed setters (C's astSetFits<X>)
    // ------------------------------------------------------------------

    /**
     * C's {@code MAKE_FSET}: stores a card with keyword {@code name} and {@code value}. The name is
     * read as a card, so its first 8 characters are the keyword and, when {@code comment} is blank,
     * any text after them is the comment.
     */
    private void setValue(String name, FitsCard.Value value, String comment, boolean overwrite, String method) {
        readFromSource();
        String[] lname = splitName(name, method);
        String com = comment == null || FitsCard.isBlank(comment) ? lname[1] : comment;
        storeCard(lname[0], value, com, overwrite);
    }

    /**
     * Stores a card as {@link FitsCardList#put} stores one, its comment without leading and
     * trailing blanks, as C's {@code NewCard} keeps it for a card with a value.
     */
    private void storeCard(String keyword, FitsCard.Value value, String comment, boolean overwrite) {
        String com = FitsCard.trim(comment);
        cardList.put(new FitsCard(keyword.toUpperCase(Locale.ROOT), value, com.isEmpty() ? null : com), overwrite);
    }

    /**
     * Stores a complex floating value under keyword {@code name}, as {@link #setFits(String, double,
     * String, boolean)} stores a floating value.
     *
     * @param name the keyword
     * @param rval the real part
     * @param ival the imaginary part
     * @param comment the comment, or blank to keep that in {@code name} or of the card overwritten
     * @param overwrite whether to replace the current card rather than insert in front of it
     */
    public void setFits(String name, double rval, double ival, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofComplexFloat(rval, ival), comment, overwrite, "astSetFitsCF");
    }

    /**
     * Stores a complex integer value under keyword {@code name}, as {@link #setFits(String, double,
     * String, boolean)} stores a floating value.
     *
     * @param name the keyword
     * @param rval the real part
     * @param ival the imaginary part
     * @param comment the comment, or blank to keep that in {@code name} or of the card overwritten
     * @param overwrite whether to replace the current card rather than insert in front of it
     */
    public void setFits(String name, int rval, int ival, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofComplexInt(rval, ival), comment, overwrite, "astSetFitsCI");
    }

    /**
     * Stores a floating value under keyword {@code name}, at the current card.
     *
     * <p>{@code name} is read as a header card: its first 8 characters are the keyword, and any
     * text after them is the comment if {@code comment} is blank. If both are blank, a card that
     * overwrites another keeps its comment. Without {@code overwrite} the card is inserted in front
     * of the current card, which stays current; with it, the card replaces the current card and the
     * card after it becomes current. At end-of-file the card is appended. A keyword name with an
     * illegal character gives a {@code BadKeyName} warning.
     *
     * @param name the keyword
     * @param value the value
     * @param comment the comment
     * @param overwrite whether to replace the current card rather than insert in front of it
     * @throws AstException with status {@code AST__BDFTS} if the keyword name holds an '='
     */
    public void setFits(String name, double value, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofFloat(value), comment, overwrite, "astSetFitsF");
    }

    /**
     * Stores an integer value under keyword {@code name}, as {@link #setFits(String, double, String,
     * boolean)} stores a floating value.
     *
     * @param name the keyword
     * @param value the value
     * @param comment the comment
     * @param overwrite whether to replace the current card rather than insert in front of it
     */
    public void setFits(String name, int value, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofInt(value), comment, overwrite, "astSetFitsI");
    }

    /**
     * Stores a logical value under keyword {@code name}, as {@link #setFits(String, double, String,
     * boolean)} stores a floating value.
     *
     * @param name the keyword
     * @param value the value
     * @param comment the comment
     * @param overwrite whether to replace the current card rather than insert in front of it
     */
    public void setFits(String name, boolean value, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofLogical(value), comment, overwrite, "astSetFitsL");
    }

    /**
     * Stores a string value under keyword {@code name}, as {@link #setFits(String, double, String,
     * boolean)} stores a floating value.
     *
     * @param name the keyword
     * @param value the value
     * @param comment the comment
     * @param overwrite whether to replace the current card rather than insert in front of it
     */
    public void setFits(String name, String value, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofString(value), comment, overwrite, "astSetFitsS");
    }

    /**
     * Stores a {@code CONTINUE} string under keyword {@code name}: a string written without an
     * equals sign, as the continuation of a long string is; otherwise as {@link #setFits(String,
     * double, String, boolean)} stores a floating value.
     *
     * @param name the keyword
     * @param value the value
     * @param comment the comment
     * @param overwrite whether to replace the current card rather than insert in front of it
     */
    public void setFitsContinue(String name, String value, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofContinue(value), comment, overwrite, "astSetFitsCN");
    }

    /** C's {@code astSetFitsK}: stores a 64-bit integer value. */
    void setFitsK(String name, long value, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.ofKInt(value), comment, overwrite, "astSetFitsK");
    }

    /** C's {@code astSetFitsU}: stores a keyword with no value. */
    void setFitsU(String name, String comment, boolean overwrite) {
        setValue(name, FitsCard.Value.UNDEF, comment, overwrite, "astSetFitsU");
    }

    /** C's {@code astSetFitsCM}: stores a commentary card with a blank keyword. */
    void setFitsCM(String comment, boolean overwrite) {
        setFitsCom("", comment, overwrite);
    }

    /**
     * C's {@code SetFitsCom}: stores a commentary card with keyword {@code name}. When overwriting
     * a card with the same keyword, the card keeps its type and value and takes the new comment.
     */
    private void setFitsCom(String name, String comment, boolean overwrite) {
        readFromSource();
        String lname = splitName(name, "astSetFitsCom")[0];
        String com = comment == null || FitsCard.isBlank(comment) ? null : comment;
        FitsCard cur = cardList.current();
        boolean same = cur != null && cur.keyword.equals(lname);
        FitsCard card;
        if (!overwrite || !same) {
            card = new FitsCard(lname.toUpperCase(Locale.ROOT), FitsCard.Value.ofComment(com == null ? "" : com), null);
        } else if (cur.value.kind == FitsCard.Kind.COMMENT) {
            card = cur.copy();
            card.value = FitsCard.Value.ofComment(com == null ? "" : com);
        } else {
            card = cur.copy();
            String t = com == null ? null : FitsCard.trim(com);
            card.comment = t == null || t.isEmpty() ? null : t;
        }
        cardList.insCard(card, overwrite);
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Card attribute: the index of the current card, the first card being 1. At
     * end-of-file it is one more than Ncard, and it is 0 when there are no cards. Cards a read
     * consumed are not counted.
     *
     * @return the Card attribute
     */
    public int getCard() {
        readFromSource();
        return cardList.getCard();
    }

    /**
     * Sets the Card attribute, making card {@code card} current: the FitsChan is rewound and moved
     * forward {@code card - 1} cards, stopping at end-of-file. A value below 1 makes the first card
     * current. Clearing Card rewinds the FitsChan.
     *
     * @param card the index of the card
     */
    public void setCard(int card) {
        readFromSource();
        cardList.setCard(card);
    }

    /**
     * Gets the Ncard attribute: the number of cards, not counting those a read consumed.
     *
     * @return the number of cards
     */
    public int getNcard() {
        readFromSource();
        return cardList.ncard();
    }

    /** The Nkey attribute: the number of different keywords among the cards a read has not consumed. */
    int getNkey() {
        readFromSource();
        return cardList.nkey();
    }

    /** The CardName attribute: the keyword of the current card, or null at end-of-file. */
    String getCardName() {
        readFromSource();
        FitsCard c = cardList.current();
        return c == null ? null : c.cName();
    }

    /** The CardComm attribute: the comment of the current card, empty if it has none. */
    String getCardComm() {
        readFromSource();
        FitsCard c = cardList.current();
        String com = c == null ? null : c.cComment();
        return com == null ? "" : com;
    }

    /** The CardType attribute: the type of the value of the current card. */
    FitsCard.CardType getCardType() {
        readFromSource();
        FitsCard c = cardList.current();
        return c == null ? FitsCard.CardType.NO_TYPE : c.cardType();
    }

    /** The Encoding, set or chosen from the cards as C's {@code GetEncoding} chooses it. */
    Encoding encoding() {
        if (encodingSet != null) {
            return encodingSet;
        }
        readFromSource();
        return FitsGuessEncoding.guess(cardList);
    }

    /**
     * Gets the Encoding attribute: the conventions a read or write uses to describe Objects as
     * header cards, one of {@code NATIVE}, {@code FITS-WCS}, {@code FITS-PC}, {@code FITS-IRAF},
     * {@code FITS-AIPS}, {@code FITS-AIPS++}, {@code FITS-CLASS} and {@code DSS}.
     *
     * <p>When it is not set, it is chosen each time from the keywords of the cards a read has not
     * consumed. The first of these that holds gives it: a keyword beginning {@code BEGAST} gives
     * NATIVE; {@code DELTAV} and a velocity keyword ({@code VELO-OBS}, {@code VELO-HEL}, {@code
     * VELO-EAR}, {@code VELO-LSR} or {@code VLSR}), FITS-CLASS; a CTYPE holding an AIPS spectral axis
     * type, FITS-AIPS++ if there is also a {@code CDi_j}, {@code PROJPi}, {@code LONPOLE} or {@code
     * LATPOLE} keyword and FITS-AIPS otherwise; {@code PLTRAH}, DSS; {@code PCiiijjj}, FITS-PC; {@code
     * CDiiijjj}, FITS-IRAF; {@code CDi_j}, FITS-IRAF if there is also a {@code RADECSYS} keyword
     * without {@code RADESYS}, a {@code PROJPi} keyword without any {@code PVi_m}, or a {@code CjVALi}
     * keyword, and FITS-WCS otherwise; no {@code CDi_j} or {@code PCi_j} and one of those three
     * old-style forms, FITS-PC; {@code CROTAi}, FITS-AIPS; {@code CRVALi}, FITS-WCS; and otherwise
     * NATIVE. Each case but the first and DSS also needs a CTYPE, CRPIX or CRVAL keyword.
     *
     * @return the Encoding attribute
     */
    public String getEncoding() {
        return encoding().cName();
    }

    /**
     * Sets the Encoding attribute. The name is case insensitive, may use '_' for '-', and may be
     * abbreviated.
     *
     * @param encoding the encoding
     * @throws AstException with status {@code AST__BADAT} if the name is not that of an encoding
     */
    public void setEncoding(String encoding) {
        setC("Encoding", encoding);
    }

    /**
     * Gets the FitsDigits attribute: the number of significant digits a floating value is
     * formatted with in a header card (default 17, which loses no information). A negative value
     * is the most digits used: fewer are used where the value would otherwise be wider than the 20
     * characters the FITS standard recommends.
     *
     * @return the FitsDigits attribute
     */
    public int getFitsDigits() {
        return fitsDigits;
    }

    /**
     * Sets the FitsDigits attribute.
     *
     * @param fitsDigits the number of digits
     */
    public void setFitsDigits(int fitsDigits) {
        this.fitsDigits = fitsDigits;
    }

    /** Whether FitsDigits differs from its default: C tests the value, not whether one was set. */
    private boolean testFitsDigits() {
        return fitsDigits != DEFAULT_FITS_DIGITS;
    }

    /** The FitsRounding attribute (default 10). */
    int getFitsRounding() {
        return fitsRounding == null ? 10 : fitsRounding;
    }

    /** Sets FitsRounding; a negative value is stored as 0. */
    void setFitsRounding(int v) {
        fitsRounding = Math.max(0, v);
    }

    /**
     * Gets the Warnings attribute: the conditions met while reading or writing an Object or
     * keyword that are reported, a list of names separated by spaces (see {@link
     * #getAllWarnings}). A warning for a condition it names is added to those {@link #warnings}
     * gives and stored as {@code ASTWARN} cards in front of the current card; a warning for another
     * condition is not recorded at all, and none is while Clean is set. With Strict set, a reported
     * warning is instead an error. The default is {@code "BadKeyName BadKeyValue Tnx Zpx BadPV
     * BadCel BadMat BadCTYPE"}.
     *
     * @return the Warnings attribute
     */
    public String getWarnings() {
        return warningConditions == null ? FitsWarnings.DEFAULT_WARNINGS : warningConditions;
    }

    /**
     * Sets the Warnings attribute.
     *
     * @param warnings condition names separated by white space, in any case
     * @throws AstException with status {@code AST__ATTIN} if a name is not that of a condition; the
     *     attribute is then unchanged
     */
    public void setWarnings(String warnings) {
        FitsWarnings.goodWarns(warnings);
        warningConditions = warnings;
    }

    /**
     * Gets the read-only AllWarnings attribute: the name of every condition the Warnings attribute
     * accepts, in lower case, each with a space before and after it. The conditions are BadAlt (an
     * alternate axis description that cannot be read is left out), BadCel (an unknown celestial
     * system in CTYPE), BadCTYPE (an illegal algorithm code in CTYPE that can be converted), BadKeyName
     * (an illegal character in a keyword name), BadKeyValue (a value whose type cannot be
     * determined), BadLat (a reference latitude beyond 90 degrees), BadMat (a pixel to intermediate
     * world coordinate matrix that cannot be inverted), BadPV (projection parameters unsuited to the
     * projection), BadVal (a value that cannot be converted to the type expected), Distortion (an
     * unsupported distortion code), NoCTYPE, NoEquinox, NoRadesys, NoLonpole, NoLatpole and NoMjd-obs
     * (a default used for a missing keyword), Tnx and Zpx (unsupported terms of IRAF's TNX and ZPX
     * projections).
     *
     * @return the AllWarnings attribute
     */
    public String getAllWarnings() {
        return FitsWarnings.ALL_WARNINGS;
    }

    /**
     * Gets the CDMatrix attribute: whether a FITS-WCS write describes the scaling and rotation of
     * the pixel axes with {@code CDi_j} keywords rather than {@code PCi_j} and {@code CDELTi}. When
     * it is not set, it is true if the FitsChan holds a {@code CDi_j} card a read has not consumed.
     *
     * @return the CDMatrix attribute
     */
    public boolean getCDMatrix() {
        if (cdMatrix != null) {
            return cdMatrix != 0;
        }
        // C saves the current card before the search reads the source and puts it back after, so a
        // FitsChan at end-of-file, as one is before its source is read, is left there.
        int card0 = cardList.position();
        boolean atEof = card0 >= cardList.len();
        readFromSource();
        boolean found = cardList.keyFields("CD%1d_%1d", 0).n > 0;
        cardList.setPosition(atEof ? Integer.MAX_VALUE : card0);
        return found;
    }

    /**
     * Sets the CDMatrix attribute.
     *
     * @param cdMatrix whether to use {@code CDi_j} keywords
     */
    public void setCDMatrix(boolean cdMatrix) {
        this.cdMatrix = cdMatrix ? 1 : 0;
    }

    /**
     * Gets the CarLin attribute: whether a read of a foreign encoding takes a {@code CAR} projection
     * to be a linear transformation from pixel to celestial coordinates, leaving out the rotation
     * FITS-WCS paper II requires, as suits some older data (default false).
     *
     * @return the CarLin attribute
     */
    public boolean getCarLin() {
        return carLin != null && carLin != 0;
    }

    /**
     * Sets the CarLin attribute.
     *
     * @param carLin whether CAR projections are linear
     */
    public void setCarLin(boolean carLin) {
        this.carLin = carLin ? 1 : 0;
    }

    /**
     * Gets the DefB1950 attribute: whether a read of a foreign encoding takes RA and Dec axes whose
     * header gives no reference frame or equinox to be FK4 B1950 rather than ICRS. When it is not
     * set, it is false if the Encoding is FITS-WCS and true otherwise.
     *
     * @return the DefB1950 attribute
     */
    public boolean getDefB1950() {
        if (defB1950 != null) {
            return defB1950 != 0;
        }
        return encoding() != Encoding.FITS_WCS;
    }

    /**
     * Sets the DefB1950 attribute.
     *
     * @param defB1950 whether to default to FK4 B1950
     */
    public void setDefB1950(boolean defB1950) {
        this.defB1950 = defB1950 ? 1 : 0;
    }

    /**
     * Gets the Iwc attribute: whether a FrameSet read from a foreign encoding includes a Frame of
     * FITS-WCS intermediate world coordinates for each axis description, with Domain {@code IWC} or
     * {@code IWCa} (default false).
     *
     * @return the Iwc attribute
     */
    public boolean getIwc() {
        return iwc != null && iwc != 0;
    }

    /**
     * Sets the Iwc attribute.
     *
     * @param iwc whether to include IWC Frames
     */
    public void setIwc(boolean iwc) {
        this.iwc = iwc ? 1 : 0;
    }

    /** The Clean attribute: whether a failed read still removes the cards it used (default false). */
    boolean getClean() {
        return clean != null && clean != 0;
    }

    /** The FitsTol attribute: the largest departure from linearity, in pixels, a write allows. */
    double getFitsTol() {
        return fitsTol == null ? FITS_TOL_DEFAULT : fitsTol;
    }

    /** Sets FitsTol; a negative value is stored as 0. */
    void setFitsTol(double v) {
        fitsTol = Math.max(v, 0.0);
    }

    /** The SipReplace attribute (default true). */
    boolean getSipReplace() {
        return sipReplace == null || sipReplace != 0;
    }

    /** The PolyTan attribute (default -1). */
    int getPolyTan() {
        return polyTan == null ? -1 : polyTan;
    }

    /** The SipOK attribute, as C stores it (default 1). */
    int getSipOK() {
        return sipOk == null ? 1 : sipOk;
    }

    /** The IgnoreBadAlt attribute, as C stores it (default 0). */
    int getIgnoreBadAlt() {
        return ignoreBadAlt == null ? 0 : ignoreBadAlt;
    }

    /** The TabOK attribute (default 0). */
    int getTabOK() {
        return tabOk == null ? 0 : tabOk;
    }

    /** The ForceTab attribute (default 0). */
    int getForceTab() {
        return forceTab == null ? 0 : forceTab;
    }

    /** The AltAxes attribute as C's code: 0 ALL (the default), 1 IDENT, 2 NONE. */
    int getAltAxes() {
        return altAxes == null ? 0 : altAxes;
    }

    /** The FitsAxisOrder attribute (default {@code <auto>}). */
    String getFitsAxisOrder() {
        return fitsAxisOrder == null ? "<auto>" : fitsAxisOrder;
    }

    /** The Full attribute, whose default for a FitsChan is -1 (fitschan.c {@code GetFull}). */
    @Override
    public int getFull() {
        return testAttrib("full") ? super.getFull() : -1;
    }

    /** The Skip attribute, whose default for a FitsChan is true (fitschan.c {@code GetSkip}). */
    @Override
    public boolean getSkip() {
        return !testAttrib("skip") || super.getSkip();
    }

    /** Whether {@code value} (its trailing blanks ignored) abbreviates {@code name}, ignoring case. */
    private static boolean abbreviates(String value, String name) {
        int nc = FitsCard.chrLen(value);
        return nc <= name.length() && name.substring(0, nc).equalsIgnoreCase(value.substring(0, nc));
    }

    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "card":
                return Integer.toString(getCard());
            case "cardcomm":
                return getCardComm();
            case "cardname":
                return getCardName();
            case "cardtype":
                return Integer.toString(getCardType().code());
            case "encoding":
                return getEncoding();
            case "cdmatrix":
                return getCDMatrix() ? "1" : "0";
            case "defb1950":
                return getDefB1950() ? "1" : "0";
            case "tabok":
                return Integer.toString(getTabOK());
            case "forcetab":
                return Integer.toString(getForceTab());
            case "ignorebadalt":
                return Integer.toString(getIgnoreBadAlt());
            case "carlin":
                return getCarLin() ? "1" : "0";
            case "sipreplace":
                return getSipReplace() ? "1" : "0";
            case "fitstol":
                return CFormat.format("%.17g", getFitsTol());
            case "polytan":
                return Integer.toString(getPolyTan());
            case "sipok":
                return Integer.toString(getSipOK());
            case "iwc":
                return getIwc() ? "1" : "0";
            case "clean":
                return getClean() ? "1" : "0";
            case "fitsaxisorder":
                return getFitsAxisOrder();
            case "fitsdigits":
                return Integer.toString(fitsDigits);
            case "fitsrounding":
                return Integer.toString(getFitsRounding());
            case "altaxes":
                return ALT_AXES[getAltAxes()];
            case "ncard":
                return Integer.toString(getNcard());
            case "nkey":
                return Integer.toString(getNkey());
            case "allwarnings":
                return getAllWarnings();
            case "warnings":
                return getWarnings();
            default:
                return super.getAttrib(attrib);
        }
    }

    /** Whether {@code value} is one C's {@code "%*[^\n]"} matches whole: non-empty, without a newline. */
    private static boolean isLine(String value) {
        return !value.isEmpty() && value.indexOf('\n') < 0;
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        switch (attrib) {
            case "card":
                if (ival != null) {
                    setCard(ival);
                    return;
                }
                break;
            case "encoding":
                if (isLine(value)) {
                    for (String[] e : ENCODING_NAMES) {
                        if (abbreviates(value, e[0])) {
                            encodingSet = Encoding.fromName(e[1]);
                            return;
                        }
                    }
                    throw Ast.err(
                            AstException.AST__BADAT,
                            "astSet(" + astClass() + "): Unknown encoding system '" + value + "' requested for a "
                                    + astClass() + ".");
                }
                break;
            case "altaxes":
                if (isLine(value)) {
                    // C tries NONE, IDENT and ALL in turn.
                    for (int code : new int[] {2, 1, 0}) {
                        if (abbreviates(value, ALT_AXES[code])) {
                            altAxes = code;
                            return;
                        }
                    }
                    throw Ast.err(
                            AstException.AST__BADAT,
                            "astSet(" + astClass() + "): Illegal value '" + value
                                    + "' supplied for attribute AltAxes.");
                }
                break;
            case "fitsdigits":
                if (ival != null) {
                    fitsDigits = ival;
                    return;
                }
                break;
            case "fitsrounding":
                if (ival != null) {
                    setFitsRounding(ival);
                    return;
                }
                break;
            case "fitsaxisorder":
                if (isLine(value)) {
                    fitsAxisOrder = value;
                    return;
                }
                break;
            case "cdmatrix":
                if (ival != null) {
                    cdMatrix = ival != 0 ? 1 : 0;
                    return;
                }
                break;
            case "defb1950":
                if (ival != null) {
                    defB1950 = ival != 0 ? 1 : 0;
                    return;
                }
                break;
            case "tabok":
                if (ival != null) {
                    tabOk = ival;
                    return;
                }
                break;
            case "forcetab":
                if (ival != null) {
                    forceTab = ival;
                    return;
                }
                break;
            case "ignorebadalt":
                if (ival != null) {
                    ignoreBadAlt = ival;
                    return;
                }
                break;
            case "carlin":
                if (ival != null) {
                    carLin = ival != 0 ? 1 : 0;
                    return;
                }
                break;
            case "sipreplace":
                if (ival != null) {
                    sipReplace = ival != 0 ? 1 : 0;
                    return;
                }
                break;
            case "fitstol": {
                Double d = CScan.scanDouble(value);
                if (d != null) {
                    setFitsTol(d);
                    return;
                }
                break;
            }
            case "polytan":
                if (ival != null) {
                    polyTan = ival;
                    return;
                }
                break;
            case "sipok":
                if (ival != null) {
                    sipOk = ival;
                    return;
                }
                break;
            case "iwc":
                if (ival != null) {
                    iwc = ival != 0 ? 1 : 0;
                    return;
                }
                break;
            case "clean":
                if (ival != null) {
                    clean = ival != 0 ? 1 : 0;
                    return;
                }
                break;
            case "warnings":
                if (isLine(value)) {
                    setWarnings(value);
                    return;
                }
                break;
            case "ncard":
            case "cardtype":
            case "cardcomm":
            case "cardname":
            case "nkey":
            case "allwarnings":
                if (isLine(value)) {
                    throw Ast.err(
                            AstException.AST__NOWRT,
                            "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                                    + ". This is a read-only attribute.");
                }
                break;
            default:
                break;
        }
        super.setAttrib(attrib, value);
    }

    @Override
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "card":
                readFromSource();
                return cardList.testCard();
            case "encoding":
                return encodingSet != null;
            case "fitsaxisorder":
                return fitsAxisOrder != null;
            case "altaxes":
                return altAxes != null;
            case "fitsdigits":
                return testFitsDigits();
            case "fitsrounding":
                return fitsRounding != null;
            case "defb1950":
                return defB1950 != null;
            case "tabok":
                return tabOk != null;
            case "forcetab":
                return forceTab != null;
            case "ignorebadalt":
                return ignoreBadAlt != null;
            case "cdmatrix":
                return cdMatrix != null;
            case "carlin":
                return carLin != null;
            case "sipreplace":
                return sipReplace != null;
            case "fitstol":
                return fitsTol != null;
            case "polytan":
                return polyTan != null;
            case "sipok":
                return sipOk != null;
            case "iwc":
                return iwc != null;
            case "clean":
                return clean != null;
            case "warnings":
                return warningConditions != null;
            case "ncard":
            case "nkey":
            case "cardtype":
            case "cardcomm":
            case "cardname":
            case "allwarnings":
                return false;
            default:
                return super.testAttrib(attrib);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "card":
                readFromSource();
                cardList.clearCard();
                break;
            case "altaxes":
                altAxes = null;
                break;
            case "encoding":
                encodingSet = null;
                break;
            case "cdmatrix":
                cdMatrix = null;
                break;
            case "fitsaxisorder":
                fitsAxisOrder = null;
                break;
            case "fitsdigits":
                fitsDigits = DEFAULT_FITS_DIGITS;
                break;
            case "fitsrounding":
                fitsRounding = null;
                break;
            case "defb1950":
                defB1950 = null;
                break;
            case "tabok":
                tabOk = null;
                break;
            case "forcetab":
                forceTab = null;
                break;
            case "ignorebadalt":
                ignoreBadAlt = null;
                break;
            case "carlin":
                carLin = null;
                break;
            case "sipreplace":
                sipReplace = null;
                break;
            case "fitstol":
                fitsTol = null;
                break;
            case "polytan":
                polyTan = null;
                break;
            case "sipok":
                sipOk = null;
                break;
            case "iwc":
                iwc = null;
                break;
            case "clean":
                clean = null;
                break;
            case "warnings":
                warningConditions = null;
                break;
            case "ncard":
            case "allwarnings":
                throw Ast.err(
                        AstException.AST__NOWRT,
                        "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                                + ". This is a read-only attribute.");
            default:
                super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Iteration
    // ------------------------------------------------------------------

    /**
     * Returns an Iterator over the cards in this FitsChan, those a read has not consumed, from the
     * first. Each element is the 80-character text of a card, as {@link #findFits} gives it, and
     * the Iterator's {@code remove} deletes from the FitsChan the card last returned. Iterating
     * leaves the current card as it was, unless {@code remove} deletes it, when the card after it
     * becomes current. The Iterator should not be used while cards are being added.
     *
     * @return the Iterator
     */
    public Iterator<String> iterator() {
        readFromSource();
        return new CardIterator();
    }

    /** The index of {@code card} in the list of cards, by identity, or -1. */
    // A card is found by identity: two cards may hold the same values.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private int indexOf(FitsCard card) {
        List<FitsCard> list = cardList.cards();
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k) == card) {
                return k;
            }
        }
        return -1;
    }

    /** The Iterator {@link #iterator} gives. */
    private final class CardIterator implements Iterator<String> {
        /** The index in the list of cards of the next card to look at. */
        private int index;

        /** The card last returned, or null if there is none to remove. */
        private FitsCard last;

        @Override
        public boolean hasNext() {
            List<FitsCard> list = cardList.cards();
            while (index < list.size() && list.get(index).isConsumed()) {
                index++;
            }
            return index < list.size();
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            last = cardList.cards().get(index++);
            return last.formatWithDigitsAndRounding(fitsDigits, getFitsRounding());
        }

        @Override
        // Identity tells whether the card removed is the current one.
        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            int at = indexOf(last);
            if (at >= 0) {
                FitsCard current = cardList.current();
                cardList.setPosition(at);
                cardList.deleteCard();
                if (current != last) {
                    cardList.setPosition(current == null ? Integer.MAX_VALUE : indexOf(current));
                }
                if (at < index) {
                    index--;
                }
            }
            last = null;
        }
    }
}
