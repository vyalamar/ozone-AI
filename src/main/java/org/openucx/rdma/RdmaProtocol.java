package org.openucx.rdma;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class RdmaProtocol {
    static final long REQ_TAG = 0x1111L;
    static final long RESP_TAG = 0x2222L;
    static final long WARMUP_TAG = 0xFFFFL;
    static final long TAG_MASK = 0xFFFF_FFFF_FFFF_FFFFL;
    static final long OP_TIMEOUT_MS = readTimeoutMs();
    static final int DEFAULT_CHUNK_SIZE = 256 * 1024;

    private RdmaProtocol() {
    }

    static boolean isGetRequest(String request) {
        return request != null && request.startsWith("GET ");
    }

    static String getPathFromRequest(String request) {
        if (!isGetRequest(request)) {
            return null;
        }
        return request.substring(4).trim();
    }

    static byte[] buildGetRequest(String path) {
        return ("GET " + path).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeLength(long length) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(length);
        return buffer.array();
    }

    static long decodeLength(byte[] header) {
        if (header == null || header.length != Long.BYTES) {
            throw new IllegalArgumentException("Length header must be 8 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        return buffer.getLong();
    }

    private static long readTimeoutMs() {
        String raw = System.getenv("RDMA_OP_TIMEOUT_MS");
        if (raw == null || raw.trim().isEmpty()) {
            return 60000L;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : 60000L;
        } catch (NumberFormatException e) {
            return 60000L;
        }
    }
}
