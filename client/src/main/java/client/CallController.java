package client;

import chat.ChatException;
import chat.ConferenceInfo;
import chat.UdpEndpoint;
import com.zeroc.Ice.LocalException;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;

/**
 * Máquina de estados de las llamadas del cliente (RF-05 / RF-06).
 *
 * <pre>
 *  IDLE ──/call──▶ CALLING ──callAccepted──▶ IN_CALL ──/hangup|callEnded──▶ IDLE
 *  IDLE ──incomingCall──▶ RINGING_IN ──/accept──▶ IN_CALL
 *                                  └──/reject──▶ IDLE
 *  IDLE ──/gcall sala──▶ IN_CONFERENCE ──/hangup──▶ IDLE
 * </pre>
 *
 * <p>Regla de concurrencia: el candado {@code lock} protege solo el estado
 * local y NUNCA se mantiene durante una invocación remota. Los callbacks de
 * Ice corren en un único hilo; si esperaran al candado mientras el hilo de la
 * consola espera la respuesta del servidor, habría un interbloqueo.
 */
final class CallController {

    enum Mode { IDLE, CALLING, RINGING_IN, IN_CALL, IN_CONFERENCE }

    private final ChatClient client;
    private final AudioEngine audio = new AudioEngine();
    private final int preferredUdpPort;
    private final Object lock = new Object();

    private Mode mode = Mode.IDLE;
    private String callId;
    private String peer;
    private UdpEndpoint peerEndpoint;
    private String conferenceRoom;

    CallController(ChatClient client, int preferredUdpPort) {
        this.client = client;
        this.preferredUdpPort = preferredUdpPort;
    }

    // =================================================== comandos de usuario
    void call(String nick) {
        synchronized (lock) {
            if (mode != Mode.IDLE) {
                Console.error("Ya tienes una llamada en curso (" + describe() + "). Usa /hangup.");
                return;
            }
            mode = Mode.CALLING;
            peer = nick;
            callId = null;
        }
        try {
            int port = audio.open(preferredUdpPort);
            UdpEndpoint me = new UdpEndpoint(client.localIp(), port);
            Console.info("Llamando a " + nick + " (audio UDP en " + me.host + ":" + me.port + ")... /hangup para cancelar");
            String id = client.session().startCall(nick, me);
            synchronized (lock) {
                if (mode == Mode.CALLING && callId == null) {
                    callId = id;
                }
            }
        } catch (ChatException e) {
            resetIfCalling();
            Console.error("No se pudo llamar: " + e.reason);
        } catch (SocketException e) {
            resetIfCalling();
            Console.error("No se pudo abrir el socket UDP: " + e.getMessage());
        }
    }

    void accept() {
        String id;
        UdpEndpoint remote;
        String caller;
        synchronized (lock) {
            if (mode != Mode.RINGING_IN) {
                Console.error("No tienes llamadas entrantes");
                return;
            }
            id = callId;
            remote = peerEndpoint;
            caller = peer;
        }
        try {
            int port = audio.open(preferredUdpPort);
            client.session().acceptCall(id, new UdpEndpoint(client.localIp(), port));
            synchronized (lock) {
                if (mode != Mode.RINGING_IN || !id.equals(callId)) {
                    return; // colgaron justo antes
                }
                mode = Mode.IN_CALL;
            }
            audio.start(List.of(new InetSocketAddress(remote.host, remote.port)), 0);
            Console.ok("En llamada con " + caller + ". /mute para silenciar, /hangup para colgar");
        } catch (ChatException e) {
            reset();
            Console.error("No se pudo contestar: " + e.reason);
        } catch (SocketException e) {
            Console.error("No se pudo abrir el audio UDP: " + e.getMessage());
            hangup();
        }
    }

    void reject() {
        String id;
        synchronized (lock) {
            if (mode != Mode.RINGING_IN) {
                Console.error("No tienes llamadas entrantes");
                return;
            }
            id = callId;
        }
        reset();
        try {
            client.session().rejectCall(id);
            Console.info("Llamada rechazada");
        } catch (ChatException e) {
            Console.error(e.reason);
        }
    }

    void hangup() {
        Mode m;
        String id;
        String room;
        synchronized (lock) {
            m = mode;
            id = callId;
            room = conferenceRoom;
        }
        if (m == Mode.IDLE) {
            Console.error("No estás en ninguna llamada");
            return;
        }
        reset();
        try {
            if (m == Mode.IN_CONFERENCE) {
                client.session().leaveConference(room);
                Console.info("Saliste de la llamada grupal de #" + room);
            } else if (m == Mode.RINGING_IN) {
                client.session().rejectCall(id);
                Console.info("Llamada rechazada");
            } else if (id != null) {
                client.session().hangup(id);
                Console.info("Llamada finalizada");
            }
        } catch (ChatException e) {
            Console.error(e.reason);
        } catch (LocalException e) {
            Console.error("No se pudo avisar al servidor: " + e);
        }
    }

    void joinConference(String room) {
        synchronized (lock) {
            if (mode != Mode.IDLE) {
                Console.error("Ya tienes una llamada en curso (" + describe() + "). Usa /hangup.");
                return;
            }
            mode = Mode.IN_CONFERENCE;
            conferenceRoom = room;
        }
        try {
            int port = audio.open(preferredUdpPort);
            ConferenceInfo info = client.session().joinConference(room, port);
            synchronized (lock) {
                conferenceRoom = info.room;
            }
            InetSocketAddress relay = new InetSocketAddress(client.serverIp(), info.relayPort);
            audio.start(List.of(relay), info.ssrc);
            String others = info.participants.length == 0
                    ? "eres el primero, esperando a los demás"
                    : "con " + String.join(", ", info.participants);
            Console.ok("En la llamada grupal de #" + info.room + " (" + others + "). /mute, /hangup");
        } catch (ChatException e) {
            reset();
            Console.error("No se pudo unir a la llamada: " + e.reason);
        } catch (SocketException e) {
            reset();
            Console.error("No se pudo abrir el audio UDP: " + e.getMessage());
        }
    }

    void toggleMute() {
        synchronized (lock) {
            if (mode != Mode.IN_CALL && mode != Mode.IN_CONFERENCE) {
                Console.error("No estás en una llamada activa");
                return;
            }
        }
        boolean muted = audio.toggleMute();
        Console.info(muted ? "Micrófono SILENCIADO (/mute para reactivar)" : "Micrófono ACTIVO");
    }

    String status() {
        synchronized (lock) {
            if (!audio.isRunning()) {
                return describe();
            }
            return describe() + (audio.isMuted() ? " [mute]" : "") + " | " + audio.stats();
        }
    }

    // ================================================== eventos del servidor
    void onIncomingCall(String id, String caller, UdpEndpoint ep) {
        synchronized (lock) {
            if (mode != Mode.IDLE) {
                return; // el servidor no debería enviarlo si estamos ocupados
            }
            mode = Mode.RINGING_IN;
            callId = id;
            peer = caller;
            peerEndpoint = ep;
        }
        Console.event(Console.c(Console.YELLOW + Console.BOLD,
                "[llamada] *** " + caller + " te está llamando *** -> /accept o /reject"));
    }

    void onRinging(String id, String callee) {
        synchronized (lock) {
            if (!matchesOutgoing(id)) {
                return;
            }
            callId = id;
        }
        Console.event(Console.c(Console.YELLOW, "[llamada] Timbrando en el equipo de " + callee + "..."));
    }

    void onAccepted(String id, String callee, UdpEndpoint ep) {
        synchronized (lock) {
            if (!matchesOutgoing(id)) {
                return;
            }
            callId = id;
            mode = Mode.IN_CALL;
            peerEndpoint = ep;
        }
        try {
            audio.start(List.of(new InetSocketAddress(ep.host, ep.port)), 0);
            Console.event(Console.c(Console.GREEN, "[llamada] " + callee
                    + " contestó. En llamada (UDP -> " + ep.host + ":" + ep.port + "). /mute, /hangup"));
        } catch (SocketException e) {
            Console.event(Console.c(Console.RED, "[error] No se pudo iniciar el audio: " + e.getMessage()));
        }
    }

    void onRejected(String id, String callee) {
        synchronized (lock) {
            if (!matchesOutgoing(id)) {
                return;
            }
        }
        reset();
        Console.event(Console.c(Console.RED, "[llamada] " + callee + " rechazó la llamada"));
    }

    void onEnded(String id, String by, String reason) {
        synchronized (lock) {
            boolean mine = id.equals(callId) || (mode == Mode.CALLING && callId == null);
            if (!mine) {
                return;
            }
        }
        reset();
        Console.event(Console.c(Console.YELLOW, "[llamada] Llamada finalizada (" + by + " " + reason + ")"));
    }

    void onConferenceUpdate(String room, String nick, boolean joined) {
        boolean inThisConference;
        synchronized (lock) {
            inThisConference = mode == Mode.IN_CONFERENCE && room.equalsIgnoreCase(conferenceRoom);
        }
        String hint = (!inThisConference && joined) ? " (únete con /gcall " + room + ")" : "";
        Console.event(Console.c(Console.CYAN, "[voz #" + room + "] " + nick
                + (joined ? " entró a la llamada grupal" : " salió de la llamada grupal") + hint));
    }

    /** Sesión cerrada o conexión perdida: se corta todo el audio localmente. */
    void onDisconnected() {
        reset();
    }

    // ================================================================ util
    private boolean matchesOutgoing(String id) {
        return mode == Mode.CALLING && (callId == null || callId.equals(id));
    }

    private void resetIfCalling() {
        synchronized (lock) {
            if (mode != Mode.CALLING) {
                return;
            }
        }
        reset();
    }

    private void reset() {
        synchronized (lock) {
            mode = Mode.IDLE;
            callId = null;
            peer = null;
            peerEndpoint = null;
            conferenceRoom = null;
        }
        audio.stop();
    }

    private String describe() {
        switch (mode) {
            case CALLING:
                return "llamando a " + peer;
            case RINGING_IN:
                return "llamada entrante de " + peer;
            case IN_CALL:
                return "en llamada con " + peer;
            case IN_CONFERENCE:
                return "en la llamada grupal de #" + conferenceRoom;
            default:
                return "sin llamada";
        }
    }
}
