#pragma once
#include "../include/ConnectionHandler.h"
#include <mutex>
#include <map>
#include <vector>
#include <string>
#include <event.h>

// TODO: implement the STOMP protocol
class StompProtocol
{
private:
    ConnectionHandler* handler;
    bool shouldTerminate;
    bool isConnected;

    std::string currentUserName;
    int subscriptionIdCounter;
    int receiptIdCounter;
    
    // Maps
    std::map<std::string, int> subscriptions; // game_name -> sub_id
    std::map<std::string, std::map<std::string, std::vector<Event>>> gameEvents; // Game State: game_name -> (user_name -> list of events)

    // Receipt handling: receiptId -> Description of action (e.g., "DISCONNECT", "joined channel X")
    std::map<int, std::string> receiptActions; 
    
    std::mutex mtx;

    std::vector<std::string> split(const std::string& str, char delimiter);
    std::string readFile(const std::string& path);

public:
    StompProtocol();
    void setConnectionHandler(ConnectionHandler* h);
    
    // Called by Keyboard Thread
    void processKeyboardCommand(const std::string& line);
    
    // Called by Socket Thread
    bool processServerFrame(const std::string& frame);

    // Helpers
    void close();
    bool shouldLogout();
    bool isClientConnected() const { return isConnected; }
};
