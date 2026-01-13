package bgu.spl.net.impl.stomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.srv.Connections;


public class StompMessagingProtocolImpl implements StompMessagingProtocol<String>{
    
    private boolean shouldTerminate = false;
    private int connectionId;
    private Connections<String> connections;
    private boolean isLoggedIn = false;
    private String username = null;
    private final Map<String, String> subscriptions = new HashMap<>();

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }
    
    @Override
    public void process(String message){
        String [] lines = message.split("\n");
        String frame = lines[0];
        Map <String,String> headers = new HashMap<>();

        StringBuilder body = new StringBuilder();

        int i=1;
        //reading the headers
        while (i < lines.length && !lines[i].isEmpty()){
            int splitIdx = lines[i].indexOf(':');
            if (splitIdx != -1){
                headers.put (lines[i].substring(0, splitIdx) , lines[i].substring(splitIdx+1));
            }
            i++;
        for (int j=i+1; j < lines.length; j++){
            body.append(lines[i]).append("\n");
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
                handleSubscribe();
                break;
            
            case "UNSUBSRIBE":
                handleUnsubscribe();
                break;

            case "DISCONNECT":
                handleDisconnect;
                break;

            default:
                sendError("UnKown Command");
        }

        }

    }
	@Override
    public boolean shouldTerminate(){
        return shouldTerminate;
    }

    //handlers

    private void handleConnect(Map <String,String> headers){
        String version = headers.get("accept-version");
        String host = headers.get("host");
        String login = headers.get("login");
        String passcode = headers.get("passcode");

        if (version == null || host == null || login == null || passcode == null){
            sendError("Malformed CONNECT frame" ,"Missing required headers" ,headers);
            return;
        }

        LoginStatus status = Database.getInstance().login(connectionId, login, passcode);

        switch (status) {
            case ADDED_NEW_USER:
            case LOGGED_IN_SUCCESSFULLY:
                isLoggedIn = true;
                username = login;

                String connected =
                 "CONNECTED\n" + 
                 "version:1.2\n\n" +
                 "\u0000";

                 connections.send(connectionId, connected);

                 if (headers.containsKey("receipt")) {
                sendReceipt(headers.get("receipt"));
            }
                break;

            case WRONG_PASSWORD:
                sendError ("Wrong password", "The password you entered is incorrect", headers);
                break;

            case ALREADY_LOGGED_IN:
                sendError("User already logged in", "This user already has an active connection", headers);
                break;

            case CLIENT_ALREADY_CONNECTED:
                sendError("Client already connected", "This connection is already logged in", headers);
                break;
        }
    }

    private void handleSend (Map<String,String> headers, String body){
        if (!isLoggedIn) {
            sendError(
            "Unauthorized",
            "You must login before sending messages",
            headers
        );
        return;
        }
        String destination = headers.get("destinatiion");

        if (destination == null) {
        sendError(
            "Malformed SEND frame",
            "Missing destination header",
            headers
        );
        return;
        }

        if (!subscriptions.containsKey(destination)){
            sendError(
            "Not subscribed",
            "Client is not subscribed to destination",
            headers
        );
        return;
        }

        



    }

    private void sendReceipt (String receiptId){
        String msg = "RECEIPT\n" +
            "receipt-id:" + receiptId + "\n\n" +
            "\u0000";
        connections.send(connectionId, msg);
    }

    private void sendError (String errorType, String msg, Map<String,String> headers){
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

