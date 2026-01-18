#include "../include/StompProtocol.h"
#include <iostream>
#include <sstream>
#include <fstream>
#include <algorithm>
#include <event.h>

StompProtocol::StompProtocol() :
    handler(nullptr), shouldTerminate(false), isConnected(false), 
    currentUserName(""), subscriptionIdCounter(0), receiptIdCounter(0), 
    subscriptions(), receiptActions(), gameEvents() 
{
}

void StompProtocol::setConnectionHandler(ConnectionHandler* h) {
    handler = h;
}

void StompProtocol::close() {
    shouldTerminate = true;
    isConnected = false;
}

bool StompProtocol::shouldLogout() {
    return shouldTerminate;
}

std::vector<std::string> StompProtocol::split(const std::string& str, char delimiter) {
    std::vector<std::string> tokens;
    std::string token;
    std::istringstream tokenStream(str);
    while (std::getline(tokenStream, token, delimiter)) {
        tokens.push_back(token);
    }
    return tokens;
}

void StompProtocol::processKeyboardCommand(const std::string& line) {
    std::vector<std::string> args = split(line, ' ');
    if (args.empty()) return;

    std::string command = args[0];

    // LOGIN
    if (command == "login") {
        if (isConnected) {
            std::cout << "The client is already logged in, log out before trying again" << std::endl;
            return;
        }
        if (args.size() < 4) {
            std::cout << "Usage: login {host:port} {username} {password}" << std::endl;
            return;
        }
        
        // Host extraction (kept as valid fix)
        std::string hostPort = args[1];
        std::string host = "127.0.0.1"; 
        size_t colonPos = hostPort.find(':');
        if (colonPos != std::string::npos) {
            host = hostPort.substr(0, colonPos);
        } else {
             host = hostPort;
        }

        std::string username = args[2];
        std::string password = args[3];
        currentUserName = username;

        std::string frame = "CONNECT\n";
        frame += "accept-version:1.2\n";
        frame += "host:" + host + "\n"; 
        frame += "login:" + username + "\n";
        frame += "passcode:" + password + "\n";
        frame += "\n"; 

        handler->sendFrameAscii(frame, '\0');
        return;
    }

    if (!isConnected) {
        std::cout << "Please login first" << std::endl;
        return;
    }

    // JOIN
    if (command == "join") {
        if (args.size() < 2) return;
        std::string game_name = args[1];
        
        int sub_id = subscriptionIdCounter++;
        int receipt_id = receiptIdCounter++;

        subscriptions[game_name] = sub_id;
        receiptActions[receipt_id] = "Joined channel " + game_name;

        std::string frame = "SUBSCRIBE\n";
        frame += "destination:/" + game_name + "\n";
        frame += "id:" + std::to_string(sub_id) + "\n";
        frame += "receipt:" + std::to_string(receipt_id) + "\n";
        frame += "\n";

        handler->sendFrameAscii(frame, '\0');
    }
    // EXIT
    else if (command == "exit") {
        if (args.size() < 2) return;
        std::string game_name = args[1];
        
        if (subscriptions.find(game_name) == subscriptions.end()) {
             std::cout << "Error: Not subscribed to " << game_name << std::endl;
             return;
        }
        int sub_id = subscriptions[game_name];
        int receipt_id = receiptIdCounter++;

        receiptActions[receipt_id] = "Exited channel " + game_name;
        subscriptions.erase(game_name);

        std::string frame = "UNSUBSCRIBE\n";
        frame += "id:" + std::to_string(sub_id) + "\n";
        frame += "receipt:" + std::to_string(receipt_id) + "\n";
        frame += "\n";

        handler->sendFrameAscii(frame, '\0');
    }
    // LOGOUT
    else if (command == "logout") {
        int receipt_id = receiptIdCounter++;
        receiptActions[receipt_id] = "DISCONNECT";
        
        std::string frame = "DISCONNECT\n";
        frame += "receipt:" + std::to_string(receipt_id) + "\n";
        frame += "\n";
        handler->sendFrameAscii(frame, '\0');
    }
    // REPORT
    else if (command == "report") {
        if (args.size() < 2) return;
        std::string file_path = args[1];
        
        names_and_events nne;

        try {
        nne = parseEventsFile(file_path);
        } catch (const std::exception& e) {
        std::cout << "Error reading file: " << e.what() << std::endl;
        return;
        }
        // SORTING: Split -> Sort by Time -> Merge
        std::vector<Event> before_halftime;
        std::vector<Event> after_halftime;
        
        for (const auto& event : nne.events) {
            bool is_before = true; 
            if (event.get_game_updates().count("before halftime")) {
                 if (event.get_game_updates().at("before halftime") == "false") {
                     is_before = false;
                 }
            }
            if (is_before || event.get_time()<=3060) before_halftime.push_back(event);
            else after_halftime.push_back(event);
        }
        
        auto timeComparator = [](const Event& a, const Event& b) {
            return a.get_time() < b.get_time();
        };
        std::sort(before_halftime.begin(), before_halftime.end(), timeComparator);
        std::sort(after_halftime.begin(), after_halftime.end(), timeComparator);
        
        nne.events.clear();
        nne.events.insert(nne.events.end(), before_halftime.begin(), before_halftime.end());
        nne.events.insert(nne.events.end(), after_halftime.begin(), after_halftime.end());


        std::string game_name = nne.team_a_name + "_" + nne.team_b_name;

        for (const Event& event : nne.events) {
            gameEvents[game_name][currentUserName].push_back(event);

            std::string frame = "SEND\n";
            frame += "destination:/" + game_name + "\n";
            frame += "\n";
            
            frame += "user: " + currentUserName + "\n";
            frame += "team a: " + nne.team_a_name + "\n";
            frame += "team b: " + nne.team_b_name + "\n";
            frame += "event name: " + event.get_name() + "\n";
            frame += "time: " + std::to_string(event.get_time()) + "\n";
            
            frame += "general game updates:\n";
            for (auto const& [key, val] : event.get_game_updates()) {
                 frame += key + ":" + val + "\n"; 
            }
            frame += "team a updates:\n";
            for (auto const& [key, val] : event.get_team_a_updates()) {
                 frame += key + ":" + val + "\n"; 
            }
            frame += "team b updates:\n";
             for (auto const& [key, val] : event.get_team_b_updates()) {
                 frame += key + ":" + val + "\n"; 
            }
            frame += "description:\n" + event.get_discription() + "\n";
            
            handler->sendFrameAscii(frame, '\0');
        }
    }
    // SUMMARY
    else if (command == "summary") {
        if (args.size() < 4) return;
        std::string game_name = args[1];
        std::string user_name = args[2];
        std::string file_path = args[3];

        if (gameEvents.find(game_name) == gameEvents.end()) {
            std::cout << "No events/game found for: " << game_name << std::endl;
            return;
        }
        if (gameEvents[game_name].find(user_name) == gameEvents[game_name].end()) {
             std::cout << "No events found for user: " << user_name << " in game " << game_name << std::endl;
             return;
        }

        std::vector<Event> events = gameEvents[game_name][user_name];
        
        // SORTING 
        std::vector<Event> before_halftime;
        std::vector<Event> after_halftime;
        
        for (const auto& event : events) {
            bool is_before = true; 
            if (event.get_game_updates().count("before halftime")) {
                 if (event.get_game_updates().at("before halftime") == "false") {
                     is_before = false;
                 }
            }
            if (is_before || event.get_time()<=3060) before_halftime.push_back(event);
            else after_halftime.push_back(event);
        }
        
        auto timeComparator = [](const Event& a, const Event& b) {
            return a.get_time() < b.get_time();
        };
        std::sort(before_halftime.begin(), before_halftime.end(), timeComparator);
        std::sort(after_halftime.begin(), after_halftime.end(), timeComparator);
        
        events.clear();
        events.insert(events.end(), before_halftime.begin(), before_halftime.end());
        events.insert(events.end(), after_halftime.begin(), after_halftime.end());
        
        // DEBUG: Show sorted order
        std::cout << "DEBUG: After sorting, events order:" << std::endl;
        for (const auto& ev : events) {
            std::cout << "  " << ev.get_time() << " - " << ev.get_name() << std::endl;
        }
        
        // Aggregate stats
        std::map<std::string, std::string> general_stats;
        std::map<std::string, std::string> team_a_stats;
        std::map<std::string, std::string> team_b_stats;

        for (const auto& ev : events) {
            for (auto const& [key, val] : ev.get_game_updates()) general_stats[key] = val;
            for (auto const& [key, val] : ev.get_team_a_updates()) team_a_stats[key] = val;
            for (auto const& [key, val] : ev.get_team_b_updates()) team_b_stats[key] = val;
        }

        std::ofstream outfile(file_path);
        if (!outfile.is_open()) {
            std::cout << "Error: Cannot write to file: " << file_path << std::endl;
            return;
        }
        
        if (!events.empty()) {
            outfile << events[0].get_team_a_name() << " vs " << events[0].get_team_b_name() << "\n";
            outfile << "Game stats:\n";
            
            outfile << "General stats:\n";
            for (const auto& [key, val] : general_stats) {
                outfile << key << ": " << val << "\n";
            }
            
            outfile << events[0].get_team_a_name() << " stats:\n";
            for (const auto& [key, val] : team_a_stats) {
                outfile << key << ": " << val << "\n";
            }

            outfile << events[0].get_team_b_name() << " stats:\n";
            for (const auto& [key, val] : team_b_stats) {
                outfile << key << ": " << val << "\n";
            }

            outfile << "Game event reports:\n";
            for (const auto& ev : events) {
                outfile << ev.get_time() << " - " << ev.get_name() << ":\n\n";
                outfile << ev.get_discription() << "\n\n\n";
            }
        
        outfile.close();
        std::cout << "Summary written to " << file_path << std::endl;
        }
    }
}

bool StompProtocol::processServerFrame(const std::string& frame) {
    std::stringstream s(frame);
    std::string line;
    std::string command;
    std::getline(s, command); 

    std::map<std::string, std::string> headers;
    std::string body;
    bool inBody = false;

    while (std::getline(s, line)) {
        if (line.empty()) {
            inBody = true;
            char c; 
            while(s.get(c)) body += c; 
            break;
        }
        size_t colon = line.find(':');
        if (colon != std::string::npos) {
            std::string key = line.substr(0, colon);
            std::string val = line.substr(colon+1);
            headers[key] = val;
        }
    }

    if (command == "CONNECTED") {
        isConnected = true;
        std::cout << "Login successful" << std::endl;
    }
    else if (command == "ERROR") {
        std::cout << "Received Error: " << headers["message"] << std::endl;
        std::cout << body << std::endl;
        close();
        return false;
    }
    else if (command == "RECEIPT") {
        int id = std::stoi(headers["receipt-id"]);
        if (receiptActions.find(id) != receiptActions.end()) {
            std::string action = receiptActions[id];
            
            if (action == "DISCONNECT") {
                std::cout << "Disconnected properly." << std::endl;
                close();
                return false;
            }
            
            std::cout << action << std::endl;
            receiptActions.erase(id);
        }
    }
    else if (command == "MESSAGE") {
        Event event(body);
        std::string user = "";
        
        // Correct Body Parsing (Kept as valid fix)
        std::stringstream bss(body);
        std::string bline;
        while(std::getline(bss, bline)) {
             if (bline.find("user: ") == 0) {
                 user = bline.substr(6);
                 break;
             }
        }
        
        if (user == currentUserName) {
            return true; 
        }

        std::cout << "Received frame from server:\n" << body << std::endl;
        
        std::string game_name = event.get_team_a_name() + "_" + event.get_team_b_name();
        gameEvents[game_name][user].push_back(event);
        std::cout << "DEBUG: Saved event for game: [" << game_name << "] user: [" << user << "]" << std::endl;
    }
    
    return true;
}
