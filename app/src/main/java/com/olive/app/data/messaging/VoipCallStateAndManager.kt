// Chat roles: defines planned voice-call states; calling is not implemented yet.
package com.olive.app.data

// Gemini recommends packages "kotlinx.coroutines.asFlow and kotlinx.coroutines.StateFlow" which do not appear to exit
// perhaps outdated training data.

//TODO: Create the other end of the API's
//TODO: install and setup the mumble voip server/service on yourolivebranch.ca
/*
Class VoipManager:
    This class is meant to handle the application layer of Voip
    The VoipManager will be sending api calls to the server with UUID and other information
    to create/start calls, receive calls, join calls, end/leave calls, join in progress group calls.
    Connects with a yet unwritten library hosted on yourolivebranch.ca (or even the firebase) which connects to a mumble server
 */

/*
Early draft of voipState, a way to track the status of a call.
perhaps add an array of UUIds that are in the call.
 */
sealed interface VoipState {
    object Idle : VoipState
    data class Ringing(val sessionId: String, val callerName: String) : VoipState
    data class Connecting(val sessionId: String) : VoipState
    data class Active(val sessionId: String, val participantCount: Int) : VoipState
    data class Error(val message: String) : VoipState
}

class VoipManager {
}