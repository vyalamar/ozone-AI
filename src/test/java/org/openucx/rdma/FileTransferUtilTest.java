package org.openucx.rdma;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileTransferUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void nextChunkSizeRespectsRemaining() {
        assertEquals(0, FileTransferUtil.nextChunkSize(0, 1024));
        assertEquals(0, FileTransferUtil.nextChunkSize(-1, 1024));
        assertEquals(64, FileTransferUtil.nextChunkSize(64, 1024));
        assertEquals(1024, FileTransferUtil.nextChunkSize(4096, 1024));
    }

    @Test
    void readAllBytesInChunksReturnsFullContent() throws Exception {
        Path file = tempDir.resolve("payload.bin");
        byte[] data = new byte[8192 + 123];
        new Random(42).nextBytes(data);
        Files.write(file, data);

        byte[] read = FileTransferUtil.readAllBytesInChunks(file, 257);
        assertArrayEquals(data, read);
    }

    @Test
    void readAllBytesInChunksHandlesEmptyFile() throws Exception {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        byte[] read = FileTransferUtil.readAllBytesInChunks(file, 128);
        assertEquals(0, read.length);
    }

    @Test
    void resolveSafePathBlocksTraversal() {
        Path root = tempDir.resolve("root");
        Path ok = FileTransferUtil.resolveSafePath(root, "file.txt");
        assertEquals(root.resolve("file.txt").normalize(), ok);

        Path okAbs = FileTransferUtil.resolveSafePath(root, root.resolve("ok.bin").toString());
        assertEquals(root.resolve("ok.bin").normalize(), okAbs);

        assertNull(FileTransferUtil.resolveSafePath(root, "../etc/passwd"));
        assertNull(FileTransferUtil.resolveSafePath(root, "/etc/passwd"));
    }
}
