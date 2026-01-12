package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String>{
    
    private boolean shouldTerminate = false;
    private int connectionId;
    private Connections<String> connections;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        connectionId = this.connectionId;
        connections = this.connections;
    }
    
    @Override
    public void process(String message){
        
    }
	
	@Override
    public boolean shouldTerminate(){
        return shouldTerminate;
    }
}

