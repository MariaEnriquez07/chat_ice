package client;

import chat.ChatServerPrx;
import chat.ClientCallbackPrx;
import chat.InvalidNicknameException;
import chat.NicknameInUseException;
import chat.SessionPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.IPConnectionInfo;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;
import common.Protocol;

import java.util.UUID;

/**
 * Conexión del cliente con el servidor Ice.
 *
 * <h3>Callbacks bidireccionales</h3>
 * <ol>
 *   <li>Se crea un adaptador de objetos SIN endpoints ({@code createObjectAdapter("")}):
 *       el cliente no abre ningún puerto TCP propio.</li>
 *   <li>Se registra el servant {@link CallbackI} y se obtiene su proxy.</li>
 *   <li>Se asocia el adaptador a la conexión saliente hacia el servidor
 *       ({@code connection.setAdapter}). Así el servidor puede invocar los
 *       callbacks por esa misma conexión TCP.</li>
 *   <li>Se envía el proxy en {@code login}; el servidor lo "fija" a la
 *       conexión con {@code ice_fixed}.</li>
 * </ol>
 */
final class ChatClient {

    private final Communicator communicator;
    private final ChatServerPrx server;
    private final ObjectAdapter adapter;
    private final ClientCallbackPrx callbackPrx;
    private final CallbackI callback;

    private volatile SessionPrx session;
    private volatile String nick;
    private volatile Runnable onConnectionLost;

    ChatClient(Communicator communicator, String host, int port, CallbackI callback) {
        this.communicator = communicator;
        this.callback = callback;
        String proxy = Protocol.SERVER_IDENTITY + ":tcp -h " + host + " -p " + port;
        this.server = ChatServerPrx.checkedCast(communicator.stringToProxy(proxy));
        if (server == null) {
            throw new IllegalStateException("El objeto en " + host + ":" + port + " no es un ChatServer");
        }
        this.adapter = communicator.createObjectAdapter("");
        Identity id = new Identity(UUID.randomUUID().toString(), "callback");
        this.callbackPrx = ClientCallbackPrx.uncheckedCast(adapter.add(callback, id));
        adapter.activate();
        bindConnection();
    }

    /** Asocia el adaptador de callbacks a la conexión actual y vigila su cierre. */
    private void bindConnection() {
        Connection con = server.ice_getConnection();
        con.setAdapter(adapter);
        con.setCloseCallback(c -> {
            if (session != null) {
                session = null;
                nick = null;
                Runnable r = onConnectionLost;
                if (r != null) {
                    r.run();
                }
            }
        });
    }

    void setOnConnectionLost(Runnable r) {
        this.onConnectionLost = r;
    }

    void login(String requestedNick) throws NicknameInUseException, InvalidNicknameException {
        if (session != null) {
            throw new IllegalStateException("Ya iniciaste sesión como " + nick);
        }
        // Por si la conexión se reabrió (p.ej. tras perderla), se reasocia el adaptador.
        bindConnection();
        SessionPrx s = server.login(requestedNick, callbackPrx);
        nick = requestedNick.trim();
        session = s;
        callback.setMyNick(nick);
    }

    void logout() {
        SessionPrx s = session;
        session = null;
        nick = null;
        if (s != null) {
            try {
                s.logout();
            } catch (LocalException ignored) {
                // el servidor ya no está; no hay nada que limpiar
            }
        }
    }

    boolean isLoggedIn() {
        return session != null;
    }

    SessionPrx session() {
        SessionPrx s = session;
        if (s == null) {
            throw new IllegalStateException("Primero inicia sesión con /login <nickname>");
        }
        return s;
    }

    String nick() {
        return nick;
    }

    /** IP local con la que salimos hacia el servidor: es la que publicamos para UDP. */
    String localIp() {
        IPConnectionInfo info = ipInfo();
        return info == null ? "127.0.0.1" : Protocol.normalizeHost(info.localAddress);
    }

    /** IP del servidor ya resuelta (para el relay de audio). */
    String serverIp() {
        IPConnectionInfo info = ipInfo();
        return info == null ? "127.0.0.1" : Protocol.normalizeHost(info.remoteAddress);
    }

    private IPConnectionInfo ipInfo() {
        try {
            Connection con = server.ice_getConnection();
            ConnectionInfo info = con.getInfo();
            while (info != null && !(info instanceof IPConnectionInfo)) {
                info = info.underlying;
            }
            return (IPConnectionInfo) info;
        } catch (LocalException e) {
            return null;
        }
    }

    Communicator communicator() {
        return communicator;
    }
}
