package uk.ac.starlink.ast;

/**
 * The Channel attributes that control how {@link NativeWriter} renders a description: Comment,
 * Full and Indent.
 *
 * <p>Ported from Rust {@code serial/native_write.rs} ({@code RenderOptions}).
 */
// MODERN-JAVA(21): record
final class ChannelOptions {

    /** Whether each line ends with a comment saying what the item is. */
    final boolean comment;

    /** -1 to write no unset values, 0 to write the helpful ones, 1 to write all of them. */
    final int full;

    /** The indentation of each level of nesting; a negative value writes what 0 would. */
    final int indent;

    ChannelOptions(boolean comment, int full, int indent) {
        this.comment = comment;
        this.full = full;
        this.indent = Math.max(indent, 0);
    }

    /** The options of a default Channel: comments, Full 0 and Indent 3. */
    static ChannelOptions defaults() {
        return new ChannelOptions(true, 0, 3);
    }

    /** These options with Full set to {@code f}. */
    ChannelOptions withFull(int f) {
        return new ChannelOptions(comment, f, indent);
    }
}
