package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Translation of the unit tests of Rust {@code fitschan/source_sink.rs}. */
class FitsChanSourceSinkTest {

    @Test
    void fgetsSplitsLongLinesAsCDoes() {
        byte[] in = "abc\ndefgh\n\nxy".getBytes(StandardCharsets.US_ASCII);
        List<String> got = new ArrayList<>();
        for (byte[] line : FitsChan.fgetsLines(in, 4)) {
            got.add(new String(line, StandardCharsets.US_ASCII));
        }
        assertEquals(Arrays.asList("abc\n", "defg", "h\n", "\n", "xy"), got);
    }

    @Test
    void chrLenDropsTrailingSpaceAndControl() {
        assertEquals(4, FitsCard.chrLen("AB C  \r\n"));
        assertEquals(0, FitsCard.chrLen(" \n"));
    }
}
