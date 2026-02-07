package org.openucx.rdma;

import org.openucx.jucx.UcxCallback;
import org.openucx.jucx.ucp.*;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

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

    private volatile UcpEndpoint endpoint;
    private volatile Socket clientSocket;

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
    }

    /**
     * Blocks until a client connects and completes worker address exchange.
     */
    public void awaitConnection(long timeout) throws Exception {
        // Accept bootstrap connection
        this.clientSocket = bootstrapServer.accept();

        DataInputStream in = new DataInputStream(clientSocket.getInputStream());
        DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());

        // Receive client's worker address
        int clientAddrLen = in.readInt();
        byte[] clientAddrBytes = new byte[clientAddrLen];
        in.readFully(clientAddrBytes);

        // Send our worker address to client
        ByteBuffer localAddress = worker.getAddress();
        byte[] localAddrBytes = new byte[localAddress.remaining()];
        localAddress.get(localAddrBytes);

        out.writeInt(localAddrBytes.length);
        out.write(localAddrBytes);
        out.flush();

        // Create endpoint from client's worker address (NOT connection request!)
        // This allows UCX to select optimal transport (EFA SRD if available)
        UcpEndpointParams epParams = new UcpEndpointParams()
                .setUcpAddress(ByteBuffer.wrap(clientAddrBytes))
                .setPeerErrorHandlingMode();

        this.endpoint = worker.newEndpoint(epParams);
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
        UcpRequest recvReq = worker.recvTaggedNonBlocking(
                recvBuffer,
                0L,
                ~0L,
                new UcxCallback() {});

        // Progress the worker until the receive is completed.
        while (!recvReq.isCompleted()) {
            worker.progress();
        }

        long bytesReceived = recvReq.getRecvSize();
        recvBuffer.limit((int) bytesReceived);
        recvBuffer.rewind();

        byte[] data = new byte[(int) bytesReceived];
        recvBuffer.get(data);

        // Echo the same bytes back to the client on tag 0.
        ByteBuffer sendBuffer = ByteBuffer.allocateDirect((int) bytesReceived);
        sendBuffer.put(data);
        sendBuffer.rewind();

        UcpRequest sendReq = endpoint.sendTaggedNonBlocking(
                sendBuffer,
                0L,
                new UcxCallback() {});

        while (!sendReq.isCompleted()) {
            worker.progress();
        }

        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
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
        } catch (Exception e) {
            // ignore
        }
    }
}

