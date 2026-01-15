package bgu.spl.net.impl.stomp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for SubscriptionManager
 * Tests subscription management, thread safety, and message ID generation
 */
public class SubscriptionManagerTest {

    private SubscriptionManager manager;

    @BeforeEach
    public void setUp() {
        manager = SubscriptionManager.getInstance();
        // Note: Since it's a singleton, we can't fully reset between tests
        // In a production environment, consider adding a reset() method for testing
    }

    @Test
    public void testSimpleSubscribe() {
        int connectionId = 1000;
        String topic = "/topic/test1";
        String subscriptionId = "sub-1";

        boolean result = manager.subscribe(connectionId, topic, subscriptionId);
        assertTrue(result, "First subscription should succeed");

        assertTrue(manager.isSubscribed(connectionId, topic), "Connection should be subscribed to topic");
    }

    @Test
    public void testDuplicateSubscriptionId() {
        int connectionId = 1001;
        String topic = "/topic/test2";
        String subscriptionId = "sub-2";

        boolean result1 = manager.subscribe(connectionId, topic, subscriptionId);
        assertTrue(result1, "First subscription should succeed");

        // Try to subscribe with same subscription ID (even to different topic)
        boolean result2 = manager.subscribe(connectionId, "/topic/other", subscriptionId);
        assertFalse(result2, "Duplicate subscription ID should fail");
    }

    @Test
    public void testMultipleClientsToSameTopic() {
        String topic = "/topic/shared";
        int conn1 = 2001;
        int conn2 = 2002;
        int conn3 = 2003;

        manager.subscribe(conn1, topic, "sub-2001");
        manager.subscribe(conn2, topic, "sub-2002");
        manager.subscribe(conn3, topic, "sub-2003");

        Map<Integer, String> subscribers = manager.getSubscribersSnapshot(topic);
        assertEquals(3, subscribers.size(), "Topic should have 3 subscribers");

        assertTrue(subscribers.containsKey(conn1), "Should contain conn1");
        assertTrue(subscribers.containsKey(conn2), "Should contain conn2");
        assertTrue(subscribers.containsKey(conn3), "Should contain conn3");
    }

    @Test
    public void testClientMultipleTopics() {
        int connectionId = 3001;
        String topic1 = "/topic/sports";
        String topic2 = "/topic/news";
        String topic3 = "/topic/weather";

        manager.subscribe(connectionId, topic1, "sub-sports");
        manager.subscribe(connectionId, topic2, "sub-news");
        manager.subscribe(connectionId, topic3, "sub-weather");

        assertTrue(manager.isSubscribed(connectionId, topic1), "Should be subscribed to sports");
        assertTrue(manager.isSubscribed(connectionId, topic2), "Should be subscribed to news");
        assertTrue(manager.isSubscribed(connectionId, topic3), "Should be subscribed to weather");
    }

    @Test
    public void testUnsubscribe() {
        int connectionId = 4001;
        String topic = "/topic/test3";
        String subscriptionId = "sub-4001";

        manager.subscribe(connectionId, topic, subscriptionId);
        assertTrue(manager.isSubscribed(connectionId, topic), "Should be subscribed");

        String result = manager.unsubscribe(connectionId, subscriptionId);
        assertEquals("OK", result, "Unsubscribe should succeed");

        assertFalse(manager.isSubscribed(connectionId, topic), "Should not be subscribed after unsubscribe");
    }

    @Test
    public void testUnsubscribeNonExistent() {
        int connectionId = 4002;

        String result = manager.unsubscribe(connectionId, "non-existent-sub");
        assertEquals("SUBSCRIPTION_ID_NOT_FOUND", result, "Should return error for non-existent subscription");
    }

    @Test
    public void testRemoveAllSubscriptions() {
        int connectionId = 5001;

        manager.subscribe(connectionId, "/topic/t1", "sub-t1");
        manager.subscribe(connectionId, "/topic/t2", "sub-t2");
        manager.subscribe(connectionId, "/topic/t3", "sub-t3");

        assertTrue(manager.isSubscribed(connectionId, "/topic/t1"));
        assertTrue(manager.isSubscribed(connectionId, "/topic/t2"));
        assertTrue(manager.isSubscribed(connectionId, "/topic/t3"));

        manager.removeAllSubscriptions(connectionId);

        assertFalse(manager.isSubscribed(connectionId, "/topic/t1"), "Should not be subscribed to t1");
        assertFalse(manager.isSubscribed(connectionId, "/topic/t2"), "Should not be subscribed to t2");
        assertFalse(manager.isSubscribed(connectionId, "/topic/t3"), "Should not be subscribed to t3");
    }

    @Test
    public void testGetSubscribersSnapshot() {
        String topic = "/topic/snapshot";
        int conn1 = 6001;
        int conn2 = 6002;

        manager.subscribe(conn1, topic, "sub-6001");
        manager.subscribe(conn2, topic, "sub-6002");

        Map<Integer, String> snapshot = manager.getSubscribersSnapshot(topic);

        // Verify it's a snapshot (modify shouldn't affect original)
        snapshot.put(9999, "fake-sub");

        Map<Integer, String> snapshot2 = manager.getSubscribersSnapshot(topic);
        assertFalse(snapshot2.containsKey(9999), "Snapshot should be independent");
        assertEquals(2, snapshot2.size(), "Original should still have 2 subscribers");
    }

    @Test
    public void testGetSubscribersSnapshotEmptyTopic() {
        Map<Integer, String> snapshot = manager.getSubscribersSnapshot("/topic/nonexistent");
        assertNotNull(snapshot, "Should return empty map, not null");
        assertEquals(0, snapshot.size(), "Should be empty");
    }

    @Test
    public void testMessageIdGeneration() {
        String id1 = manager.nextMessageId();
        String id2 = manager.nextMessageId();
        String id3 = manager.nextMessageId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotNull(id3);

        assertNotEquals(id1, id2, "Message IDs should be unique");
        assertNotEquals(id2, id3, "Message IDs should be unique");
        assertNotEquals(id1, id3, "Message IDs should be unique");
    }

    @Test
    public void testConcurrentSubscriptions() throws InterruptedException {
        String topic = "/topic/concurrent";
        int numThreads = 10;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int connId = 7000 + i;
            new Thread(() -> {
                try {
                    boolean result = manager.subscribe(connId, topic, "sub-" + connId);
                    if (result) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(numThreads, successCount.get(), "All concurrent subscriptions should succeed");

        Map<Integer, String> subscribers = manager.getSubscribersSnapshot(topic);
        assertEquals(numThreads, subscribers.size(), "All subscribers should be registered");
    }

    @Test
    public void testConcurrentMessageIdGeneration() throws InterruptedException {
        int numThreads = 20;
        int idsPerThread = 50;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger uniqueIds = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        String id = manager.nextMessageId();
                        if (id != null && !id.isEmpty()) {
                            uniqueIds.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(numThreads * idsPerThread, uniqueIds.get(),
                "All message ID generations should succeed");
    }

    @Test
    public void testTopicCleanupAfterUnsubscribe() {
        String topic = "/topic/cleanup";
        int conn1 = 8001;
        int conn2 = 8002;

        manager.subscribe(conn1, topic, "sub-8001");
        manager.subscribe(conn2, topic, "sub-8002");

        Map<Integer, String> snapshot1 = manager.getSubscribersSnapshot(topic);
        assertEquals(2, snapshot1.size());

        manager.unsubscribe(conn1, "sub-8001");
        manager.unsubscribe(conn2, "sub-8002");

        // After all unsubscribe, topic should be cleaned up
        Map<Integer, String> snapshot2 = manager.getSubscribersSnapshot(topic);
        assertEquals(0, snapshot2.size(), "Topic should be empty after all unsubscribe");
    }

    @Test
    public void testIsSubscribedToNonExistentTopic() {
        int connectionId = 9001;
        assertFalse(manager.isSubscribed(connectionId, "/topic/never-subscribed"),
                "Should return false for topic never subscribed to");
    }
}
