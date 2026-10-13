// =============================================================================
//  Chat.ice  -  Contrato Slice de la plataforma de chat multimedia
//  Computación en Internet I - Universidad Icesi (Taller 2, Unidad 2)
//
//  Organización:
//    1. Tipos base y excepciones personalizadas
//    2. Estructuras (mensajes, salas, archivos, llamadas)
//    3. ClientCallback  : lo que el SERVIDOR invoca sobre el CLIENTE (eventos)
//    4. Session         : lo que el CLIENTE invoca sobre el SERVIDOR (tras login)
//    5. ChatServer      : punto de entrada público (login)
//
//  Nota: el audio de las llamadas NO viaja por Ice. Ice solo hace la
//  señalización (quién llama, IP/puerto UDP, aceptar, colgar). La voz va por UDP.
// =============================================================================

#pragma once

module chat
{
    // ------------------------------------------------------------------ tipos
    sequence<byte>   ByteSeq;
    sequence<string> StringSeq;

    // ------------------------------------------------------------ excepciones
    /** Excepción base de la aplicación: siempre trae un mensaje legible. */
    exception ChatException
    {
        string reason;
    };

    /** El nickname ya pertenece a otra sesión activa (RF-01). */
    exception NicknameInUseException extends ChatException {};

    /** Nickname vacío, con caracteres inválidos o sesión duplicada. */
    exception InvalidNicknameException extends ChatException {};

    /** El usuario destino no existe o se desconectó (RF-02). */
    exception UserNotFoundException extends ChatException {};

    /** Errores de salas: no existe, ya existe, no es miembro... (RF-03). */
    exception RoomException extends ChatException {};

    /** Errores en la transferencia por bloques (RF-04). */
    exception FileTransferException extends ChatException {};

    /** Errores de señalización de llamadas (RF-05 / RF-06). */
    exception CallException extends ChatException {};

    // ------------------------------------------------------------ estructuras
    /** Mensaje de texto. room == "" significa mensaje privado 1 a 1. */
    struct ChatMessage
    {
        string sender;
        string target;
        string room;
        string text;
        long   timestamp;   // epoch millis asignado por el servidor
    };

    struct RoomInfo
    {
        string name;
        string owner;
        int    memberCount;
        int    inVoiceCall;  // participantes en la llamada grupal de la sala
    };
    sequence<RoomInfo> RoomInfoSeq;

    /** Metadatos de un archivo que se enviará por bloques (chunks). */
    struct FileMeta
    {
        string transferId;
        string fileName;
        long   totalSize;    // bytes
        int    chunkSize;    // bytes por bloque (32-64 KB)
        int    totalChunks;
        string sender;
        string room;         // "" = envío privado
    };

    /** Dirección donde un cliente escucha audio UDP. */
    struct UdpEndpoint
    {
        string host;
        int    port;
    };

    /** Respuesta al unirse a una llamada grupal (relay en el servidor). */
    struct ConferenceInfo
    {
        string    room;
        int       relayPort;     // puerto UDP del relay en el servidor
        int       ssrc;          // id de flujo asignado a este participante
        StringSeq participants;  // quiénes están ya en la llamada
    };

    // ------------------------------------------------------ callbacks (S -> C)
    interface ClientCallback
    {
        // Presencia (RF-01)
        void userConnected(string nick);
        void userDisconnected(string nick);

        // Mensajería (RF-02 / RF-03)
        void privateMessage(ChatMessage msg);
        void roomMessage(ChatMessage msg);
        void roomMembership(string room, string nick, bool joined);

        // Archivos por bloques (RF-04)
        void fileStarted(FileMeta meta);
        void fileChunk(string transferId, int index, ByteSeq data);
        void fileFinished(string transferId, string sha256);
        void fileAborted(string transferId, string reason);

        // Llamadas 1 a 1 (RF-05)
        void incomingCall(string callId, string caller, UdpEndpoint callerEndpoint);
        void callRinging(string callId, string callee);
        void callAccepted(string callId, string callee, UdpEndpoint calleeEndpoint);
        void callRejected(string callId, string callee);
        void callEnded(string callId, string by, string reason);

        // Llamadas grupales (RF-06)
        void conferenceUpdate(string room, string nick, bool joined);
    };

    // -------------------------------------------------- sesión (C -> S)
    interface Session
    {
        // ---- Sesión y presencia (RF-01)
        StringSeq listUsers();
        void logout();

        // ---- Mensajería privada (RF-02). Devuelve el timestamp = confirmación.
        long sendPrivateMessage(string to, string text) throws UserNotFoundException;

        // ---- Salas (RF-03)
        void createRoom(string name) throws RoomException;
        RoomInfoSeq listRooms();
        void joinRoom(string name) throws RoomException;
        void leaveRoom(string name) throws RoomException;
        StringSeq listRoomMembers(string name) throws RoomException;
        /** Devuelve a cuántos miembros se difundió el mensaje. */
        int sendRoomMessage(string room, string text) throws RoomException;

        // ---- Archivos por bloques (RF-04)
        string beginFileTransfer(string target, bool toRoom, string fileName,
                                 long totalSize, int chunkSize, int totalChunks)
            throws ChatException;
        void sendFileChunk(string transferId, int index, ByteSeq data)
            throws FileTransferException;
        void endFileTransfer(string transferId, string sha256)
            throws FileTransferException;
        void cancelFileTransfer(string transferId);

        // ---- Llamadas 1 a 1 (RF-05): solo señalización
        string startCall(string callee, UdpEndpoint myEndpoint) throws ChatException;
        void acceptCall(string callId, UdpEndpoint myEndpoint) throws CallException;
        void rejectCall(string callId) throws CallException;
        void hangup(string callId);

        // ---- Llamadas grupales (RF-06): relay UDP en el servidor
        ConferenceInfo joinConference(string room, int udpPort) throws ChatException;
        void leaveConference(string room);
    };

    // ----------------------------------------------------- punto de entrada
    interface ChatServer
    {
        /**
         * Inicia sesión con un nickname único y registra el proxy de callback.
         * Devuelve un proxy a la sesión privada del usuario.
         */
        Session* login(string nick, ClientCallback* callback)
            throws NicknameInUseException, InvalidNicknameException;
    };
};
