package bgu.spl.net.impl.stomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.srv.Connections;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private boolean shouldTerminate = false;
    private int connectionId;
    private Connections<String> connections;
    private boolean isLoggedIn = false;
    private String username = null;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String message) {
        String[] lines = message.split("\n");
        String frame = lines[0];
        Map<String, String> headers = new HashMap<>();

        StringBuilder body = new StringBuilder();

        int i = 1;
        // reading the headers
        while (i < lines.length && !lines[i].isEmpty()) {
            int splitIdx = lines[i].indexOf(':');
            if (splitIdx != -1) {
                headers.put(lines[i].substring(0, splitIdx), lines[i].substring(splitIdx + 1));
            }
            i++;
        }

        // reading the body
        for (int j = i + 1; j < lines.length; j++) {
            body.append(lines[j]).append("\n");
        }

        String bodyToString = body.toString().trim();

        switch (frame) {

            case ("CONNECT"):
                handleConnect(headers);
                break;

            case "SEND":
                handleSend(headers, bodyToString);
                break;

            case "SUBSCRIBE":
                handleSubscribe(headers);
                break;

            case "UNSUBSCRIBE":
                handleUnsubscribe(headers);
                break;

            case "DISCONNECT":
                handleDisconnect(headers);
                break;

            default:
                sendError("UnKown Command", "command doesnt exist", headers);
        }

    }

    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // handlers

    private void handleConnect(Map<String, String> headers) {
        String version = headers.get("accept-version");
        String host = headers.get("host");
        String login = headers.get("login");
        String passcode = headers.get("passcode");

        if (version == null || host == null || login == null || passcode == null) {
            sendError("Malformed CONNECT frame", "Missing required headers", headers);
            return;
        }

        LoginStatus status = Database.getInstance().login(connectionId, login, passcode);

        switch (status) {
            case ADDED_NEW_USER:
            case LOGGED_IN_SUCCESSFULLY:
                isLoggedIn = true;
                username = login;

                String connected = "CONNECTED\n" +
                        "version:1.2\n\n" +
                        "\u0000";

                connections.send(connectionId, connected);

                if (headers.containsKey("receipt")) {
                    sendReceipt(headers.get("receipt"));
                }
                break;

            case WRONG_PASSWORD:
                sendError("Wrong password", "The password you entered is incorrect", headers);
                break;

            case ALREADY_LOGGED_IN:
                sendError("User already logged in", "This user already has an active connection", headers);
                break;

            case CLIENT_ALREADY_CONNECTED:
                sendError("Client already connected", "This connection is already logged in", headers);
                break;
        }
    }

    private void handleSend(Map<String, String> headers, String body) {
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before sending messages",
                    headers);
            return;
        }
        String destination = headers.get("destination");

        if (destination == null) {
            sendError(
                    "Malformed SEND frame",
                    "Missing destination header",
                    headers);
            return;
        }
        SubscriptionManager manager = SubscriptionManager.getInstance();
        if (!manager.isSubscribed(connectionId, destination)) {
            sendError(
                    "Not subscribed",
                    "Client is not subscribed to destination",
                    headers);
            return;
        }

        Map<Integer, String> subscribers = manager.getSubscribersSnapshot(destination);
        for (Map.Entry<Integer, String> subscriberEntry : subscribers.entrySet()) {
            Integer otherId = subscriberEntry.getKey();
            String otherSubId = subscriberEntry.getValue();
            String msg = "MESSAGE\n" +
                    "subscription:" + otherSubId + "\n" +
                    "message-id:" + manager.nextMessageId() + "\n" +
                    "destination:" + destination + "\n\n" +
                    body +
                    "\u0000";

            connections.send(otherId, msg);

        }
        if (headers.containsKey("receipt")) {
            sendReceipt(headers.get("receipt"));
        }

    }

    private void handleSubscribe(Map<String, String> headers) {

        // Checks if the user is connected
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before subscribing",
                    headers);
            return;
        }

        String destination = headers.get("destination");
        String id = headers.get("id");
        if (destination == null || id == null) {
            sendError("Malformed SUBSCRIBE frame", "Missing required headers", headers);
            return;
        }
        SubscriptionManager manager = SubscriptionManager.getInstance();
        boolean success = manager.subscribe(connectionId, destination, id);
        if (!success) {
            sendError("Failed subscribe", "Duplicate subscription id", headers);
            return;
        }
        if (headers.containsKey("receipt")) {
            sendReceipt(headers.get("receipt"));
        }
    }

    private void handleUnsubscribe(Map<String, String> headers) {
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before unsubscribing",
                    headers);
            return;
        }
        String id = headers.get("id");
        if (id == null) {
            sendError("Malformed UNSUBSCRIBE frame", "Missing required headers", headers);
            return;
        }
        SubscriptionManager manager = SubscriptionManager.getInstance();
        String result = manager.unsubscribe(connectionId, id);
        if (!result.equals("OK")) {
            sendError("Malformed UNSUBSCRIBE frame", "Subscription id does not exist", headers);
            return;
        }
        if (headers.containsKey("receipt")) {
            sendReceipt(headers.get("receipt"));
        }
    }

    private void handleDisconnect(Map<String, String> headers) {
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before unsubscribing",
                    headers);
            return;
        }
        String receiptId = headers.get("receipt");
        if (receiptId == null) {
            sendError("Malformed DISCONNECT frame",
                    "Missing required headers- you must add receipt id to disconnect", headers);
            return;
        }
        SubscriptionManager manager = SubscriptionManager.getInstance();
        manager.removeAllSubscriptions(connectionId);
        Database.getInstance().logout(connectionId);
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    private void sendReceipt(String receiptId) {

        String msg = "RECEIPT\n" +
                "receipt-id:" + receiptId + "\n\n" +
                "\u0000";
        connections.send(connectionId, msg);
    }

    private void sendError(String errorType, String msg, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();

        sb.append("ERROR\n");

        if (headers != null && headers.containsKey("receipt")) {
            sb.append("receipt-id: ").append(headers.get("receipt")).append("\n");
        }
        sb.append("message: ").append(errorType).append("\n\n");

        if (msg != null && !msg.isEmpty()) {
            sb.append(msg).append("\n");
        }

        sb.append("\u0000");

        connections.send(connectionId, sb.toString());

        shouldTerminate = true;
        connections.disconnect(connectionId);
    }
}