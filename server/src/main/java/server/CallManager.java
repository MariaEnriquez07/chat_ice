package server;

import chat.CallException;
import chat.ChatException;
import chat.ConferenceInfo;
import chat.UdpEndpoint;
import chat.UserNotFoundException;
import common.Protocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Señalización de llamadas sobre Ice (RF-05 y RF-06).
 *
 * <p>Llamadas 1 a 1: el servidor solo intercambia los endpoints UDP; después
 * el audio fluye directamente entre los dos clientes (punto a punto).
 *
 * <p>Llamadas grupales: estrategia (a) Relay centralizado. Cada participante
 * recibe un {@code ssrc}; {@link AudioRelay} usa ese id para saber a qué
 * llamada pertenece cada datagrama y reenviarlo a los demás.
 */
final class CallManager implements UserRegistry.Listener {

    private static final long RING_TIMEOUT_S = 30;

    enum State { RINGING, ACTIVE }

    static final class Call {
        final String id;
        final ConnectedUser caller;
        final ConnectedUser callee;
        final UdpEndpoint callerEndpoint;
        volatile UdpEndpoint calleeEndpoint;
        volatile State state = State.RINGING;

        Call(String id, ConnectedUser caller, ConnectedUser callee, UdpEndpoint callerEndpoint) {
            this.id = id;
            this.caller = caller;
            this.callee = callee;
            this.callerEndpoint = callerEndpoint;
        }

        ConnectedUser other(ConnectedUser u) {
            return u == caller ? callee : caller;
        }
    }

    static final class Participant {
        final ConnectedUser user;
        final int ssrc;
        final Conference conference;
        /** Dirección UDP: la declarada en el join, actualizada con la real al recibir. */
        volatile InetSocketAddress address;

        Participant(ConnectedUser user, int ssrc, Conference conference, InetSocketAddress address) {
            this.user = user;
            this.ssrc = ssrc;
            this.conference = conference;
            this.address = address;
        }
    }

    static final class Conference {
        final String room;
        final ConcurrentHashMap<Integer, Participant> participants = new ConcurrentHashMap<>();

        Conference(String room) {
            this.room = room;
        }
    }

    private final ConcurrentHashMap<String, Call> calls = new ConcurrentHashMap<>();
    /** userKey -> "call:<id>" o "conf:<sala>". Un usuario solo puede estar en una llamada. */
    private final ConcurrentHashMap<String, String> busy = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Conference> conferences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Participant> bySsrc = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ring-timeout");
        t.setDaemon(true);
        return t;
    });

    private final UserRegistry users;
    private final RoomRegistry rooms;
    private final int relayPort;

    CallManager(UserRegistry users, RoomRegistry rooms, int relayPort) {
        this.users = users;
        this.rooms = rooms;
        this.relayPort = relayPort;
    }

    // =============================================================== 1 a 1
    String start(ConnectedUser caller, String calleeNick, UdpEndpoint ep) throws ChatException {
        ConnectedUser callee = users.get(calleeNick);
        if (callee == null) {
            throw new UserNotFoundException("El usuario '" + calleeNick + "' no está conectado");
        }
        if (callee == caller) {
            throw new CallException("No puedes llamarte a ti mismo");
        }
        UdpEndpoint callerEp = fixEndpoint(caller, ep);
        String id = UUID.randomUUID().toString().substring(0, 8);
        if (busy.putIfAbsent(caller.key, "call:" + id) != null) {
            throw new CallException("Ya estás en una llamada; usa /hangup primero");
        }
        if (busy.putIfAbsent(callee.key, "call:" + id) != null) {
            busy.remove(caller.key, "call:" + id);
            throw new CallException(callee.nick + " está ocupado en otra llamada");
        }
        Call call = new Call(id, caller, callee, callerEp);
        calls.put(id, call);
        Log.info("Llamada " + id + ": " + caller.nick + " → " + callee.nick
                + " (UDP " + callerEp.host + ":" + callerEp.port + ")");

        users.notify(callee, cb -> cb.incomingCallAsync(id, caller.nick, callerEp))
                .whenComplete((ok, ex) -> {
                    if (ex == null) {
                        users.notify(caller, cb -> cb.callRingingAsync(id, callee.nick));
                    } else {
                        finish(call, null, "no se pudo contactar a " + callee.nick);
                    }
                });
        timer.schedule(() -> {
            if (call.state == State.RINGING && calls.containsKey(id)) {
                finish(call, null, "sin respuesta");
            }
        }, RING_TIMEOUT_S, TimeUnit.SECONDS);
        return id;
    }

    void accept(ConnectedUser callee, String callId, UdpEndpoint ep) throws CallException {
        Call call = calls.get(callId);
        if (call == null || call.callee != callee) {
            throw new CallException("No tienes una llamada entrante con id " + callId);
        }
        synchronized (call) {
            if (call.state != State.RINGING) {
                throw new CallException("La llamada ya fue contestada");
            }
            call.calleeEndpoint = fixEndpoint(callee, ep);
            call.state = State.ACTIVE;
        }
        UdpEndpoint calleeEp = call.calleeEndpoint;
        Log.info("Llamada " + callId + " aceptada por " + callee.nick
                + " (UDP " + calleeEp.host + ":" + calleeEp.port + ")");
        users.notify(call.caller, cb -> cb.callAcceptedAsync(callId, callee.nick, calleeEp));
    }

    void reject(ConnectedUser callee, String callId) throws CallException {
        Call call = calls.get(callId);
        if (call == null || call.callee != callee) {
            throw new CallException("No tienes una llamada entrante con id " + callId);
        }
        if (calls.remove(callId, call)) {
            release(call);
            Log.info("Llamada " + callId + " rechazada por " + callee.nick);
            users.notify(call.caller, cb -> cb.callRejectedAsync(callId, callee.nick));
        }
    }

    void hangup(ConnectedUser u, String callId) {
        Call call = calls.get(callId);
        if (call != null && (call.caller == u || call.callee == u)) {
            finish(call, u, "colgó");
        }
    }

    /** Termina la llamada y avisa a quien no colgó (o a ambos si by == null). */
    private void finish(Call call, ConnectedUser by, String reason) {
        if (!calls.remove(call.id, call)) {
            return;
        }
        release(call);
        String byNick = by == null ? "servidor" : by.nick;
        Log.info("Llamada " + call.id + " finalizada (" + byNick + ": " + reason + ")");
        for (ConnectedUser u : new ConnectedUser[] {call.caller, call.callee}) {
            if (u != by && users.isActive(u)) {
                users.notify(u, cb -> cb.callEndedAsync(call.id, byNick, reason));
            }
        }
    }

    private void release(Call call) {
        busy.remove(call.caller.key, "call:" + call.id);
        busy.remove(call.callee.key, "call:" + call.id);
    }

    /**
     * Si el cliente publicó una IP inútil (vacía, 0.0.0.0 o loopback desde
     * otra máquina) se usa la IP real vista en la conexión Ice.
     */
    private static UdpEndpoint fixEndpoint(ConnectedUser u, UdpEndpoint ep) throws CallException {
        if (ep == null || ep.port <= 0 || ep.port > 65535) {
            throw new CallException("Endpoint UDP inválido");
        }
        String host = Protocol.normalizeHost(ep.host);
        boolean useless = host.isEmpty() || host.equals("0.0.0.0")
                || (host.startsWith("127.") && !u.remoteHost.startsWith("127."));
        return new UdpEndpoint(useless ? u.remoteHost : host, ep.port);
    }

    // ============================================================ grupales
    ConferenceInfo joinConference(ConnectedUser u, String roomName, int udpPort) throws ChatException {
        RoomRegistry.Room room = rooms.requireMember(u, roomName);
        if (udpPort <= 0 || udpPort > 65535) {
            throw new CallException("Puerto UDP inválido");
        }
        String marker = "conf:" + room.name;
        String current = busy.putIfAbsent(u.key, marker);
        if (current != null) {
            throw new CallException(current.equals(marker)
                    ? "Ya estás en la llamada de #" + room.name
                    : "Ya estás en otra llamada; usa /hangup primero");
        }
        Conference conf = conferences.computeIfAbsent(ConnectedUser.keyOf(room.name), k -> new Conference(room.name));
        int ssrc;
        Participant p;
        synchronized (conf) {
            do {
                ssrc = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            } while (bySsrc.containsKey(ssrc));
            p = new Participant(u, ssrc, conf, new InetSocketAddress(u.remoteHost, udpPort));
            conf.participants.put(ssrc, p);
            bySsrc.put(ssrc, p);
            // por si el último participante cerró la conferencia mientras tanto
            conferences.putIfAbsent(ConnectedUser.keyOf(room.name), conf);
        }
        List<String> names = new ArrayList<>();
        for (Participant other : conf.participants.values()) {
            if (other != p) {
                names.add(other.user.nick);
            }
        }
        Log.info(u.nick + " entró a la llamada grupal de #" + room.name + " (ssrc=" + ssrc
                + ", " + conf.participants.size() + " participantes)");
        rooms.notifyMembers(room, u, false, cb -> cb.conferenceUpdateAsync(room.name, u.nick, true));
        return new ConferenceInfo(room.name, relayPort, ssrc, names.toArray(new String[0]));
    }

    void leaveConference(ConnectedUser u, String roomName) {
        RoomRegistry.Room room = rooms.find(roomName);
        String name = room != null ? room.name : roomName;
        Conference conf = conferences.get(ConnectedUser.keyOf(name));
        if (conf == null) {
            return;
        }
        Participant leaving = null;
        synchronized (conf) {
            for (Participant p : conf.participants.values()) {
                if (p.user == u) {
                    leaving = p;
                    conf.participants.remove(p.ssrc);
                    bySsrc.remove(p.ssrc);
                }
            }
            if (conf.participants.isEmpty()) {
                conferences.remove(ConnectedUser.keyOf(name), conf);
            }
        }
        if (leaving == null) {
            return;
        }
        busy.remove(u.key, "conf:" + conf.room);
        Log.info(u.nick + " salió de la llamada grupal de #" + conf.room);
        if (room != null) {
            rooms.notifyMembers(room, u, false, cb -> cb.conferenceUpdateAsync(conf.room, u.nick, false));
        }
    }

    int conferenceSize(String roomName) {
        Conference c = conferences.get(ConnectedUser.keyOf(roomName));
        return c == null ? 0 : c.participants.size();
    }

    Participant participant(int ssrc) {
        return bySsrc.get(ssrc);
    }

    // ============================================================ limpieza
    @Override
    public void userLeft(ConnectedUser u) {
        for (Call c : new ArrayList<>(calls.values())) {
            if (c.caller == u || c.callee == u) {
                finish(c, u, "se desconectó");
            }
        }
        for (Conference c : new ArrayList<>(conferences.values())) {
            leaveConference(u, c.room);
        }
    }

    void shutdown() {
        timer.shutdownNow();
    }
}
