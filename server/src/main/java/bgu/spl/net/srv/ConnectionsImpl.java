package bgu.spl.net.srv;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConnectionsImpl<T> implements Connections<T> {

    private final Map<Integer, ConnectionHandler<T>> handlersMap = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = handlersMap.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;

    }

    @Override
    public void send(String channel, T msg) {
        // we will not use that function
    }

    @Override
    public void disconnect(int connectionId) {
        handlersMap.remove(connectionId);
    }

    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        if (handler == null)
            return;
        handlersMap.put(connectionId, handler);
    }
}
