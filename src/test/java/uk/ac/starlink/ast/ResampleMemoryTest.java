package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Resampling a 2048 by 2048 {@code float} image through a non-linear Mapping with a kernel
 * interpolator, in a child JVM ({@link ResampleMemoryChild}) with a 64 MiB heap: the image and its
 * copy take 32 MiB, so the positions of the output pixels fit only a block at a time, as C's
 * blocks of output pixels allow.
 */
class ResampleMemoryTest {

    /** The child's heap, in MiB. */
    private static final String HEAP_MIB = "64";

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Runs {@link ResampleMemoryChild} with the serial collector and a small young generation, so
     * that the old generation, which holds the large arrays, has nearly all of the heap.
     */
    @Test
    void aLargeImageResamplesInASmallHeap() throws IOException, InterruptedException {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                java,
                "-XX:+UseSerialGC",
                "-Xmn4m",
                "-Xmx" + HEAP_MIB + "m",
                "-cp",
                System.getProperty("java.class.path"),
                ResampleMemoryChild.class.getName());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        assertEquals(0, p.waitFor(), out);
        assertTrue(out.contains("passed"), out);
    }
}
