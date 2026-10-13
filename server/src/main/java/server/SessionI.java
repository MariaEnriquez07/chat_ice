package server;

import chat.CallException;
import chat.ChatException;
import chat.ChatMessage;
import chat.ConferenceInfo;
import chat.FileTransferException;
import chat.RoomException;
import chat.RoomInfo;
import chat.Session;
import chat.UdpEndpoint;
import chat.UserNotFoundException;
import com.zeroc.Ice.Current;

import java.util.List;

/**
 * Servant de la sesión de UN usuario. Se crea en el login con una identidad
 * aleatoria (UUID), así cada operación sabe quién la invoca sin que el cliente
 * tenga que mandar su nickname (y sin poder suplantar a otro).
 *
 * <p>Esta clase solo valida y delega; la lógica vive en los registros.
 */
final class SessionI implements Session {

    private static final int MAX_TEXT = 4000;

    private final ConnectedUser me;
    private final ServerContext ctx;

    SessionI(ConnectedUser me, ServerContext ctx) {
        this.me = me;
        this.ctx = ctx;
    }

    // ------------------------------------------------------- sesión / RF-01
    @Override
    public String[] listUsers(Current current) {
        return ctx.users.nicks();
    }

    @Override
    public void logout(Current current) {
        ctx.users.drop(me, "logout");
    }

    // --------------------------------------------------- privados / RF-02
    @Override
    public long sendPrivateMessage(String to, String text, Current current) throws UserNotFoundException {
        ConnectedUser dest = ctx.users.get(to);
        if (dest == null) {
            throw new UserNotFoundException("El usuario '" + to + "' no existe o se desconectó");
        }
        long ts = System.currentTimeMillis();
        ChatMessage msg = new ChatMessage(me.nick, dest.nick, "", clip(text), ts);
        ctx.users.notify(dest, cb -> cb.privateMessageAsync(msg));
        return ts; // confirmación: el servidor aceptó y despachó el mensaje
    }

    // ------------------------------------------------------ salas / RF-03
    @Override
    public void createRoom(String name, Current current) throws RoomException {
        ctx.rooms.create(me, name);
    }

    @Override
    public RoomInfo[] listRooms(Current current) {
        List<RoomRegistry.Room> all = ctx.rooms.all();
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        RoomInfo[] out = new RoomInfo[all.size()];
        for (int i = 0; i < out.length; i++) {
            RoomRegistry.Room r = all.get(i);
            out[i] = new RoomInfo(r.name, r.owner, r.members.size(), ctx.calls.conferenceSize(r.name));
        }
        return out;
    }

    @Override
    public void joinRoom(String name, Current current) throws RoomException {
        ctx.rooms.join(me, name);
    }

    @Override
    public void leaveRoom(String name, Current current) throws RoomException {
        RoomRegistry.Room r = ctx.rooms.requireMember(me, name);
        ctx.calls.leaveConference(me, r.name); // salir de la sala implica salir de su llamada
        ctx.rooms.leave(me, r.name);
    }

    @Override
    public String[] listRoomMembers(String name, Current current) throws RoomException {
        return ctx.rooms.members(name);
    }

    @Override
    public int sendRoomMessage(String room, String text, Current current) throws RoomException {
        return ctx.rooms.broadcast(me, room, clip(text));
    }

    // --------------------------------------------------- archivos / RF-04
    @Override
    public String beginFileTransfer(String target, boolean toRoom, String fileName, long totalSize,
                                    int chunkSize, int totalChunks, Current current) throws ChatException {
        return ctx.files.begin(me, target, toRoom, fileName, totalSize, chunkSize, totalChunks);
    }

    @Override
    public void sendFileChunk(String transferId, int index, byte[] data, Current current)
            throws FileTransferException {
        ctx.files.chunk(me, transferId, index, data);
    }

    @Override
    public void endFileTransfer(String transferId, String sha256, Current current)
            throws FileTransferException {
        ctx.files.end(me, transferId, sha256);
    }

    @Override
    public void cancelFileTransfer(String transferId, Current current) {
        ctx.files.cancel(me, transferId, "cancelada por " + me.nick);
    }

    // ---------------------------------------------- llamadas 1 a 1 / RF-05
    @Override
    public String startCall(String callee, UdpEndpoint myEndpoint, Current current) throws ChatException {
        return ctx.calls.start(me, callee, myEndpoint);
    }

    @Override
    public void acceptCall(String callId, UdpEndpoint myEndpoint, Current current) throws CallException {
        ctx.calls.accept(me, callId, myEndpoint);
    }

    @Override
    public void rejectCall(String callId, Current current) throws CallException {
        ctx.calls.reject(me, callId);
    }

    @Override
    public void hangup(String callId, Current current) {
        ctx.calls.hangup(me, callId);
    }

    // --------------------------------------------- llamadas grupales / RF-06
    @Override
    public ConferenceInfo joinConference(String room, int udpPort, Current current) throws ChatException {
        return ctx.calls.joinConference(me, room, udpPort);
    }

    @Override
    public void leaveConference(String room, Current current) {
        ctx.calls.leaveConference(me, room);
    }

    private static String clip(String text) {
        String t = text == null ? "" : text;
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }
}
