package bgu.spl.net.impl.stomp;

import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.srv.Connections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for StompMessagingProtocolImpl
 * Tests all STOMP handlers: CONNECT, SEND, SUBSCRIBE, UNSUBSCRIBE, DISCONNECT
 * Also tests error handling and edge cases
 */
public class StompMessagingProtocolTest {

    private StompMessagingProtocolImpl protocol;
    private MockConnections connections;
    private int connectionId;

    /**
     * Mock implementation of Connections for testing
     */
    private static class MockConnections implements Connections<String> {
        public List<String> sentMessages = new ArrayList<>();
        public List<Integer> disconnectedIds = new ArrayList<>();

        @Override
        public boolean send(int connectionId, String msg) {
            sentMessages.add(msg);
            return true;
        }

        @Override
        public void send(String channel, String msg) {
            // Not used in protocol
        }

        @Override
        public void disconnect(int connectionId) {
            disconnectedIds.add(connectionId);
        }

        public void clear() {
            sentMessages.clear();
            disconnectedIds.clear();
        }

        public String getLastMessage() {
            return sentMessages.isEmpty() ? null : sentMessages.get(sentMessages.size() - 1);
        }
    }

    @BeforeEach
    public void setUp() {
        protocol = new StompMessagingProtocolImpl();
        connections = new MockConnections();
        connectionId = (int) (Math.random() * 10000) + 10000; // Random ID for isolation
        protocol.start(connectionId, connections);
    }

    // ==================== CONNECT Tests ====================

    @Test
    public void testConnectSuccess() {
        String connectMsg = "CONNECT\n" +
                "accept-version:1.2\n" +
                "host:localhost\n" +
                "login:testuser" + connectionId + "\n" +
                "passcode:testpass\n\n";

        protocol.process(connectMsg);

        assertFalse(connections.sentMessages.isEmpty(), "Should send CONNECTED frame");
        String response = connections.getLastMessage();
        assertTrue(response.contains("CONNECTED"), "Should send CONNECTED frame");
        assertTrue(response.contains("version:1.2"), "Should include version");
    }

    @Test
    public void testConnectUnsupportedVersion() {
        String connectMsg = "CONNECT\n" +
                "accept-version:1.0\n" +
                "host:localhost\n" +
                "login:testuser\n" +
                "passcode:testpass\n\n";

        protocol.process(connectMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for unsupported version");
        assertTrue(response.contains("version"), "Error should mention version");
        assertTrue(protocol.shouldTerminate(), "Should terminate after error");
    }

    @Test
    public void testConnectMissingHeaders() {
        String connectMsg = "CONNECT\n" +
                "accept-version:1.2\n" +
                "host:localhost\n\n"; // Missing login and passcode

        protocol.process(connectMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for missing headers");
        assertTrue(protocol.shouldTerminate(), "Should terminate after error");
    }

    @Test
    public void testConnectWithReceipt() {
        String connectMsg = "CONNECT\n" +
                "accept-version:1.2\n" +
                "host:localhost\n" +
                "login:receiptuser" + connectionId + "\n" +
                "passcode:pass\n" +
                "receipt:connect-123\n\n";

        protocol.process(connectMsg);

        // Should have CONNECTED and RECEIPT
        assertTrue(connections.sentMessages.size() >= 2, "Should send CONNECTED and RECEIPT");

        boolean hasConnected = false;
        boolean hasReceipt = false;
        for (String msg : connections.sentMessages) {
            if (msg.contains("CONNECTED"))
                hasConnected = true;
            if (msg.contains("RECEIPT") && msg.contains("connect-123"))
                hasReceipt = true;
        }

        assertTrue(hasConnected, "Should send CONNECTED");
        assertTrue(hasReceipt, "Should send RECEIPT");
    }

    @Test
    public void testConnectWrongPassword() {
        String username = "pwdtest" + connectionId;

        // Register user first
        Database.getInstance().login(connectionId + 1, username, "correctpass");
        Database.getInstance().logout(connectionId + 1);

        // Try with wrong password
        String connectMsg = "CONNECT\n" +
                "accept-version:1.2\n" +
                "host:localhost\n" +
                "login:" + username + "\n" +
                "passcode:wrongpass\n\n";

        protocol.process(connectMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for wrong password");
        assertTrue(response.toLowerCase().contains("password"), "Error should mention password");
    }

    // ==================== SEND Tests ====================

    @Test
    public void testSendWithoutLogin() {
        String sendMsg = "SEND\n" +
                "destination:/topic/test\n\n" +
                "Test message";

        protocol.process(sendMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR when not logged in");
        assertTrue(response.toLowerCase().contains("login") ||
                response.toLowerCase().contains("unauthorized"),
                "Error should mention login/unauthorized");
    }

    @Test
    public void testSendMissingDestination() {
        // Login first
        connectUser();

        String sendMsg = "SEND\n\n" + // No destination
                "Test message";

        protocol.process(sendMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for missing destination");
    }

    @Test
    public void testSendNotSubscribed() {
        // Login first
        connectUser();

        String sendMsg = "SEND\n" +
                "destination:/topic/test\n\n" +
                "Test message";

        protocol.process(sendMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR when not subscribed");
        assertTrue(response.toLowerCase().contains("subscribed"), "Error should mention subscription");
    }

    @Test
    public void testSendSuccessWithReceipt() {
        // Login and subscribe
        connectUser();
        subscribeToTopic("/topic/chat");

        connections.clear();

        String sendMsg = "SEND\n" +
                "destination:/topic/chat\n" +
                "receipt:send-123\n\n" +
                "Hello World";

        protocol.process(sendMsg);

        // Should send MESSAGE to self and RECEIPT
        boolean hasMessage = false;
        boolean hasReceipt = false;

        for (String msg : connections.sentMessages) {
            if (msg.contains("MESSAGE"))
                hasMessage = true;
            if (msg.contains("RECEIPT") && msg.contains("send-123"))
                hasReceipt = true;
        }

        assertTrue(hasMessage, "Should send MESSAGE frame");
        assertTrue(hasReceipt, "Should send RECEIPT");
    }

    // ==================== SUBSCRIBE Tests ====================

    @Test
    public void testSubscribeWithoutLogin() {
        String subscribeMsg = "SUBSCRIBE\n" +
                "destination:/topic/test\n" +
                "id:sub-1\n\n";

        protocol.process(subscribeMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR when not logged in");
    }

    @Test
    public void testSubscribeMissingHeaders() {
        connectUser();

        String subscribeMsg = "SUBSCRIBE\n" +
                "destination:/topic/test\n\n"; // Missing id

        protocol.process(subscribeMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for missing headers");
    }

    @Test
    public void testSubscribeSuccess() {
        connectUser();
        connections.clear();

        String subscribeMsg = "SUBSCRIBE\n" +
                "destination:/topic/sports\n" +
                "id:sub-sports\n\n";

        protocol.process(subscribeMsg);

        // Should not send error
        if (!connections.sentMessages.isEmpty()) {
            String lastMsg = connections.getLastMessage();
            assertFalse(lastMsg.contains("ERROR"), "Should not send ERROR on success");
        }
    }

    @Test
    public void testSubscribeDuplicateId() {
        connectUser();

        String subscribeMsg1 = "SUBSCRIBE\n" +
                "destination:/topic/a\n" +
                "id:sub-1\n\n";

        protocol.process(subscribeMsg1);

        String subscribeMsg2 = "SUBSCRIBE\n" +
                "destination:/topic/b\n" +
                "id:sub-1\n\n"; // Same ID

        protocol.process(subscribeMsg2);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for duplicate subscription ID");
    }

    @Test
    public void testSubscribeWithReceipt() {
        connectUser();
        connections.clear();

        String subscribeMsg = "SUBSCRIBE\n" +
                "destination:/topic/news\n" +
                "id:sub-news\n" +
                "receipt:sub-receipt\n\n";

        protocol.process(subscribeMsg);

        boolean hasReceipt = false;
        for (String msg : connections.sentMessages) {
            if (msg.contains("RECEIPT") && msg.contains("sub-receipt")) {
                hasReceipt = true;
            }
        }

        assertTrue(hasReceipt, "Should send RECEIPT for subscription");
    }

    // ==================== UNSUBSCRIBE Tests ====================

    @Test
    public void testUnsubscribeWithoutLogin() {
        String unsubscribeMsg = "UNSUBSCRIBE\n" +
                "id:sub-1\n\n";

        protocol.process(unsubscribeMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR when not logged in");
    }

    @Test
    public void testUnsubscribeMissingId() {
        connectUser();

        String unsubscribeMsg = "UNSUBSCRIBE\n\n"; // Missing id

        protocol.process(unsubscribeMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for missing id");
    }

    @Test
    public void testUnsubscribeNonExistent() {
        connectUser();

        String unsubscribeMsg = "UNSUBSCRIBE\n" +
                "id:non-existent\n\n";

        protocol.process(unsubscribeMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for non-existent subscription");
    }

    @Test
    public void testUnsubscribeSuccess() {
        connectUser();
        subscribeToTopic("/topic/test");
        connections.clear();

        String unsubscribeMsg = "UNSUBSCRIBE\n" +
                "id:test-sub\n\n";

        protocol.process(unsubscribeMsg);

        // Should not send error
        if (!connections.sentMessages.isEmpty()) {
            String lastMsg = connections.getLastMessage();
            assertFalse(lastMsg.contains("ERROR"), "Should not send ERROR on successful unsubscribe");
        }
    }

    // ==================== DISCONNECT Tests ====================

    @Test
    public void testDisconnectWithoutLogin() {
        String disconnectMsg = "DISCONNECT\n" +
                "receipt:disc-123\n\n";

        protocol.process(disconnectMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR when not logged in");
    }

    @Test
    public void testDisconnectWithoutReceipt() {
        connectUser();

        String disconnectMsg = "DISCONNECT\n\n"; // No receipt

        protocol.process(disconnectMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR without receipt");
    }

    @Test
    public void testDisconnectSuccess() {
        connectUser();
        connections.clear();

        String disconnectMsg = "DISCONNECT\n" +
                "receipt:disconnect-123\n\n";

        protocol.process(disconnectMsg);

        // Should send RECEIPT
        boolean hasReceipt = false;
        for (String msg : connections.sentMessages) {
            if (msg.contains("RECEIPT") && msg.contains("disconnect-123")) {
                hasReceipt = true;
            }
        }

        assertTrue(hasReceipt, "Should send RECEIPT");
        assertTrue(protocol.shouldTerminate(), "Should set terminate flag");
        assertTrue(connections.disconnectedIds.contains(connectionId),
                "Should call disconnect");
    }

    // ==================== ERROR Tests ====================

    @Test
    public void testUnknownCommand() {
        String unknownMsg = "UNKNOWN\n\n";

        protocol.process(unknownMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should send ERROR for unknown command");
        assertTrue(protocol.shouldTerminate(), "Should terminate after error");
    }

    @Test
    public void testErrorIncludesOriginalMessage() {
        String badMsg = "CONNECT\n\n"; // Missing required headers

        protocol.process(badMsg);

        String response = connections.getLastMessage();
        assertTrue(response.contains("ERROR"), "Should be ERROR frame");
        assertTrue(response.contains("CONNECT"), "Error should include original message");
    }

    @Test
    public void testErrorSetsTerminateFlag() {
        String badMsg = "INVALID\n\n";

        assertFalse(protocol.shouldTerminate(), "Should not be terminated initially");

        protocol.process(badMsg);

        assertTrue(protocol.shouldTerminate(), "Should set terminate flag after error");
    }

    // ==================== Helper Methods ====================

    private void connectUser() {
        String connectMsg = "CONNECT\n" +
                "accept-version:1.2\n" +
                "host:localhost\n" +
                "login:testuser" + connectionId + "\n" +
                "passcode:pass\n\n";
        protocol.process(connectMsg);
    }

    private void subscribeToTopic(String topic) {
        String subscribeMsg = "SUBSCRIBE\n" +
                "destination:" + topic + "\n" +
                "id:test-sub\n\n";
        protocol.process(subscribeMsg);
    }
}
