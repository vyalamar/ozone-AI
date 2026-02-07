package org.openucx.rdma;

import org.openucx.jucx.UcxCallback;
import org.openucx.jucx.ucp.*;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Minimal UCX/JUCX-based client that connects to a {@link UcxRdmaServer},
 * sends a single message and waits for a reply using tagged operations.
 *
 * This class uses worker-address exchange to enable RDMA/EFA transport
 * selection by UCX, rather than socket-address endpoints which force TCP.
 */
public class UcxRdmaClient implements Closeable {

    private final UcpContext context;
    private final UcpWorker worker;
    private final UcpEndpoint endpoint;
    private final Socket bootstrapSocket;
    private final DataInputStream bootstrapIn;
    private final DataOutputStream bootstrapOut;
    private volatile boolean warmupCompleted;

    public UcxRdmaClient(String host, int port) throws Exception {
        UcxNative.load();
        UcpParams params = new UcpParams()
                .requestTagFeature()
                .setMtWorkersShared(true);

        this.context = new UcpContext(params);
        this.worker = context.newWorker(new UcpWorkerParams());

        // --- Worker Address Exchange (enables EFA/RDMA) ---
        // 1. Connect to server via plain TCP socket for bootstrap
        this.bootstrapSocket = new Socket();
        bootstrapSocket.connect(new InetSocketAddress(host, port));
        bootstrapSocket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, RdmaProtocol.OP_TIMEOUT_MS));
        System.out.println("Bootstrap socket connected to " + host + ":" + port);

        this.bootstrapIn = new DataInputStream(bootstrapSocket.getInputStream());
        this.bootstrapOut = new DataOutputStream(bootstrapSocket.getOutputStream());

        // 2. Get our worker address and send it to server
        System.out.println("Obtaining local UCX worker address...");
        byte[] localAddrBytes = extractAddressBytes(worker.getAddress());
        System.out.println("Local UCX worker address length=" + localAddrBytes.length);

        bootstrapOut.writeInt(localAddrBytes.length);
        bootstrapOut.write(localAddrBytes);
        bootstrapOut.flush();
        System.out.println("Sent local UCX worker address to server");

        // 3. Receive server's worker address
        System.out.println("Waiting for server UCX worker address...");
        int serverAddrLen = bootstrapIn.readInt();
        if (serverAddrLen <= 0) {
            throw new IllegalStateException("Received empty server UCX worker address");
        }
        byte[] serverAddrBytes = new byte[serverAddrLen];
        bootstrapIn.readFully(serverAddrBytes);
        System.out.println("Received server UCX worker address length=" + serverAddrLen);

        // 4. Create endpoint from server's worker address (NOT socket address!)
        // This allows UCX to select optimal transport (EFA SRD if available)
        UcpEndpointParams epParams = new UcpEndpointParams()
                .setUcpAddress(createDirectAddressBuffer(serverAddrBytes))
                .setPeerErrorHandlingMode();

        this.endpoint = worker.newEndpoint(epParams);
        System.out.println("UCX endpoint created on client");
        flushEndpoint("client");

        warmupConnection();

        // Keep bootstrap socket open for a lightweight barrier signal.
    }

    /**
     * Sends the given message to the server and waits for a reply of up to maxReplyLength bytes.
     *
     * @param message        message to send (UTF-8 encoded on the wire)
     * @param maxReplyLength maximum expected reply length in bytes
     * @return reply as string
     */
    public String sendAndReceive(String message, int maxReplyLength) throws Exception {
        byte[] payload = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Prepare send buffer.
        ByteBuffer sendBuffer = ByteBuffer.allocateDirect(payload.length);
        sendBuffer.put(payload);
        sendBuffer.rewind();

        System.out.println("Waiting for server ready signal (request)...");
        awaitServerSignal("request", 2);
        System.out.println("Received server ready signal (request)");

        // Post tagged send on tag 0.
        System.out.println("Posting tagged send of " + payload.length + " bytes tag=" + RdmaProtocol.REQ_TAG);
        UcpRequest sendReq = endpoint.sendTaggedNonBlocking(
                sendBuffer,
                RdmaProtocol.REQ_TAG,
                new UcxCallback() {});

        // Prepare receive buffer for reply.
        ByteBuffer recvBuffer = ByteBuffer.allocateDirect(maxReplyLength);
        System.out.println("Posting tagged recv up to " + maxReplyLength + " bytes tag=" + RdmaProtocol.RESP_TAG);
        UcpRequest recvReq = worker.recvTaggedNonBlocking(
                recvBuffer,
                RdmaProtocol.RESP_TAG,
                RdmaProtocol.TAG_MASK,
                new UcxCallback() {});

        // Progress until both operations are completed.
        long start = System.currentTimeMillis();
        long lastLog = start;
        long spins = 0;
        while (!sendReq.isCompleted() || !recvReq.isCompleted()) {
            worker.progress();
            spins++;
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000) {
                System.out.println("Waiting for send/recv... elapsed=" + (now - start) + "ms spins=" + spins);
                lastLog = now;
            }
            if (now - start > RdmaProtocol.OP_TIMEOUT_MS) {
                throw new IllegalStateException("Timed out waiting for send/recv completion");
            }
        }
        System.out.println("Send/recv completed");

        long bytesReceived = recvReq.getRecvSize();
        recvBuffer.limit((int) bytesReceived);
        recvBuffer.rewind();

        byte[] replyBytes = new byte[(int) bytesReceived];
        recvBuffer.get(replyBytes);

        return new String(replyBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public long requestFile(String remotePath, Path localPath, int chunkSize) throws Exception {
        int effectiveChunkSize = chunkSize <= 0 ? RdmaProtocol.DEFAULT_CHUNK_SIZE
                : Math.max(chunkSize, RdmaProtocol.DEFAULT_CHUNK_SIZE);
        if (effectiveChunkSize != chunkSize) {
            System.out.println("Adjusted chunkSize from " + chunkSize + " to " + effectiveChunkSize);
        }
        byte[] payload = RdmaProtocol.buildGetRequest(remotePath);

        ByteBuffer sendBuffer = ByteBuffer.allocateDirect(payload.length);
        sendBuffer.put(payload);
        sendBuffer.rewind();

        System.out.println("Requesting file '" + remotePath + "' chunkSize=" + effectiveChunkSize);
        System.out.println("Waiting for server ready signal (file request)...");
        awaitServerSignal("file request", 2);
        System.out.println("Received server ready signal (file request)");
        UcpRequest sendReq = endpoint.sendTaggedNonBlocking(
                sendBuffer,
                RdmaProtocol.REQ_TAG,
                new UcxCallback() {});

        // Receive 8-byte length header
        ByteBuffer header = ByteBuffer.allocateDirect(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        UcpRequest headerReq = worker.recvTaggedNonBlocking(
                header,
                RdmaProtocol.RESP_TAG,
                RdmaProtocol.TAG_MASK,
                new UcxCallback() {});

        waitForCompletion("file request send/header", sendReq, headerReq);

        header.rewind();
        byte[] headerBytes = new byte[Long.BYTES];
        header.get(headerBytes);
        long length = RdmaProtocol.decodeLength(headerBytes);
        if (length < 0) {
            throw new IllegalStateException("Server reported error for file: " + remotePath);
        }
        System.out.println("Server reports file length=" + length + " bytes");

        try (FileOutputStream out = new FileOutputStream(localPath.toFile())) {
            long remaining = length;
            while (remaining > 0) {
                int recvLen = (int) Math.min(effectiveChunkSize, remaining);
                ByteBuffer chunk = ByteBuffer.allocateDirect(recvLen);
                UcpRequest recvReq = worker.recvTaggedNonBlocking(
                        chunk,
                        RdmaProtocol.RESP_TAG,
                        RdmaProtocol.TAG_MASK,
                        new UcxCallback() {});
                waitForCompletion("file chunk recv", recvReq);
                chunk.rewind();
                byte[] data = new byte[recvLen];
                chunk.get(data);
                out.write(data);
                remaining -= recvLen;
                if (remaining % (chunkSize * 8L) == 0) {
                    System.out.println("Remaining bytes: " + remaining);
                }
            }
        }

        System.out.println("File written to " + localPath);
        return length;
    }

    private static byte[] extractAddressBytes(ByteBuffer addressBuffer) {
        ByteBuffer addressCopy = addressBuffer.duplicate();
        addressCopy.rewind();
        byte[] bytes = new byte[addressCopy.remaining()];
        addressCopy.get(bytes);
        return bytes;
    }

    private static ByteBuffer createDirectAddressBuffer(byte[] addressBytes) {
        ByteBuffer directAddress = ByteBuffer.allocateDirect(addressBytes.length);
        directAddress.put(addressBytes);
        directAddress.flip();
        return directAddress;
    }

    private void flushEndpoint(String label) {
        System.out.println("Flushing UCX endpoint on " + label + "...");
        UcpRequest flushReq = endpoint.flushNonBlocking(new UcxCallback() {});
        waitForCompletion("endpoint flush", flushReq);
        System.out.println("Endpoint flush completed");
    }

    private void waitForCompletion(String label, UcpRequest... requests) {
        long start = System.currentTimeMillis();
        long lastLog = start;
        long spins = 0;
        while (true) {
            boolean allDone = true;
            for (UcpRequest req : requests) {
                if (req != null && !req.isCompleted()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                return;
            }
            try {
                worker.progress();
            } catch (Exception e) {
                throw new IllegalStateException("UCX worker progress failed while waiting for " + label, e);
            }
            spins++;
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000) {
                System.out.println("Waiting for " + label + "... elapsed=" + (now - start) + "ms spins=" + spins);
                lastLog = now;
            }
            if (now - start > RdmaProtocol.OP_TIMEOUT_MS) {
                throw new IllegalStateException("Timed out waiting for " + label);
            }
        }
    }

    private void awaitServerSignal(String label, int expected) {
        try {
            int value = bootstrapIn.read();
            if (value < 0) {
                throw new IllegalStateException("Bootstrap socket closed before signal for " + label);
            }
            if (value != expected) {
                throw new IllegalStateException("Unexpected bootstrap signal " + value + " for " + label);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed waiting for server signal for " + label, e);
        }
    }

    private void warmupConnection() {
        if (warmupCompleted) {
            return;
        }
        System.out.println("Waiting for server warmup signal...");
        awaitServerSignal("warmup", 1);
        System.out.println("Received server warmup signal");
        ByteBuffer warmupSend = ByteBuffer.allocateDirect(1);
        warmupSend.put((byte) 0);
        warmupSend.flip();
        UcpRequest warmupReq = endpoint.sendTaggedNonBlocking(
                warmupSend,
                RdmaProtocol.WARMUP_TAG,
                new UcxCallback() {});
        waitForCompletion("warmup send", warmupReq);
        System.out.println("Warmup send completed");
        flushEndpoint("client warmup");
        warmupCompleted = true;
        System.out.println("Warmup completed");
    }

    @Override
    public void close() {
        endpoint.close();
        worker.close();
        context.close();
        try {
            bootstrapIn.close();
            bootstrapOut.close();
            bootstrapSocket.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
