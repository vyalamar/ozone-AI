package org.openucx.rdma;

import org.openucx.jucx.UcxCallback;
import org.openucx.jucx.ucp.*;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.SocketTimeoutException;

/**
 * Minimal UCX/JUCX-based server that accepts a single client connection and
 * performs a simple request/response (echo) exchange using tagged send/recv.
 *
 * This class uses worker-address exchange to enable RDMA/EFA transport
 * selection by UCX, rather than socket-address endpoints which force TCP.
 */
public class UcxRdmaServer implements Closeable {

    private final UcpContext context;
    private final UcpWorker worker;
    private final ServerSocket bootstrapServer;
    private final Path fileRoot;

    private volatile UcpEndpoint endpoint;
    private volatile Socket clientSocket;
    private volatile DataInputStream bootstrapIn;
    private volatile DataOutputStream bootstrapOut;
    private volatile boolean warmupCompleted;

    public UcxRdmaServer(String host, int port) throws Exception {
        UcxNative.load();
        UcpParams params = new UcpParams()
                .requestTagFeature()
                .setMtWorkersShared(true);

        this.context = new UcpContext(params);
        this.worker = context.newWorker(new UcpWorkerParams());

        // Use plain TCP ServerSocket for bootstrap (address exchange only)
        this.bootstrapServer = new ServerSocket();
        bootstrapServer.setReuseAddress(true);
        bootstrapServer.bind(new InetSocketAddress(host, port));
        this.fileRoot = resolveFileRoot();
    }

    /**
     * Blocks until a client connects and completes worker address exchange.
     */
    public void awaitConnection(long timeout) throws Exception {
        if (timeout > 0) {
            bootstrapServer.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeout));
        } else {
            bootstrapServer.setSoTimeout(0);
        }
        // Accept bootstrap connection
        System.out.println("Waiting for bootstrap TCP connection...");
        try {
            this.clientSocket = bootstrapServer.accept();
        } catch (SocketTimeoutException e) {
            throw new IllegalStateException("Timed out waiting for bootstrap TCP connection", e);
        }
        System.out.println("Bootstrap TCP connection accepted");

        clientSocket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, RdmaProtocol.OP_TIMEOUT_MS));
        this.bootstrapIn = new DataInputStream(clientSocket.getInputStream());
        this.bootstrapOut = new DataOutputStream(clientSocket.getOutputStream());

        // Receive client's worker address
        System.out.println("Waiting for client UCX worker address...");
        int clientAddrLen = bootstrapIn.readInt();
        if (clientAddrLen <= 0) {
            throw new IllegalStateException("Received empty client UCX worker address");
        }
        byte[] clientAddrBytes = new byte[clientAddrLen];
        bootstrapIn.readFully(clientAddrBytes);
        System.out.println("Received client UCX worker address length=" + clientAddrLen);

        // Send our worker address to client
        System.out.println("Obtaining local UCX worker address...");
        byte[] localAddrBytes = extractAddressBytes(worker.getAddress());
        System.out.println("Local UCX worker address length=" + localAddrBytes.length);

        bootstrapOut.writeInt(localAddrBytes.length);
        bootstrapOut.write(localAddrBytes);
        bootstrapOut.flush();
        System.out.println("Sent local UCX worker address to client");

        // Create endpoint from client's worker address (NOT connection request!)
        // This allows UCX to select optimal transport (EFA SRD if available)
        UcpEndpointParams epParams = new UcpEndpointParams()
                .setUcpAddress(createDirectAddressBuffer(clientAddrBytes))
                .setPeerErrorHandlingMode();

        this.endpoint = worker.newEndpoint(epParams);
        System.out.println("UCX endpoint created on server");
        flushEndpoint("server");

        warmupConnection();

        // Keep bootstrap socket open for a lightweight barrier signal.
    }

    /**
     * Receives a single message from the client with the given maximum length,
     * and immediately sends the same bytes back as a reply.
     *
     * @return the received payload as a String using UTF-8 encoding.
     */
    public String receiveAndEcho(int maxMessageLength) throws Exception {
        if (endpoint == null) {
            throw new IllegalStateException("No client connected yet.");
        }

        ByteBuffer recvBuffer = ByteBuffer.allocateDirect(maxMessageLength);

        // Post a tagged receive on the worker (tag 0, any message length up to maxMessageLength).
        System.out.println("Posting tagged recv up to " + maxMessageLength + " bytes tag=" + RdmaProtocol.REQ_TAG);
        UcpRequest recvReq = worker.recvTaggedNonBlocking(
                recvBuffer,
                RdmaProtocol.REQ_TAG,
                RdmaProtocol.TAG_MASK,
                new UcxCallback() {});
        signalClientReady("recv posted");

        // Progress the worker until the receive is completed.
        long start = System.currentTimeMillis();
        long lastLog = start;
        long spins = 0;
        while (!recvReq.isCompleted()) {
            worker.progress();
            spins++;
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000) {
                System.out.println("Waiting for recv... elapsed=" + (now - start) + "ms spins=" + spins);
                lastLog = now;
            }
            if (now - start > RdmaProtocol.OP_TIMEOUT_MS) {
                throw new IllegalStateException("Timed out waiting for recv completion");
            }
        }
        System.out.println("Recv completed");

        long bytesReceived = recvReq.getRecvSize();
        recvBuffer.limit((int) bytesReceived);
        recvBuffer.rewind();

        byte[] data = new byte[(int) bytesReceived];
        recvBuffer.get(data);

        String request = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (RdmaProtocol.isGetRequest(request)) {
            String path = RdmaProtocol.getPathFromRequest(request);
            sendFile(path);
            return "SENT " + path;
        }

        // Echo the same bytes back to the client.
        ByteBuffer sendBuffer = ByteBuffer.allocateDirect((int) bytesReceived);
        sendBuffer.put(data);
        sendBuffer.rewind();

        System.out.println("Posting tagged send of " + bytesReceived + " bytes tag=" + RdmaProtocol.RESP_TAG);
        UcpRequest sendReq = endpoint.sendTaggedNonBlocking(
                sendBuffer,
                RdmaProtocol.RESP_TAG,
                new UcxCallback() {});

        start = System.currentTimeMillis();
        lastLog = start;
        spins = 0;
        while (!sendReq.isCompleted()) {
            worker.progress();
            spins++;
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000) {
                System.out.println("Waiting for send... elapsed=" + (now - start) + "ms spins=" + spins);
                lastLog = now;
            }
            if (now - start > RdmaProtocol.OP_TIMEOUT_MS) {
                throw new IllegalStateException("Timed out waiting for send completion");
            }
        }
        System.out.println("Send completed");

        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
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

    private void sendFile(String filePath) throws Exception {
        Path safePath = FileTransferUtil.resolveSafePath(fileRoot, filePath);
        if (safePath == null) {
            System.out.println("Rejected file path (outside root): " + filePath);
            ByteBuffer header = ByteBuffer.allocateDirect(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
            header.put(RdmaProtocol.encodeLength(-1L));
            header.flip();
            UcpRequest errReq = endpoint.sendTaggedNonBlocking(header, RdmaProtocol.RESP_TAG, new UcxCallback() {});
            waitForCompletion("error header send", errReq);
            return;
        }
        if (!Files.exists(safePath)) {
            System.out.println("File not found: " + safePath);
            ByteBuffer header = ByteBuffer.allocateDirect(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
            header.put(RdmaProtocol.encodeLength(-1L));
            header.flip();
            UcpRequest errReq = endpoint.sendTaggedNonBlocking(header, RdmaProtocol.RESP_TAG, new UcxCallback() {});
            waitForCompletion("error header send", errReq);
            return;
        }

        long length = Files.size(safePath);
        System.out.println("Sending file " + safePath + " length=" + length + " root=" + fileRoot);

        ByteBuffer header = ByteBuffer.allocateDirect(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        header.put(RdmaProtocol.encodeLength(length));
        header.flip();
        UcpRequest headerReq = endpoint.sendTaggedNonBlocking(header, RdmaProtocol.RESP_TAG, new UcxCallback() {});
        waitForCompletion("file header send", headerReq);

        int chunkSize = RdmaProtocol.DEFAULT_CHUNK_SIZE;
        try (FileInputStream in = new FileInputStream(safePath.toFile())) {
            byte[] buffer = new byte[chunkSize];
            long remaining = length;
            while (remaining > 0) {
                int read = in.read(buffer, 0, FileTransferUtil.nextChunkSize(remaining, chunkSize));
                if (read < 0) {
                    throw new IllegalStateException("Unexpected EOF while reading " + safePath);
                }
                ByteBuffer chunk = ByteBuffer.allocateDirect(read);
                chunk.put(buffer, 0, read);
                chunk.flip();
                UcpRequest sendReq = endpoint.sendTaggedNonBlocking(chunk, RdmaProtocol.RESP_TAG, new UcxCallback() {});
                waitForCompletion("file chunk send", sendReq);
                remaining -= read;
                if (remaining % (chunkSize * 8L) == 0) {
                    System.out.println("Remaining bytes: " + remaining);
                }
            }
        }

        System.out.println("File send completed");
    }

    private static Path resolveFileRoot() {
        String root = System.getenv("UCX_FILE_ROOT");
        if (root == null || root.trim().isEmpty()) {
            root = "/tmp";
        }
        return Paths.get(root).toAbsolutePath().normalize();
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

    private void signalClientReady(String label) {
        try {
            if (bootstrapOut != null) {
                bootstrapOut.write(2);
                bootstrapOut.flush();
            } else {
                throw new IllegalStateException("Bootstrap output not initialized");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to signal client ready: " + label, e);
        }
    }

    private void warmupConnection() {
        if (warmupCompleted) {
            return;
        }
        System.out.println("Posting warmup recv tag=" + RdmaProtocol.WARMUP_TAG);
        ByteBuffer warmupRecv = ByteBuffer.allocateDirect(1);
        UcpRequest warmupReq = worker.recvTaggedNonBlocking(
                warmupRecv,
                RdmaProtocol.WARMUP_TAG,
                RdmaProtocol.TAG_MASK,
                new UcxCallback() {});
        sendWarmupSignal();
        waitForCompletion("warmup recv", warmupReq);
        warmupCompleted = true;
        System.out.println("Warmup recv completed");
    }

    private void sendWarmupSignal() {
        try {
            if (bootstrapOut != null) {
                bootstrapOut.write(1);
                bootstrapOut.flush();
                System.out.println("Signaled client to send warmup");
            } else {
                throw new IllegalStateException("Bootstrap output not initialized");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to signal client warmup", e);
        }
    }

    @Override
    public void close() {
        if (endpoint != null) {
            endpoint.close();
        }
        worker.close();
        context.close();
        try {
            if (clientSocket != null) clientSocket.close();
            bootstrapServer.close();
            if (bootstrapIn != null) bootstrapIn.close();
            if (bootstrapOut != null) bootstrapOut.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
