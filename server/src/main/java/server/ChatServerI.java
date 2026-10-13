package server;

import chat.ChatServer;
import chat.ClientCallbackPrx;
import chat.InvalidNicknameException;
import chat.NicknameInUseException;
import chat.SessionPrx;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.IPConnectionInfo;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.LocalException;
import common.Protocol;

import java.util.UUID;

/**
 * Punto de entrada público (identidad "ChatServer"). Implementa RF-01:
 * nickname único, registro del callback y monitor de presencia.
 */
final class ChatServerI implements ChatServer {

    private static final String NICK_PATTERN = "[A-Za-z0-9_\\-]{2,20}";
    /** Tiempo máximo que el servidor espera a un callback antes de rendirse. */
    private static final int CALLBACK_TIMEOUT_MS = 15_000;

    private final ServerContext ctx;

    ChatServerI(ServerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public SessionPrx login(String nick, ClientCallbackPrx callback, Current current)
            throws NicknameInUseException, InvalidNicknameException {
        String clean = nick == null ? "" : nick.trim();
        if (!clean.matches(NICK_PATTERN)) {
            throw new InvalidNicknameException("Nickname inválido: use 2-20 letras, números, '_' o '-'");
        }
        if (callback == null) {
            throw new InvalidNicknameException("Debe registrar un proxy de callback");
        }
        if (ctx.users.byConnection(current.con) != null) {
            throw new InvalidNicknameException("Esta conexión ya tiene una sesión activa; haga logout primero");
        }

        // Conexión bidireccional: el servidor invoca los callbacks por la MISMA
        // conexión TCP que abrió el cliente (atraviesa firewalls/NAT del cliente).
        ClientCallbackPrx cb = callback;
        if (current.con != null) {
            cb = ClientCallbackPrx.uncheckedCast(callback.ice_fixed(current.con));
        }
        cb = ClientCallbackPrx.uncheckedCast(cb.ice_invocationTimeout(CALLBACK_TIMEOUT_MS));

        Identity sessionId = new Identity(UUID.randomUUID().toString(), "session");
        ConnectedUser user = new ConnectedUser(clean, cb, sessionId, current.con, remoteHost(current));
        if (!ctx.users.register(user)) {
            throw new NicknameInUseException("El nickname '" + clean + "' ya está en uso por otra sesión activa");
        }

        SessionPrx session = SessionPrx.uncheckedCast(current.adapter.add(new SessionI(user, ctx), sessionId));

        // Tolerancia a fallos: si la conexión TCP se cierra (cliente cerrado,
        // cable desconectado → timeout de ACM) se limpia todo el estado.
        if (current.con != null) {
            current.con.setCloseCallback(con -> ctx.users.drop(user, "conexión cerrada"));
        }

        Log.info("Usuario conectado: " + user + ". Conectados: " + ctx.users.all().size());
        ctx.users.broadcastExcept(user, c -> c.userConnectedAsync(user.nick));
        return session;
    }

    /** IP del cliente según la conexión Ice (la usamos para el audio UDP). */
    static String remoteHost(Current current) {
        if (current.con == null) {
            return "127.0.0.1"; // invocación colocada (mismo proceso)
        }
        try {
            ConnectionInfo info = current.con.getInfo();
            while (info != null && !(info instanceof IPConnectionInfo)) {
                info = info.underlying;
            }
            if (info != null) {
                return Protocol.normalizeHost(((IPConnectionInfo) info).remoteAddress);
            }
        } catch (LocalException ignored) {
            // conexión ya cerrada
        }
        return "127.0.0.1";
    }
}
