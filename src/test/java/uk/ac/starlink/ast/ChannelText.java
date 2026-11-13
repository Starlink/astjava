package uk.ac.starlink.ast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes Objects to native text and reads them back through a default Channel, as the Rust tests'
 * {@code native_dump} and {@code from_native_string} do.
 */
final class ChannelText {

    private ChannelText() {}

    /** The text a default Channel writes for {@code obj}. */
    static String dump(AstObject obj) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new Channel(null, out).write(obj);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** A Channel reading {@code text}. */
    static Channel reader(String text) {
        return new Channel(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), null);
    }

    /**
     * The first Object in {@code text}, read by a default Channel.
     *
     * @throws AstException with status {@code AST__RDERR} if the text holds no Object
     */
    static AstObject read(String text) {
        try {
            AstObject obj = reader(text).read();
            if (obj == null) {
                throw new AstException("no object found in input", AstException.AST__RDERR);
            }
            return obj;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The lines joined with newlines, each line ending with one. */
    static String text(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
