package org.openucx.rdma;

public class UcxRdmaClientMain {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 23456;
        String message = args.length > 2 ? args[2] : "Hello UCX RDMA";
        int maxReplyLength = args.length > 3 ? Integer.parseInt(args[3]) : 1024;

        logUcxEnv();
        System.out.println("Connecting to " + host + ":" + port);

        try (UcxRdmaClient client = new UcxRdmaClient(host, port)) {
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
