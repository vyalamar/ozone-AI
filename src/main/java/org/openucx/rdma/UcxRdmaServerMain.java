package org.openucx.rdma;

public class UcxRdmaServerMain {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "0.0.0.0";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 23456;
        int maxMessageLength = args.length > 2 ? Integer.parseInt(args[2]) : 1024;

        logUcxEnv();
        System.out.println("Starting UCX server on " + host + ":" + port);

        try (UcxRdmaServer server = new UcxRdmaServer(host, port)) {
            server.awaitConnection(0);
            String message = server.receiveAndEcho(maxMessageLength);
            System.out.println("Received: " + message);
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
