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
    public String process(String message) {
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
                handleConnect(headers, message);
                break;

            case "SEND":
                handleSend(headers, bodyToString, message);
                break;

            case "SUBSCRIBE":
                handleSubscribe(headers, message);
                break;

            case "UNSUBSCRIBE":
                handleUnsubscribe(headers, message);
                break;

            case "DISCONNECT":
                handleDisconnect(headers, message);
                break;

            default:
                sendError("UnKnown Command", "command doesnt exist", headers, message);
        }
        return null;

    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // handlers

    private void handleConnect(Map<String, String> headers, String message) {
        String version = headers.get("accept-version");
        String host = headers.get("host");
        String login = headers.get("login");
        String passcode = headers.get("passcode");

        if (version == null || host == null || login == null || passcode == null) {
            sendError("Malformed CONNECT frame", "Missing required headers", headers, message);
            return;
        }

        if (!version.equals("1.2")) {
            sendError("Unsupported version",
                    "Server supports STOMP version 1.2, but client requested: " + version,
                    headers, message);
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
                sendError("Wrong password", "The password you entered is incorrect", headers, message);
                break;

            case ALREADY_LOGGED_IN:
                sendError("User already logged in", "This user already has an active connection", headers, message);
                break;

            case CLIENT_ALREADY_CONNECTED:
                sendError("Client already connected", "This connection is already logged in", headers, message);
                break;
        }
    }

    private void handleSend(Map<String, String> headers, String body, String message) {
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before sending messages",
                    headers, message);
            return;
        }
        String destination = headers.get("destination");

        if (destination == null) {
            sendError(
                    "Malformed SEND frame",
                    "Missing destination header",
                    headers, message);
            return;
        }
        SubscriptionManager manager = SubscriptionManager.getInstance();
        if (!manager.isSubscribed(connectionId, destination)) {
            sendError(
                    "Not subscribed",
                    "Client is not subscribed to destination:" + destination,
                    headers, message);
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

    private void handleSubscribe(Map<String, String> headers, String message) {

        // Checks if the user is connected
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before subscribing",
                    headers, message);
            return;
        }

        String destination = headers.get("destination");
        String id = headers.get("id");
        if (destination == null || id == null) {
            sendError("Malformed SUBSCRIBE frame", "Missing required headers", headers, message);
            return;
        }
        SubscriptionManager manager = SubscriptionManager.getInstance();
        boolean success = manager.subscribe(connectionId, destination, id);
        if (!success) {
            sendError("Failed subscribe", "Duplicate subscription id", headers, message);
            return;
        }
        if (headers.containsKey("receipt")) {
            sendReceipt(headers.get("receipt"));
        }
    }

    private void handleUnsubscribe(Map<String, String> headers, String message) {
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before unsubscribing",
                    headers, message);
            return;
        }
        String id = headers.get("id");
        if (id == null) {
            sendError("Malformed UNSUBSCRIBE frame", "Missing required headers", headers, message);
            return;
        }
        SubscriptionManager manager = SubscriptionManager.getInstance();
        String result = manager.unsubscribe(connectionId, id);
        if (!result.equals("OK")) {
            sendError("Malformed UNSUBSCRIBE frame", "Subscription id does not exist", headers, message);
            return;
        }
        if (headers.containsKey("receipt")) {
            sendReceipt(headers.get("receipt"));
        }
    }

    private void handleDisconnect(Map<String, String> headers, String message) {
        if (!isLoggedIn) {
            sendError(
                    "Unauthorized",
                    "You must login before disconnecting",
                    headers, message);
            return;
        }
        String receiptId = headers.get("receipt");
        if (receiptId == null) {
            sendError("Malformed DISCONNECT frame",
                    "Missing required headers- you must add receipt id to disconnect", headers, message);
            return;
        }
        sendReceipt(receiptId);
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

    private void sendError(String errorType,
            String detailedExplanation,
            Map<String, String> headers,
            String originalMessage) {

        StringBuilder sb = new StringBuilder();

        sb.append("ERROR\n");

        if (headers != null && headers.containsKey("receipt")) {
            sb.append("receipt-id:")
                    .append(headers.get("receipt"))
                    .append("\n");
        }

        sb.append("message:")
                .append(errorType)
                .append("\n\n");

        sb.append("The message:\n");
        sb.append("-----\n");

        if (originalMessage != null && !originalMessage.isEmpty()) {

            sb.append(originalMessage.replace("\u0000", ""))
                    .append("\n");
        }

        sb.append("-----\n");
        sb.append(detailedExplanation)
                .append("\n");

        sb.append("\u0000");

        connections.send(connectionId, sb.toString());

        if (isLoggedIn) {
            SubscriptionManager.getInstance().removeAllSubscriptions(connectionId);
            Database.getInstance().logout(connectionId);
        }

        shouldTerminate = true;
        connections.disconnect(connectionId);
    }
}