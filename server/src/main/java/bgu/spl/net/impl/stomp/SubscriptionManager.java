package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SubscriptionManager {

    // singleton pattern implementation
    private static class Holder {
        private static final SubscriptionManager INSTANCE = new SubscriptionManager();
    }

    public static SubscriptionManager getInstance() {
        return Holder.INSTANCE;
    }

    // topicSubscribers is a map of topic to a map of connectionId to subscriptionId 
    private final Map<String, ConcurrentHashMap<Integer,String>> topicSubscribers = new ConcurrentHashMap<>();

    // clientSubscriptions is a map of connectionId to a map of subscriptionId to
    // topic
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String,String>> clientSubscriptions = new ConcurrentHashMap<>();

    private final AtomicInteger messageIdCounter = new AtomicInteger(0);

    private SubscriptionManager() {}

    public boolean subscribe(int connectionId, String topic, String subscriptionId) {
        
        ConcurrentHashMap<String, String> subsOfClient = clientSubscriptions.get(connectionId);
        if (subsOfClient == null){
            subsOfClient = new ConcurrentHashMap<>();
            ConcurrentHashMap<String,String> existing = clientSubscriptions.putIfAbsent(connectionId, subsOfClient);
            if (existing != null){
                subsOfClient = existing;
            }
        }
        
        //trying adding to client map 
        // if that subId already exist so the putIfAbsent will not return null
        String previousValue = subsOfClient.putIfAbsent(subscriptionId, topic);
        if (previousValue != null) {
            // subscriptionId already exists
            return false;
        }
        // adding to topic map
        ConcurrentHashMap<Integer, String> subsOfTopic = topicSubscribers.get(topic);
        if (subsOfTopic == null) {
            subsOfTopic = new ConcurrentHashMap<>();
            ConcurrentHashMap<Integer, String> existing = topicSubscribers.putIfAbsent(topic, subsOfTopic);
            if (existing != null) {
                subsOfTopic = existing;
            }
        }
        subsOfTopic.put(connectionId, subscriptionId);
        return true;
    }

    public String unsubscribe(int connectionId, String subscriptionId) {

        // get client's subscriptions
        ConcurrentHashMap<String, String> subsOfClient = clientSubscriptions.get(connectionId);

        if (subsOfClient == null) {
            return "SUBSCRIPTION_ID_NOT_FOUND";
        }

        // remove subscriptionId -> topic
        String topic = subsOfClient.remove(subscriptionId);

        if (topic == null) {
            return "TOPIC_NOT_FOUND";
        }

        // clean client map if empty
        if (subsOfClient.isEmpty()) {
            clientSubscriptions.remove(connectionId);
        }

        // remove from topicSubscribers
        ConcurrentHashMap<Integer, String> subsOfTopic = topicSubscribers.get(topic);

        if (subsOfTopic != null) {
            subsOfTopic.remove(connectionId);

            // clean topic map if empty
            if (subsOfTopic.isEmpty()) {
                topicSubscribers.remove(topic);
            }
        }

        return "OK";
    }

    public Map<Integer, String> getSubscribersSnapshot(String topic) {
        Map<Integer, String> snapshot = topicSubscribers.get(topic);
        if (snapshot == null){
            return new HashMap<Integer,String>();
        }
        return new HashMap(snapshot);
    }

    public void removeAllSubscriptions(int connectionId){
    ConcurrentHashMap<String, String> subsOfClient = clientSubscriptions.remove(connectionId);
    if (subsOfClient == null) {
        return;
    }
    for (String topic : subsOfClient.values()) {

        ConcurrentHashMap<Integer, String> subsOfTopic =
                topicSubscribers.get(topic);

        if (subsOfTopic != null) {
            subsOfTopic.remove(connectionId);
            if (subsOfTopic.isEmpty()) {
                topicSubscribers.remove(topic);
            }
        }
    }
    }

    public boolean isSubscribed(int connectionId, String topic) {
        ConcurrentHashMap<String, String> subsOfClient =
            clientSubscriptions.get(connectionId);

        if (subsOfClient == null) {
        return false;
        }
        return subsOfClient.containsValue(topic);
    }

    public String nextMessageId() {
        return String.valueOf(messageIdCounter.incrementAndGet());
    }
}



