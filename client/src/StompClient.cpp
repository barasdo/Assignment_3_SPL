#include <stdlib.h>
#include "../include/StompProtocol.h"
#include <thread>

int main(int argc, char *argv[]) {
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " host port" << std::endl << std::endl;
        return -1;
    }
    std::string host = argv[1];
    short port = atoi(argv[2]);

    ConnectionHandler connectionHandler(host, port);
    if (!connectionHandler.connect()) {
        std::cerr << "Cannot connect to " << host << ":" << port << std::endl;
        return 1;
    }

    StompProtocol protocol;
    protocol.setConnectionHandler(&connectionHandler);

    // Thread for reading from socket
    std::thread socketThread([&connectionHandler, &protocol]() {
        while (1) {
            std::string answer;
             // Get frame (blocking)
            if (!connectionHandler.getFrameAscii(answer, '\0')) {
                std::cout << "Disconnected from server." << std::endl;
                protocol.close(); 
                break;
            }
            
            bool shouldContinue = protocol.processServerFrame(answer);
            if (!shouldContinue) {
                break; 
            }
        }
    });

    // Main thread handles keyboard input
    while (1) {
        const short bufsize = 1024;
        char buf[bufsize];
        std::cin.getline(buf, bufsize);
        std::string line(buf);
        
        protocol.processKeyboardCommand(line);
        
        if (protocol.shouldLogout()) {
             // With the "Immediate" architecture, we don't have a sophisticated join mechanism here.
             // We'll just break if the protocol says so.
             // Usually we wait for valid logout in protocol.
        }
        if (!protocol.isClientConnected() && line == "logout") { 
            break;
        }
    }

    socketThread.join();
    return 0;
}
