package server;

import chat.ClientCallbackPrx;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Identity;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Estado en el servidor de un usuario conectado. */
final class ConnectedUser {

    final String nick;
    /** Clave para el mapa (insensible a mayúsculas: "Ana" y "ana" chocan). */
    final String key;
    /** Proxy de callback fijado a la conexión bidireccional del cliente. */
    final ClientCallbackPrx callback;
    /** Identidad del servant Session de este usuario. */
    final Identity sessionId;
    final Connection connection;
    /** IP real del cliente vista por el servidor (para UDP). */
    final String remoteHost;
    /** Salas a las que pertenece (conjunto concurrente). */
    final Set<String> rooms = ConcurrentHashMap.newKeySet();
    final long connectedAt = System.currentTimeMillis();

    ConnectedUser(String nick, ClientCallbackPrx callback, Identity sessionId,
                  Connection connection, String remoteHost) {
        this.nick = nick;
        this.key = keyOf(nick);
        this.callback = callback;
        this.sessionId = sessionId;
        this.connection = connection;
        this.remoteHost = remoteHost;
    }

    static String keyOf(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return nick + "@" + remoteHost;
    }
}
