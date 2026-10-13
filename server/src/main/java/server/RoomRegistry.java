package server;

import chat.ChatMessage;
import chat.RoomException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Salas de chat (RF-03). Todas las colecciones son concurrentes:
 * el catálogo es un {@link ConcurrentHashMap} y los miembros de cada sala
 * un {@code ConcurrentHashMap.newKeySet()}.
 *
 * <p>Aislamiento de tráfico: un mensaje de sala solo se envía iterando los
 * miembros de ESA sala, nunca a la lista global de usuarios.
 */
final class RoomRegistry implements UserRegistry.Listener {

    static final class Room {
        final String name;
        final String owner;
        final long createdAt = System.currentTimeMillis();
        /** Claves (minúsculas) de los usuarios miembros. */
        final Set<String> members = ConcurrentHashMap.newKeySet();

        Room(String name, String owner) {
            this.name = name;
            this.owner = owner;
        }
    }

    private static final String NAME_PATTERN = "[A-Za-z0-9_\\-]{2,30}";

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final UserRegistry users;

    RoomRegistry(UserRegistry users) {
        this.users = users;
    }

    Room create(ConnectedUser creator, String name) throws RoomException {
        String clean = name == null ? "" : name.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (!clean.matches(NAME_PATTERN)) {
            throw new RoomException("Nombre de sala inválido: use 2-30 letras, números, '_' o '-'");
        }
        Room room = new Room(clean, creator.nick);
        if (rooms.putIfAbsent(ConnectedUser.keyOf(clean), room) != null) {
            throw new RoomException("La sala '" + clean + "' ya existe");
        }
        Log.info("Sala creada: #" + clean + " por " + creator.nick);
        join(creator, clean);
        return room;
    }

    Room find(String name) {
        String n = name == null ? "" : name.trim();
        if (n.startsWith("#")) {
            n = n.substring(1);
        }
        return rooms.get(ConnectedUser.keyOf(n));
    }

    Room require(String name) throws RoomException {
        Room r = find(name);
        if (r == null) {
            throw new RoomException("La sala '" + name + "' no existe");
        }
        return r;
    }

    Room requireMember(ConnectedUser u, String name) throws RoomException {
        Room r = require(name);
        if (!r.members.contains(u.key)) {
            throw new RoomException("No perteneces a la sala '" + r.name + "'");
        }
        return r;
    }

    List<Room> all() {
        return new ArrayList<>(rooms.values());
    }

    void join(ConnectedUser u, String name) throws RoomException {
        Room r = require(name);
        if (!r.members.add(u.key)) {
            throw new RoomException("Ya eres miembro de '" + r.name + "'");
        }
        u.rooms.add(r.name);
        Log.info(u.nick + " se unió a #" + r.name + " (" + r.members.size() + " miembros)");
        notifyMembers(r, u, false, cb -> cb.roomMembershipAsync(r.name, u.nick, true));
    }

    void leave(ConnectedUser u, String name) throws RoomException {
        Room r = requireMember(u, name);
        removeMember(r, u);
    }

    private void removeMember(Room r, ConnectedUser u) {
        if (r.members.remove(u.key)) {
            u.rooms.remove(r.name);
            Log.info(u.nick + " salió de #" + r.name);
            notifyMembers(r, u, false, cb -> cb.roomMembershipAsync(r.name, u.nick, false));
        }
    }

    String[] members(String name) throws RoomException {
        Room r = require(name);
        List<String> out = new ArrayList<>();
        for (String key : r.members) {
            ConnectedUser m = users.get(key);
            if (m != null) {
                out.add(m.nick);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.toArray(new String[0]);
    }

    /** Difusión (broadcast) a los miembros, sin eco al emisor. */
    int broadcast(ConnectedUser sender, String roomName, String text) throws RoomException {
        Room r = requireMember(sender, roomName);
        ChatMessage msg = new ChatMessage(sender.nick, r.name, r.name, text, System.currentTimeMillis());
        return notifyMembers(r, sender, false, cb -> cb.roomMessageAsync(msg));
    }

    /** Envía a cada miembro de la sala (excepto 'except' si includeSelf=false). */
    int notifyMembers(Room r, ConnectedUser except, boolean includeSelf,
                      java.util.function.Function<chat.ClientCallbackPrx,
                              java.util.concurrent.CompletableFuture<Void>> call) {
        int count = 0;
        for (String key : r.members) {
            ConnectedUser m = users.get(key);
            if (m == null || (!includeSelf && m == except)) {
                continue;
            }
            users.notify(m, call);
            count++;
        }
        return count;
    }

    @Override
    public void userLeft(ConnectedUser u) {
        for (Room r : all()) {
            removeMember(r, u);
        }
    }
}
