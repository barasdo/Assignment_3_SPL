package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.stomp.MessageEncoderDecoderImpl;
import bgu.spl.net.impl.stomp.StompMessagingProtocolImpl;
import bgu.spl.net.impl.data.Database;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the server
 * Tests complete client-server flows with both Reactor and TPC architectures
 * 
 * NOTE: These tests require manually starting the server before running
 * To run these tests:
 * 1. Start the server on port 7777 (either reactor or tpc mode)
 * 2. Run the tests
 * 
 * These tests are marked as disabled by default to avoid failures when server
 * is not running
 */
public class ServerIntegrationTest {

    private static final String HOST = "localhost";
    private static final int PORT = 7777;
    private static final int TIMEOUT = 5000; // 5 seconds

    /**
     * Helper class to simulate a STOMP client
     */
    private static class StompClient {
        private Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;
        private Thread readerThread;
        private StringBuilder receivedMessages = new StringBuilder();

        public void connect() throws IOException {
            socket = new Socket(HOST, PORT);
            socket.setSoTimeout(TIMEOUT);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Start reader thread
            readerThread = new Thread(() -> {
                try {
                    int ch;
                    while ((ch = reader.read()) != -1) {
                        synchronized (receivedMessages) {
                            receivedMessages.append((char) ch);
                        }
                    }
                } catch (IOException e) {
                    // Connection closed or error
                }
            });
            readerThread.start();
        }

        public void send(String message) {
            writer.print(message + '\0');
            writer.flush();
        }

        public String getReceivedMessages() {
            synchronized (receivedMessages) {
                return receivedMessages.toString();
            }
        }

        public boolean waitForMessage(String expectedContent, int timeoutMs) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                synchronized (receivedMessages) {
                    if (receivedMessages.toString().contains(expectedContent)) {
                        return true;
                    }
                }
                Thread.sleep(100);
            }
            return false;
        }

        public void close() throws IOException {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (readerThread != null) {
                try {
                    readerThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Disabled by default - enable manually when testing with running server
    // @Test
    public void testFullFlow_ConnectSubscribeSendDisconnect() throws Exception {
        StompClient client = new StompClient();

        try {
            // Connect to server
            client.connect();

            // Send CONNECT frame
            String username = "integrationuser" + System.currentTimeMillis();
            String connectFrame = "CONNECT\n" +
                    "accept-version:1.2\n" +
                    "host:localhost\n" +
                    "login:" + username + "\n" +
                    "passcode:testpass\n" +
                    "receipt:connect-1\n\n";

            client.send(connectFrame);

            // Wait for CONNECTED
            assertTrue(client.waitForMessage("CONNECTED", 3000),
                    "Should receive CONNECTED frame");

            // Subscribe
            String subscribeFrame = "SUBSCRIBE\n" +
                    "destination:/topic/test\n" +
                    "id:sub-1\n" +
                    "receipt:subscribe-1\n\n";

            client.send(subscribeFrame);
            assertTrue(client.waitForMessage("receipt-id:subscribe-1", 3000),
                    "Should receive RECEIPT for subscribe");

            // Send message
            String sendFrame = "SEND\n" +
                    "destination:/topic/test\n" +
                    "receipt:send-1\n\n" +
                    "Hello Integration Test";

            client.send(sendFrame);

            // Should receive both MESSAGE and RECEIPT
            assertTrue(client.waitForMessage("MESSAGE", 3000),
                    "Should receive MESSAGE frame");
            assertTrue(client.waitForMessage("receipt-id:send-1", 3000),
                    "Should receive RECEIPT for send");

            // Disconnect
            String disconnectFrame = "DISCONNECT\n" +
                    "receipt:disconnect-1\n\n";

            client.send(disconnectFrame);
            assertTrue(client.waitForMessage("receipt-id:disconnect-1", 3000),
                    "Should receive RECEIPT for disconnect");

        } finally {
            client.close();
        }
    }

    // @Test
    public void testMultipleClientsConcurrent() throws Exception {
        int numClients = 5;
        CountDownLatch latch = new CountDownLatch(numClients);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            new Thread(() -> {
                StompClient client = new StompClient();
                try {
                    client.connect();

                    String username = "multiclient" + System.currentTimeMillis() + "_" + clientId;
                    String connectFrame = "CONNECT\n" +
                            "accept-version:1.2\n" +
                            "host:localhost\n" +
                            "login:" + username + "\n" +
                            "passcode:pass\n\n";

                    client.send(connectFrame);

                    if (client.waitForMessage("CONNECTED", 3000)) {
                        successCount.incrementAndGet();
                    }

                    // Disconnect
                    String disconnectFrame = "DISCONNECT\n" +
                            "receipt:disc-" + clientId + "\n\n";
                    client.send(disconnectFrame);

                    client.close();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All clients should complete");
        assertEquals(numClients, successCount.get(),
                "All clients should connect successfully");
    }

    // @Test
    public void testErrorHandling_InvalidVersion() throws Exception {
        StompClient client = new StompClient();

        try {
            client.connect();

            String connectFrame = "CONNECT\n" +
                    "accept-version:1.0\n" + // Unsupported version
                    "host:localhost\n" +
                    "login:testuser\n" +
                    "passcode:pass\n\n";

            client.send(connectFrame);

            assertTrue(client.waitForMessage("ERROR", 3000),
                    "Should receive ERROR for unsupported version");

            // Server should close connection after error
            Thread.sleep(1000);
            assertTrue(client.socket.isClosed() || !client.socket.isConnected(),
                    "Connection should be closed after error");

        } finally {
            client.close();
        }
    }

    // @Test
    public void testSuddenDisconnect() throws Exception {
        StompClient client = new StompClient();

        try {
            client.connect();

            String username = "disconnect" + System.currentTimeMillis();
            String connectFrame = "CONNECT\n" +
                    "accept-version:1.2\n" +
                    "host:localhost\n" +
                    "login:" + username + "\n" +
                    "passcode:pass\n\n";

            client.send(connectFrame);
            assertTrue(client.waitForMessage("CONNECTED", 3000));

            // Suddenly close without DISCONNECT frame
            client.close();

            // Should not throw exception or crash server
            Thread.sleep(1000);

            // Try to connect again with same username - should work
            StompClient client2 = new StompClient();
            client2.connect();
            client2.send(connectFrame);
            assertTrue(client2.waitForMessage("CONNECTED", 3000),
                    "Should be able to reconnect after sudden disconnect");
            client2.close();

        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    /**
     * Unit test that doesn't require server to be running
     */
    @Test
    public void testProtocolIsolation() {
        // Test that protocol instances are independent
        StompMessagingProtocolImpl protocol1 = new StompMessagingProtocolImpl();
        StompMessagingProtocolImpl protocol2 = new StompMessagingProtocolImpl();

        MockConnections conn1 = new MockConnections();
        MockConnections conn2 = new MockConnections();

        protocol1.start(1, conn1);
        protocol2.start(2, conn2);

        // Process CONNECT for protocol1
        String connect1 = "CONNECT\n" +
                "accept-version:1.2\n" +
                "host:localhost\n" +
                "login:user1\n" +
                "passcode:pass1\n\n";

        protocol1.process(connect1);

        // protocol2 should not be affected
        assertFalse(protocol2.shouldTerminate(),
                "Protocol2 should not terminate when protocol1 processes");

        assertTrue(conn1.sentMessages.size() > 0, "Conn1 should have messages");
        assertEquals(0, conn2.sentMessages.size(), "Conn2 should have no messages");
    }

    /**
     * Mock Connections for unit testing
     */
    private static class MockConnections implements Connections<String> {
        public java.util.List<String> sentMessages = new java.util.ArrayList<>();

        @Override
        public boolean send(int connectionId, String msg) {
            sentMessages.add(msg);
            return true;
        }

        @Override
        public void send(String channel, String msg) {
        }

        @Override
        public void disconnect(int connectionId) {
        }
    }

    @Test
    public void testMessageEncoderDecoderIntegration() {
        MessageEncoderDecoderImpl encdec = new MessageEncoderDecoderImpl();

        String originalMessage = "CONNECT\naccept-version:1.2\nhost:localhost\n\n";

        // Encode
        byte[] encoded = encdec.encode(originalMessage);

        // Decode
        MessageEncoderDecoderImpl decoder = new MessageEncoderDecoderImpl();
        String decoded = null;
        for (byte b : encoded) {
            decoded = decoder.decodeNextByte(b);
        }

        assertEquals(originalMessage, decoded,
                "Round trip encoding/decoding should preserve message");
    }
}
