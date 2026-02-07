package org.openucx.rdma;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openucx.jucx.UcxUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration-style test that starts a UCX server and client in-process
 * and verifies that a message round-trips correctly.
 *
 * If UCX native libraries are not available on the machine, the test
 * is skipped gracefully.
 */
public class UcxRdmaClientServerTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 23456;

    private static UcxRdmaServer server;

    @BeforeAll
    static void setUp() throws Exception {
        // Skip if native UCX libs cannot be loaded.
        Assumptions.assumeTrue(
                tryLoadNative(),
                "UCX native libraries are not available, skipping UCX RDMA test.");

        server = new UcxRdmaServer(HOST, PORT);

        // Wait for a client connection in a background thread once the test starts.
        // The actual client will connect inside the test method.
    }

    private static boolean tryLoadNative() {
        try {
            // Trigger native libs load early; this is essentially a no-op if already loaded.
            UcxUtils.getAddress(java.nio.ByteBuffer.allocateDirect(1));
            return true;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | ExceptionInInitializerError e) {
            return false;
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testClientServerMessageExchange() throws Exception {
        final String message = "Hello UCX RDMA";
        System.out.println(message);
        Thread serverThread = new Thread(() -> {
            try {
                // Wait until a client connects.
                server.awaitConnection(10);
                // Receive a single message and echo it back.
                server.receiveAndEcho(1024);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "ucx-server-thread");

        serverThread.start();

        String reply;
        try (UcxRdmaClient client = new UcxRdmaClient(HOST, PORT)) {
            reply = client.sendAndReceive(message, 1024);
            System.out.println(reply);
        }

        serverThread.join(10_000);

        assertEquals(message, reply);
    }
}

