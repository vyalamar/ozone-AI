package org.openucx.rdma;

import org.openucx.jucx.UcxCallback;
import org.openucx.jucx.ucp.*;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

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

        DataInputStream in = new DataInputStream(bootstrapSocket.getInputStream());
        DataOutputStream out = new DataOutputStream(bootstrapSocket.getOutputStream());

        // 2. Get our worker address and send it to server
        ByteBuffer localAddress = worker.getAddress();
        byte[] localAddrBytes = new byte[localAddress.remaining()];
        localAddress.get(localAddrBytes);

        out.writeInt(localAddrBytes.length);
        out.write(localAddrBytes);
        out.flush();

        // 3. Receive server's worker address
        int serverAddrLen = in.readInt();
        byte[] serverAddrBytes = new byte[serverAddrLen];
        in.readFully(serverAddrBytes);

        // 4. Create endpoint from server's worker address (NOT socket address!)
        // This allows UCX to select optimal transport (EFA SRD if available)
        UcpEndpointParams epParams = new UcpEndpointParams()
                .setUcpAddress(ByteBuffer.wrap(serverAddrBytes))
                .setPeerErrorHandlingMode();

        this.endpoint = worker.newEndpoint(epParams);
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

        // Post tagged send on tag 0.
        UcpRequest sendReq = endpoint.sendTaggedNonBlocking(
                sendBuffer,
                0L,
                new UcxCallback() {});

        // Prepare receive buffer for reply.
        ByteBuffer recvBuffer = ByteBuffer.allocateDirect(maxReplyLength);
        UcpRequest recvReq = worker.recvTaggedNonBlocking(
                recvBuffer,
                0L,
                ~0L,
                new UcxCallback() {});

        // Progress until both operations are completed.
        while (!sendReq.isCompleted() || !recvReq.isCompleted()) {
            worker.progress();
        }

        long bytesReceived = recvReq.getRecvSize();
        recvBuffer.limit((int) bytesReceived);
        recvBuffer.rewind();

        byte[] replyBytes = new byte[(int) bytesReceived];
        recvBuffer.get(replyBytes);

        return new String(replyBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        endpoint.close();
        worker.close();
        context.close();
        try {
            bootstrapSocket.close();
        } catch (Exception e) {
            // ignore
        }
    }
}

