package client;

import chat.ChatMessage;
import chat.ClientCallback;
import chat.FileMeta;
import chat.UdpEndpoint;
import com.zeroc.Ice.Current;

/**
 * Servant de callbacks: lo que el servidor invoca sobre este cliente.
 *
 * <p>Corre en el hilo de Ice (pool cliente), por eso NUNCA hace llamadas
 * síncronas al servidor: solo imprime y actualiza estado local. Las acciones
 * que requieren red las dispara el usuario desde la consola.
 */
final class CallbackI implements ClientCallback {

    private volatile FileReceiver files;
    private volatile CallController calls;

    void setMyNick(String nick) {
        FileReceiver f = files;
        if (f != null) {
            f.setNick(nick); // los archivos se guardan en downloads/<nick>/
        }
    }

    void setFileReceiver(FileReceiver files) {
        this.files = files;
    }

    void setCallController(CallController calls) {
        this.calls = calls;
    }

    // ------------------------------------------------------------- presencia
    @Override
    public void userConnected(String nick, Current current) {
        Console.event(Console.c(Console.GREEN, "+ " + nick + " se conectó"));
    }

    @Override
    public void userDisconnected(String nick, Current current) {
        Console.event(Console.c(Console.DIM, "- " + nick + " se desconectó"));
    }

    // ------------------------------------------------------------- mensajes
    @Override
    public void privateMessage(ChatMessage msg, Current current) {
        Console.event(Console.c(Console.DIM, "[" + Console.now() + "] ")
                + Console.c(Console.MAGENTA, "(privado) " + msg.sender) + ": " + msg.text);
    }

    @Override
    public void roomMessage(ChatMessage msg, Current current) {
        Console.event(Console.c(Console.DIM, "[" + Console.now() + "] ")
                + Console.c(Console.BLUE, "#" + msg.room) + " "
                + Console.c(Console.BOLD, msg.sender) + ": " + msg.text);
    }

    @Override
    public void roomMembership(String room, String nick, boolean joined, Current current) {
        Console.event(Console.c(Console.DIM, "#" + room + ": " + nick
                + (joined ? " entró a la sala" : " salió de la sala")));
    }

    // ------------------------------------------------------------- archivos
    @Override
    public void fileStarted(FileMeta meta, Current current) {
        FileReceiver f = files;
        if (f != null) {
            f.onStarted(meta);
        }
    }

    @Override
    public void fileChunk(String transferId, int index, byte[] data, Current current) {
        FileReceiver f = files;
        if (f != null) {
            f.onChunk(transferId, index, data);
        }
    }

    @Override
    public void fileFinished(String transferId, String sha256, Current current) {
        FileReceiver f = files;
        if (f != null) {
            f.onFinished(transferId, sha256);
        }
    }

    @Override
    public void fileAborted(String transferId, String reason, Current current) {
        FileReceiver f = files;
        if (f != null) {
            f.onAborted(transferId, reason);
        }
    }

    // ------------------------------------------------------------- llamadas
    @Override
    public void incomingCall(String callId, String caller, UdpEndpoint callerEndpoint, Current current) {
        CallController c = calls;
        if (c != null) {
            c.onIncomingCall(callId, caller, callerEndpoint);
        }
    }

    @Override
    public void callRinging(String callId, String callee, Current current) {
        CallController c = calls;
        if (c != null) {
            c.onRinging(callId, callee);
        }
    }

    @Override
    public void callAccepted(String callId, String callee, UdpEndpoint calleeEndpoint, Current current) {
        CallController c = calls;
        if (c != null) {
            c.onAccepted(callId, callee, calleeEndpoint);
        }
    }

    @Override
    public void callRejected(String callId, String callee, Current current) {
        CallController c = calls;
        if (c != null) {
            c.onRejected(callId, callee);
        }
    }

    @Override
    public void callEnded(String callId, String by, String reason, Current current) {
        CallController c = calls;
        if (c != null) {
            c.onEnded(callId, by, reason);
        }
    }

    @Override
    public void conferenceUpdate(String room, String nick, boolean joined, Current current) {
        CallController c = calls;
        if (c != null) {
            c.onConferenceUpdate(room, nick, joined);
        }
    }
}
