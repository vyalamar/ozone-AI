package org.openucx.rdma;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RdmaProtocolTest {

    @Test
    void encodeDecodeLengthRoundTrip() {
        long[] values = {0L, 1L, 42L, 1024L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1};
        for (long value : values) {
            byte[] header = RdmaProtocol.encodeLength(value);
            assertEquals(Long.BYTES, header.length);
            assertEquals(value, RdmaProtocol.decodeLength(header));
        }
    }

    @Test
    void parseGetRequest() {
        assertTrue(RdmaProtocol.isGetRequest("GET /tmp/foo.bin"));
        assertEquals("/tmp/foo.bin", RdmaProtocol.getPathFromRequest("GET /tmp/foo.bin"));
        assertEquals("/tmp/foo.bin", RdmaProtocol.getPathFromRequest("GET   /tmp/foo.bin   "));
        assertFalse(RdmaProtocol.isGetRequest("POST /tmp/foo.bin"));
        assertNull(RdmaProtocol.getPathFromRequest("POST /tmp/foo.bin"));
    }
}
