package bgu.spl.net.srv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ConnectionsImpl
 * Tests connection management and message sending
 */
public class ConnectionsImplTest {

    private ConnectionsImpl<String> connections;

    /**
     * Mock ConnectionHandler for testing
     */
    private static class MockConnectionHandler implements ConnectionHandler<String> {
        public List<String> sentMessages = new ArrayList<>();
        public boolean closed = false;

        @Override
        public void send(String msg) {
            sentMessages.add(msg);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @BeforeEach
    public void setUp() {
        connections = new ConnectionsImpl<>();
    }

    @Test
    public void testAddConnection() {
        MockConnectionHandler handler = new MockConnectionHandler();

        connections.addConnection(1, handler);

        // Send message to verify connection was added
        boolean sent = connections.send(1, "test");
        assertTrue(sent, "Should successfully send to added connection");
        assertEquals(1, handler.sentMessages.size(), "Handler should receive message");
    }

    @Test
    public void testSendToExistingConnection() {
        MockConnectionHandler handler = new MockConnectionHandler();
        connections.addConnection(100, handler);

        boolean result = connections.send(100, "Hello");

        assertTrue(result, "Send should return true for existing connection");
        assertEquals(1, handler.sentMessages.size(), "Should send one message");
        assertEquals("Hello", handler.sentMessages.get(0), "Should send correct message");
    }

    @Test
    public void testSendToNonExistentConnection() {
        boolean result = connections.send(999, "Hello");

        assertFalse(result, "Send should return false for non-existent connection");
    }

    @Test
    public void testSendMultipleMessages() {
        MockConnectionHandler handler = new MockConnectionHandler();
        connections.addConnection(200, handler);

        connections.send(200, "Message1");
        connections.send(200, "Message2");
        connections.send(200, "Message3");

        assertEquals(3, handler.sentMessages.size(), "Should send all messages");
        assertEquals("Message1", handler.sentMessages.get(0));
        assertEquals("Message2", handler.sentMessages.get(1));
        assertEquals("Message3", handler.sentMessages.get(2));
    }

    @Test
    public void testDisconnect() {
        MockConnectionHandler handler = new MockConnectionHandler();
        connections.addConnection(300, handler);

        // Verify connection exists
        assertTrue(connections.send(300, "test"), "Connection should exist");

        // Disconnect
        connections.disconnect(300);

        // Verify connection removed
        assertFalse(connections.send(300, "test2"),
                "Connection should not exist after disconnect");
    }

    @Test
    public void testDisconnectNonExistent() {
        // Should not throw exception
        assertDoesNotThrow(() -> connections.disconnect(999),
                "Disconnecting non-existent connection should not throw");
    }

    @Test
    public void testMultipleConnections() {
        MockConnectionHandler handler1 = new MockConnectionHandler();
        MockConnectionHandler handler2 = new MockConnectionHandler();
        MockConnectionHandler handler3 = new MockConnectionHandler();

        connections.addConnection(1, handler1);
        connections.addConnection(2, handler2);
        connections.addConnection(3, handler3);

        connections.send(1, "To 1");
        connections.send(2, "To 2");
        connections.send(3, "To 3");

        assertEquals(1, handler1.sentMessages.size());
        assertEquals("To 1", handler1.sentMessages.get(0));

        assertEquals(1, handler2.sentMessages.size());
        assertEquals("To 2", handler2.sentMessages.get(0));

        assertEquals(1, handler3.sentMessages.size());
        assertEquals("To 3", handler3.sentMessages.get(0));
    }

    @Test
    public void testAddNullHandler() {
        // Should handle gracefully
        connections.addConnection(400, null);

        // Try to send
        boolean result = connections.send(400, "test");
        assertFalse(result, "Should not send to null handler");
    }

    @Test
    public void testReplaceConnection() {
        MockConnectionHandler handler1 = new MockConnectionHandler();
        MockConnectionHandler handler2 = new MockConnectionHandler();

        // Add first handler
        connections.addConnection(500, handler1);
        connections.send(500, "Message1");

        // Replace with second handler
        connections.addConnection(500, handler2);
        connections.send(500, "Message2");

        assertEquals(1, handler1.sentMessages.size(), "First handler should have 1 message");
        assertEquals(1, handler2.sentMessages.size(), "Second handler should have 1 message");
        assertEquals("Message2", handler2.sentMessages.get(0),
                "Second handler should receive Message2");
    }

    @Test
    public void testConcurrentAddConnections() throws InterruptedException {
        int numThreads = 20;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int id = 600 + i;
            new Thread(() -> {
                try {
                    MockConnectionHandler handler = new MockConnectionHandler();
                    connections.addConnection(id, handler);

                    if (connections.send(id, "test")) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(numThreads, successCount.get(),
                "All concurrent connections should be added successfully");
    }

    @Test
    public void testConcurrentSendToSameConnection() throws InterruptedException {
        MockConnectionHandler handler = new MockConnectionHandler();
        connections.addConnection(700, handler);

        int numThreads = 10;
        int messagesPerThread = 10;
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        connections.send(700, "Thread" + threadId + "Msg" + j);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(numThreads * messagesPerThread, handler.sentMessages.size(),
                "Should receive all concurrent messages");
    }

    @Test
    public void testConcurrentDisconnect() throws InterruptedException {
        int numConnections = 20;

        // Add connections
        for (int i = 0; i < numConnections; i++) {
            connections.addConnection(800 + i, new MockConnectionHandler());
        }

        CountDownLatch latch = new CountDownLatch(numConnections);

        // Disconnect concurrently
        for (int i = 0; i < numConnections; i++) {
            final int id = 800 + i;
            new Thread(() -> {
                try {
                    connections.disconnect(id);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        // Verify all disconnected
        for (int i = 0; i < numConnections; i++) {
            assertFalse(connections.send(800 + i, "test"),
                    "Connection " + (800 + i) + " should be disconnected");
        }
    }

    @Test
    public void testSendChannelNotImplemented() {
        // The send(String channel, T msg) method is not implemented
        // Just verify it doesn't throw
        assertDoesNotThrow(() -> connections.send("channel", "message"),
                "Channel send should not throw");
    }
}
