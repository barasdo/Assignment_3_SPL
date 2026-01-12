package bgu.spl.net.srv;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConnectionsImpl<T> implements Connections<T> {
    
    private final Map <Integer,ConnectionHandler<T>> handlersMap = new ConcurrentHashMap<>();
    private final Map <String,List<Integer>> gamesMap = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg){
        ConnectionHandler <T> handler = handlersMap.get(connectionId);
        if (handler != null) {
            handler.send(msg);    
            return true;
        }
        return false;
        
    }
    
    @Override
    public void send(String channel, T msg){
        List <Integer> subscribers = gamesMap.get(channel);
        if (subscribers == null) {
                return;
            }
        for (Integer id:subscribers){
            send(id, msg);
        }
    }

    @Override
    public void disconnect(int connectionId){
        handlersMap.remove(connectionId);
        
        for (List<Integer> channel : gamesMap.values()){
            channel.remove(Integer.valueOf(connectionId));  
            }
        }

    

    public void subsribe (String channel, int connectionId){
        gamesMap.putIfAbsent(channel, new CopyOnWriteArrayList<>());
        gamesMap.get(channel).add(connectionId);
        }

    public void unSubsrcibe (String channel, int connectionId){
        List <Integer> subscribers  = gamesMap.get(channel);
        if (subscribers == null){
            return;
        }
        subscribers.remove(Integer.valueOf(connectionId));
    }

    public void addConnection(int connectionId, ConnectionHandler <T> handler){
        handlersMap.put(connectionId, handler);
    }
}
            
        
    

