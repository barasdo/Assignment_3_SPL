#include "../include/StompProtocol.h"
#include "../include/event.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <algorithm>
//#include "../include/Frame.h"

using namespace std;
StompProtocol::StompProtocol() :
    username(""),
    subscriptionIdCounter(0),
    receiptIdCounter(0),
    activeSubscriptions(),
    receiptActions(),
    gameEvents(),
    isLoggedIn(false){}

string StompProtocol::processInput(string commandLine){
    stringstream commandLineStream(commandLine);
    string command;
    commandLineStream >> command;

    if (command.empty()) {
        return "";
    }

    if (command == "login"){
        if (isLoggedIn){
            std::cout<<"The client is already logged in, log out before trying again";
        }
        else
        {
            /* code */
        }
        
    }
    

}