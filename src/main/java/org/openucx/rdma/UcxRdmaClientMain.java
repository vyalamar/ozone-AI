package org.openucx.rdma;

import java.nio.file.Path;
import java.nio.file.Paths;

public class UcxRdmaClientMain {
    public static void main(String[] args) throws Exception {
        boolean getMode = args.length > 0 && "--get".equals(args[0]);
        String host = args.length > (getMode ? 1 : 0) ? args[getMode ? 1 : 0] : "127.0.0.1";
        int port = args.length > (getMode ? 2 : 1) ? Integer.parseInt(args[getMode ? 2 : 1]) : 23456;

        logUcxEnv();
        System.out.println("Connecting to " + host + ":" + port);

        try (UcxRdmaClient client = new UcxRdmaClient(host, port)) {
            if (getMode) {
                String remotePath = args.length > 3 ? args[3] : "/tmp/ucx_payload.bin";
                Path localPath = Paths.get(args.length > 4 ? args[4] : "/tmp/ucx_fetch.bin");
                int chunkSize = args.length > 5 ? Integer.parseInt(args[5]) : RdmaProtocol.DEFAULT_CHUNK_SIZE;
                long bytes = client.requestFile(remotePath, localPath, chunkSize);
                System.out.println("Fetched " + bytes + " bytes to " + localPath);
                return;
            }

            String message = args.length > 2 ? args[2] : "Hello UCX RDMA";
            int maxReplyLength = args.length > 3 ? Integer.parseInt(args[3]) : 1024;
            String reply = client.sendAndReceive(message, maxReplyLength);
            System.out.println("Reply: " + reply);
        }
    }

    private static void logUcxEnv() {
        printEnv("UCX_TLS");
        printEnv("UCX_NET_DEVICES");
        printEnv("UCX_LOG_LEVEL");
    }

    private static void printEnv(String key) {
        String value = System.getenv(key);
        System.out.println(key + "=" + (value == null ? "<unset>" : value));
    }
}
