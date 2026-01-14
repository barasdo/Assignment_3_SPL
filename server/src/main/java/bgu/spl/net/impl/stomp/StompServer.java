package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;

public class StompServer {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: <port> <tpc|reactor>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String type = args[1];
        Server<String> server;

        if (type.equals("tpc")) {
            server = Server.threadPerClient(
                    port,
                    () -> new StompMessagingProtocolImpl(),
                    () -> new MessageEncoderDecoderImpl());
        } else if (type.equals("reactor")) {
            int nthreads = Runtime.getRuntime().availableProcessors();
            server = Server.reactor(
                    nthreads,
                    port,
                    () -> new StompMessagingProtocolImpl(),
                    () -> new MessageEncoderDecoderImpl());
        } else {
            System.err.println("Unknown server type");
            return;
        }

        server.serve();
    }
}
