package org.openucx.rdma;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class FileTransferUtil {
    private FileTransferUtil() {
    }

    static int nextChunkSize(long remaining, int preferredChunkSize) {
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(remaining, preferredChunkSize);
    }

    static byte[] readAllBytesInChunks(Path path, int chunkSize) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[chunkSize];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    static Path resolveSafePath(Path rootDir, String requestPath) {
        if (rootDir == null || requestPath == null) {
            return null;
        }
        String trimmed = requestPath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Path root = rootDir.toAbsolutePath().normalize();
        Path requested = Paths.get(trimmed);
        Path resolved = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }
}
