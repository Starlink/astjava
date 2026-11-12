package uk.ac.starlink.ast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Reads Objects from text and writes them as text, in AST's native format.
 *
 * <p>Writing an Object to a Channel ({@link #write}) produces a textual description of it, and
 * reading from a Channel ({@link #read}) makes a new Object from such a description. A Channel
 * reads its text one line at a time through {@link #source} and writes it through {@link #sink}.
 * By default these read from and write to the streams given to the constructor; a subclass may
 * override them to read from and write to anything else. The attributes {@code SourceFile} and
 * {@code SinkFile}, set through {@link #set}, name a file to read or write instead.
 *
 * <p>The attributes of a Channel are:
 *
 * <ul>
 *   <li>{@code Comment}: whether written text includes comments (default 1).
 *   <li>{@code Full}: how much detail written text includes, -1, 0 or 1 (default 0).
 *   <li>{@code Indent}: the indentation of each level of nested Objects in written text (default
 *       3).
 *   <li>{@code ReportLevel}: which conditions met while reading are reported (default 1).
 *   <li>{@code Skip}: whether a read passes over data between Objects (default 0).
 *   <li>{@code Strict}: whether a reported condition is an error rather than a warning (default
 *       0).
 *   <li>{@code SourceFile} and {@code SinkFile}: files to read from and write to in place of the
 *       source and sink.
 * </ul>
 *
 * <p>Ported from Rust {@code channel/mod.rs}, {@code channel/base.rs} and {@code
 * channel/native.rs}, with the attribute handling of C's channel.c.
 */
public class Channel extends AstObject {

    /**
     * The stream {@link #source} reads and the lines handed back to the source, which a copy
     * shares, as a copy made by C's {@code astCopy} calls the same source function.
     */
    private final Input input;

    /** The stream {@link #sink} writes, or null for standard output. */
    private final OutputStream outStream;

    /** The Comment attribute, or null if unset. */
    private Boolean comment;

    /** The Full attribute (-1, 0 or 1), or null if unset. */
    private Integer full;

    /** The Indent attribute, or null if unset. */
    private Integer indent;

    /** The ReportLevel attribute, or null if unset. */
    private Integer reportLevel;

    /** The Skip attribute, or null if unset. */
    private Boolean skip;

    /** The Strict attribute, or null if unset. */
    private Boolean strict;

    /** The SourceFile attribute, or null if unset; a copy does not inherit it. */
    private String sourceFile;

    /** The SinkFile attribute, or null if unset; a copy does not inherit it. */
    private String sinkFile;

    /** The SourceFile, once a read has opened it. */
    private InputStream fdIn;

    /** The SinkFile, once a write has opened it. */
    private OutputStream fdOut;

    /** Lines a read handed back to the SourceFile, to be read again first. */
    private Deque<String> fileReturned = new ArrayDeque<>();

    /** The input of a Channel, shared by its copies. */
    private static final class Input {
        /** The stream {@link #source} reads, or null for standard input. */
        final InputStream stream;

        /** The reader over {@link #stream}, made by the first read. */
        BufferedReader reader;

        /** Lines a read handed back to the source, to be read again first. */
        final Deque<String> returned = new ArrayDeque<>();

        Input(InputStream stream) {
            this.stream = stream;
        }
    }

    /** The warnings from the most recent read or write. */
    private List<String> warningList = new ArrayList<>();

    /**
     * Creates a Channel that reads from {@code in} and writes to {@code out}.
     *
     * @param in the stream to read Objects from, or null for standard input
     * @param out the stream to write Objects to, or null for standard output
     */
    public Channel(InputStream in, OutputStream out) {
        this.input = new Input(in);
        this.outStream = out;
    }

    /**
     * Creates a Channel for a subclass that overrides {@link #source} and {@link #sink}. If it
     * does not, the Channel reads from standard input and writes to standard output.
     */
    protected Channel() {
        this(null, null);
    }

    /**
     * Creates a Channel for a subclass that reads and writes through its own means; the argument
     * is not used.
     *
     * @param dummy not used
     */
    protected Channel(Channel dummy) {
        this(null, null);
    }

    /**
     * Constructs a Channel from its description, as C's {@code astLoadChannel} does. It has no
     * source or sink, so reads standard input and writes standard output.
     */
    Channel(NativeNode node) {
        super(node);
        this.input = new Input(null);
        this.outStream = null;
        NativeNode part = node.section("Channel");
        // Each value is applied as its setter applies it.
        Long v = part.getIntIfSet("Indnt");
        if (v != null) {
            indent = (int) (long) v;
        }
        v = part.getIntIfSet("RpLev");
        if (v != null) {
            reportLevel = (int) (long) v;
        }
        v = part.getIntIfSet("Skip");
        if (v != null) {
            skip = v != 0;
        }
        v = part.getIntIfSet("Strict");
        if (v != null) {
            strict = v != 0;
        }
        v = part.getIntIfSet("Full");
        if (v != null) {
            full = Long.signum(v);
        }
        v = part.getIntIfSet("Comm");
        if (v != null) {
            comment = v != 0;
        }
    }

    @Override
    String dumpComment() {
        return "Basic I/O Channel";
    }

    /**
     * Writes the Object and Channel parts of a description, as channel.c {@code Dump} does: each
     * Channel attribute active when set, and otherwise its default, never helpful.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Object", "AST Object");
        node.writeInt("Indnt", indent != null, false, getIndent(), "Indentation increment");
        node.writeInt("RpLev", reportLevel != null, false, getReportLevel(), "Error reporting level");
        boolean b = getSkip();
        node.writeInt(
                "Skip",
                skip != null,
                false,
                b ? 1 : 0,
                b ? "Ignore data between Objects" : "No data allowed between Objects");
        b = getStrict();
        // The misspelling is C's, kept so that dumps match.
        String strictOn = "Report errors insead of warnings"; // codespell:ignore insead
        node.writeInt("Strict", strict != null, false, b ? 1 : 0, b ? strictOn : "Report warnings instead of errors");
        int f = getFull();
        String fullComment;
        if (f < 0) {
            fullComment = "Suppress non-essential output";
        } else if (f == 0) {
            fullComment = "Output standard information";
        } else {
            fullComment = "Output maximum information";
        }
        node.writeInt("Full", full != null, false, f, fullComment);
        b = getComment();
        node.writeInt("Comm", comment != null, false, b ? 1 : 0, b ? "Display comments" : "Omit comments");
    }

    /**
     * Completes a copy, which reads from and writes to the same source and sink as this Channel
     * but does not inherit the SourceFile and SinkFile attributes (channel.c {@code Copy}).
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        sourceFile = null;
        sinkFile = null;
        fdIn = null;
        fdOut = null;
        fileReturned = new ArrayDeque<>();
        warningList = new ArrayList<>(warningList);
    }

    // ------------------------------------------------------------------
    // Source and sink
    // ------------------------------------------------------------------

    /**
     * Reads one line of the text a {@link #read} reads. By default this reads the input stream
     * given to the constructor, or standard input. A subclass may override it to read from
     * anywhere else.
     *
     * @return the line, without its line terminator, or null when there is no more text
     * @throws IOException if the line cannot be read
     */
    protected String source() throws IOException {
        if (input.reader == null) {
            input.reader = new BufferedReader(
                    new InputStreamReader(input.stream == null ? System.in : input.stream, StandardCharsets.UTF_8));
        }
        return input.reader.readLine();
    }

    /**
     * Writes one line of the text a {@link #write} writes. By default this writes the line and a
     * newline to the output stream given to the constructor, or standard output. A subclass may
     * override it to write anywhere else.
     *
     * @param line the line, without a line terminator
     * @throws IOException if the line cannot be written
     */
    // The stream belongs to the caller, or is standard output, and stays open.
    @SuppressWarnings("PMD.CloseResource")
    protected void sink(String line) throws IOException {
        OutputStream out = outStream == null ? System.out : outStream;
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    /** C's line read from a file in {@code ReadData}: up to a newline, which is dropped. */
    private static String readFileLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c = in.read();
        if (c < 0) {
            return null;
        }
        while (c >= 0 && c != '\n') {
            buf.write(c);
            c = in.read();
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * The next line from the SourceFile, if set, or else the source (channel.c {@code ReadData}).
     * The SourceFile is opened by the first read that needs it.
     */
    private String readLine() throws IOException {
        if (sourceFile != null && fdIn == null) {
            if (fdOut != null && sourceFile.equals(sinkFile)) {
                throw Ast.err(
                        AstException.AST__RDERR,
                        "astRead(" + astClass() + "): Failed to open input SourceFile '" + sourceFile
                                + "' - the file is currently being used as the output SinkFile.");
            }
            try {
                fdIn = new BufferedInputStream(Files.newInputStream(Paths.get(sourceFile)));
            } catch (IOException e) {
                AstException err = Ast.err(
                        AstException.AST__RDERR,
                        "astRead(" + astClass() + "): Failed to open input SourceFile '" + sourceFile + "' - "
                                + e.getMessage() + ".");
                err.initCause(e);
                throw err;
            }
        }
        if (fdIn != null) {
            String line = fileReturned.pollFirst();
            return line != null ? line : readFileLine(fdIn);
        }
        String line = input.returned.pollFirst();
        return line != null ? line : source();
    }

    /** Hands {@code lines} back to the text being read, to be read again, in order, first. */
    private void returnLines(List<String> lines) {
        Deque<String> q = fdIn != null ? fileReturned : input.returned;
        for (int i = lines.size() - 1; i >= 0; i--) {
            q.addFirst(lines.get(i));
        }
    }

    /**
     * Writes {@code line} to the SinkFile, if set, or else the sink (channel.c {@code
     * WriteData}). The SinkFile is created by the first write that needs it.
     */
    private void writeLine(String line) throws IOException {
        if (sinkFile != null && fdOut == null) {
            try {
                fdOut = new BufferedOutputStream(Files.newOutputStream(Paths.get(sinkFile)));
            } catch (IOException e) {
                AstException err = Ast.err(
                        AstException.AST__WRERR,
                        "astWrite(" + astClass() + "): Failed to open output SinkFile '" + sinkFile + "' - "
                                + e.getMessage() + ".");
                err.initCause(e);
                throw err;
            }
        }
        if (fdOut != null) {
            fdOut.write(line.getBytes(StandardCharsets.UTF_8));
            fdOut.write('\n');
            return;
        }
        sink(line);
    }

    // C's fclose ignores a failure to close, as this does.
    @SuppressWarnings("PMD.EmptyCatchBlock")
    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                // Ignored, as C ignores it.
            }
        }
    }

    // ------------------------------------------------------------------
    // Reading and writing
    // ------------------------------------------------------------------

    /**
     * Reads the next Object from this Channel. The class of the Object depends on the text read.
     * The lines of one Object are read and the rest are left for the next read; with Skip set,
     * other lines before the Object are passed over. The warnings of the previous read or write
     * are discarded, and {@link #warnings} gives those of this one: each item in the description
     * that no class recognises is reported, as a warning or, with Strict set, as an error.
     *
     * @return the Object read, or null if there are no more Objects to read
     * @throws IOException if {@link #source} throws it
     * @throws AstException with status {@code AST__BADIN} if a line cannot be interpreted while
     *     Skip is not set, an Object does not start with a {@code Begin} line, its classes are out
     *     of order, its {@code End} line is missing, or Strict is set and an item is not
     *     recognised; {@code AST__EOCHN} if the text ends within an Object; {@code AST__LDERR} if
     *     an Object's {@code End} line comes before the data of all of its classes; {@code
     *     AST__OCLUK} if an Object's class is unknown; {@code AST__RDERR} if the SourceFile cannot
     *     be opened; or the error of making the Object from its description
     */
    public AstObject read() throws IOException {
        warningList.clear();
        String cls = astClass();
        NativeStream reader = new NativeStream(this::readLine, cls, getStrict(), getReportLevel());
        List<String> returned = new ArrayList<>();
        AstObject loaded;
        try {
            NativeStream.ReadObject object = reader.read(true, getSkip());
            if (object == null) {
                return null;
            }
            try {
                loaded = ObjectRegistry.load(object.node);
            } catch (AstException e) {
                // channel.c:2672
                throw Ast.withContext(e, "Error while reading a " + object.node.className + " from a " + cls + ".");
            }
            // C loads every nested Object it reads, so one no loader read is loaded here for its
            // reports. A class that cannot be loaded on its own here is left.
            NativeStream.Failure failure = reader.reportObject(object, node -> {
                try {
                    ObjectRegistry.load(node);
                } catch (AstException e) {
                    if (e.getStatus() != AstException.AST__LDERR) {
                        throw e;
                    }
                }
            });
            if (failure != null) {
                returned.addAll(reader.lines.subList(failure.lines, reader.lines.size()));
                throw Ast.withContext(
                        failure.error, "Error while reading a " + object.node.className + " from a " + cls + ".");
            }
        } finally {
            warningList = new ArrayList<>(reader.warnings);
            returnLines(returned);
        }
        return loaded;
    }

    /**
     * Writes an Object to this Channel, after any written before, as AST's native text. The
     * warnings of the previous read or write are discarded.
     *
     * @param obj the Object to write
     * @return the number of Objects written: 1
     * @throws IOException if {@link #sink} throws it
     * @throws AstException with status {@code AST__WRERR} if the SinkFile cannot be opened
     */
    public int write(AstObject obj) throws IOException {
        warningList.clear();
        String text = NativeWriter.render(NativeNode.of(obj), options());
        for (String line : NativeReader.lines(text)) {
            writeLine(line);
        }
        if (fdOut != null) {
            fdOut.flush();
        }
        return 1;
    }

    /** The options this Channel writes with. */
    ChannelOptions options() {
        return new ChannelOptions(getComment(), getFull(), getIndent());
    }

    /**
     * The text a Channel with the given Comment and Full and the default Indent writes for {@code
     * obj}.
     */
    static String text(AstObject obj, boolean comment, int full) {
        return NativeWriter.render(NativeNode.of(obj), new ChannelOptions(comment, full, 3));
    }

    /**
     * Returns the warnings from the most recent {@link #read} or {@link #write}, or null if there
     * were none. A warning reports a condition that did not stop the read or write but may leave
     * the Object read or written different from the original in some respect, such as an item in
     * a description that no class recognises, which is ignored. The ReportLevel attribute selects
     * the conditions reported, and the Strict attribute makes them errors instead.
     *
     * <p>The keys of the KeyMap are {@code Warning_1}, {@code Warning_2}, ... in the order the
     * warnings were issued, and each value is the text of one warning. A new KeyMap is made at
     * each call.
     *
     * @return a KeyMap holding the warnings, or null
     */
    public KeyMap warnings() {
        if (warningList.isEmpty()) {
            return null;
        }
        KeyMap km = new KeyMap();
        for (int i = 0; i < warningList.size(); i++) {
            km.mapPut0C("Warning_" + (i + 1), warningList.get(i), " ");
        }
        return km;
    }

    /** The texts of the warnings from the most recent read or write. */
    List<String> warningTexts() {
        return new ArrayList<>(warningList);
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Comment attribute: whether written text includes comments describing each item
     * (default true).
     *
     * @return the Comment attribute
     */
    public boolean getComment() {
        return comment == null || comment;
    }

    /**
     * Sets the Comment attribute.
     *
     * @param comment whether written text includes comments
     */
    public void setComment(boolean comment) {
        this.comment = comment;
    }

    /**
     * Gets the Full attribute: how much detail written text includes (default 0). With -1 no
     * default values are written; with 0 the default values useful to a reader are written as
     * comments; with 1 every default value is written as a comment, and the class marker of each
     * class that wrote no items is written too.
     *
     * @return the Full attribute, -1, 0 or 1
     */
    public int getFull() {
        return full == null ? 0 : full;
    }

    /**
     * Sets the Full attribute. Any positive value is stored as 1 and any negative one as -1.
     *
     * @param full the level of detail
     */
    public void setFull(int full) {
        this.full = Integer.signum(full);
    }

    /** The Indent attribute: the indentation of each level of nesting (default 3). */
    int getIndent() {
        return indent == null ? 3 : indent;
    }

    /** Sets the Indent attribute; any value is stored, a negative one writing what 0 would. */
    void setIndent(int indent) {
        this.indent = indent;
    }

    /**
     * Gets the ReportLevel attribute: which conditions met while reading are reported (default 1).
     * 0 reports none; 1 reports those that change significant information, such as an item in a
     * description that no class recognises; 2 also reports significant default values; 3 reports
     * any other condition of interest. A Channel meets only conditions of level 1.
     *
     * @return the ReportLevel attribute
     */
    public int getReportLevel() {
        return reportLevel == null ? 1 : reportLevel;
    }

    /**
     * Sets the ReportLevel attribute.
     *
     * @param reportLevel the level
     */
    public void setReportLevel(int reportLevel) {
        this.reportLevel = reportLevel;
    }

    /**
     * Gets the Skip attribute: whether a read passes over data between Objects (default false).
     * When it is false, the text must hold nothing but Object descriptions, comments and blank
     * lines.
     *
     * @return the Skip attribute
     */
    public boolean getSkip() {
        return skip != null && skip;
    }

    /**
     * Sets the Skip attribute.
     *
     * @param skip whether a read passes over data between Objects
     */
    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    /**
     * Gets the Strict attribute: whether a condition a read reports is an error rather than a
     * warning (default false).
     *
     * @return the Strict attribute
     */
    public boolean getStrict() {
        return strict != null && strict;
    }

    /**
     * Sets the Strict attribute.
     *
     * @param strict whether reported conditions are errors
     */
    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    /** The SourceFile attribute, or null when unset. */
    String getSourceFile() {
        return sourceFile;
    }

    /** Sets the SourceFile, closing any file open for the last one; the next read opens it. */
    void setSourceFile(String name) {
        closeQuietly(fdIn);
        fdIn = null;
        fileReturned.clear();
        sourceFile = name;
    }

    /** Clears the SourceFile, closing any file open for it, so that reads return to the source. */
    void clearSourceFile() {
        setSourceFile(null);
    }

    /** The SinkFile attribute, or null when unset. */
    String getSinkFile() {
        return sinkFile;
    }

    /** Sets the SinkFile, closing any file open for the last one; the next write creates it. */
    void setSinkFile(String name) {
        closeQuietly(fdOut);
        fdOut = null;
        sinkFile = name;
    }

    /** Clears the SinkFile, closing any file open for it, so that writes return to the sink. */
    void clearSinkFile() {
        setSinkFile(null);
    }

    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "comment":
                return getComment() ? "1" : "0";
            case "full":
                return Integer.toString(getFull());
            case "indent":
                return Integer.toString(getIndent());
            case "reportlevel":
                return Integer.toString(getReportLevel());
            case "skip":
                return getSkip() ? "1" : "0";
            case "sourcefile":
                return sourceFile == null ? "" : sourceFile;
            case "sinkfile":
                return sinkFile == null ? "" : sinkFile;
            case "strict":
                return getStrict() ? "1" : "0";
            default:
                return super.getAttrib(attrib);
        }
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        if ("comment".equals(attrib) && ival != null) {
            setComment(ival != 0);
        } else if ("full".equals(attrib) && ival != null) {
            setFull(ival);
        } else if ("indent".equals(attrib) && ival != null) {
            setIndent(ival);
        } else if ("reportlevel".equals(attrib) && ival != null) {
            setReportLevel(ival);
        } else if ("skip".equals(attrib) && ival != null) {
            setSkip(ival != 0);
        } else if ("sinkfile".equals(attrib) && value.indexOf('\n') < 0) {
            setSinkFile(value);
        } else if ("sourcefile".equals(attrib) && value.indexOf('\n') < 0) {
            setSourceFile(value);
        } else if ("strict".equals(attrib) && ival != null) {
            setStrict(ival != 0);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "comment":
                return comment != null;
            case "full":
                return full != null;
            case "indent":
                return indent != null;
            case "reportlevel":
                return reportLevel != null;
            case "skip":
                return skip != null;
            case "sourcefile":
                return sourceFile != null;
            case "sinkfile":
                return sinkFile != null;
            case "strict":
                return strict != null;
            default:
                return super.testAttrib(attrib);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "comment":
                comment = null;
                break;
            case "full":
                full = null;
                break;
            case "indent":
                indent = null;
                break;
            case "reportlevel":
                reportLevel = null;
                break;
            case "skip":
                skip = null;
                break;
            case "sourcefile":
                clearSourceFile();
                break;
            case "sinkfile":
                clearSinkFile();
                break;
            case "strict":
                strict = null;
                break;
            default:
                super.clearAttrib(attrib);
        }
    }
}
