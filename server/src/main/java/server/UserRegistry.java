package server;

import chat.ClientCallbackPrx;
import com.zeroc.Ice.CommunicatorDestroyedException;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.InvocationTimeoutException;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.NotRegisteredException;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.UnknownException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Registro thread-safe de usuarios conectados (RF-01).
 *
 * <ul>
 *   <li>{@link ConcurrentHashMap#putIfAbsent} garantiza nicknames únicos sin
 *       condiciones de carrera aunque dos clientes hagan login a la vez.</li>
 *   <li>{@link #drop} usa {@code remove(key, value)} para que la limpieza se
 *       ejecute una sola vez aunque coincidan logout, cierre de conexión y un
 *       callback fallido.</li>
 *   <li>Todas las notificaciones a clientes son asíncronas ({@code xxxAsync}):
 *       un cliente lento nunca bloquea los hilos del servidor.</li>
 * </ul>
 */
final class UserRegistry {

    /** Módulos que deben limpiar estado cuando un usuario se va. */
    interface Listener {
        void userLeft(ConnectedUser user);
    }

    private final ConcurrentHashMap<String, ConnectedUser> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Connection, ConnectedUser> byConnection = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ObjectAdapter adapter;

    UserRegistry(ObjectAdapter adapter) {
        this.adapter = adapter;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    /** @return false si el nickname ya está en uso. */
    boolean register(ConnectedUser u) {
        if (users.putIfAbsent(u.key, u) != null) {
            return false;
        }
        if (u.connection != null) {
            byConnection.put(u.connection, u);
        }
        return true;
    }

    ConnectedUser get(String nick) {
        return users.get(ConnectedUser.keyOf(nick));
    }

    ConnectedUser byConnection(Connection con) {
        return con == null ? null : byConnection.get(con);
    }

    boolean isActive(ConnectedUser u) {
        return users.get(u.key) == u;
    }

    List<ConnectedUser> all() {
        return new ArrayList<>(users.values());
    }

    String[] nicks() {
        return users.values().stream().map(u -> u.nick).sorted(String.CASE_INSENSITIVE_ORDER)
                .toArray(String[]::new);
    }

    /**
     * Elimina al usuario y libera todos sus recursos. Idempotente.
     * Se invoca desde: logout, cierre de conexión (CloseCallback/ACM) o un
     * callback que falló porque el cliente desapareció.
     */
    void drop(ConnectedUser u, String reason) {
        if (!users.remove(u.key, u)) {
            return; // ya fue limpiado por otro hilo
        }
        if (u.connection != null) {
            byConnection.remove(u.connection, u);
        }
        for (Listener l : listeners) {
            try {
                l.userLeft(u);
            } catch (RuntimeException e) {
                Log.warn("Error limpiando estado de " + u.nick + ": " + e);
            }
        }
        try {
            adapter.remove(u.sessionId);
        } catch (NotRegisteredException | CommunicatorDestroyedException ignored) {
            // ya no estaba registrado o el servidor se está apagando
        } catch (LocalException e) {
            Log.warn("No se pudo remover la sesión de " + u.nick + ": " + e);
        }
        Log.info("Usuario desconectado: " + u + " (" + reason + "). Conectados: " + users.size());
        for (ConnectedUser other : all()) {
            notify(other, cb -> cb.userDisconnectedAsync(u.nick));
        }
    }

    // ------------------------------------------------------------ notificación
    /**
     * Invoca un callback de forma asíncrona. Si falla porque la conexión del
     * cliente murió, el usuario se elimina (detección de caídas abruptas).
     */
    CompletableFuture<Void> notify(ConnectedUser target,
                                   Function<ClientCallbackPrx, CompletableFuture<Void>> call) {
        CompletableFuture<Void> f;
        try {
            f = call.apply(target.callback);
        } catch (LocalException e) {
            f = new CompletableFuture<>();
            f.completeExceptionally(e);
        }
        f.whenComplete((ok, ex) -> {
            if (ex != null) {
                handleCallbackFailure(target, ex);
            }
        });
        return f;
    }

    void broadcastExcept(ConnectedUser except, Function<ClientCallbackPrx, CompletableFuture<Void>> call) {
        for (ConnectedUser u : all()) {
            if (u != except) {
                notify(u, call);
            }
        }
    }

    private void handleCallbackFailure(ConnectedUser target, Throwable ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        if (cause instanceof CommunicatorDestroyedException) {
            return; // apagado del servidor
        }
        if (cause instanceof InvocationTimeoutException || cause instanceof UnknownException) {
            // cliente lento o error dentro del código del cliente: no lo expulsamos
            Log.warn("Callback a " + target.nick + " falló: " + cause.getClass().getSimpleName());
            return;
        }
        if (cause instanceof LocalException && isActive(target)) {
            drop(target, "callback inalcanzable: " + cause.getClass().getSimpleName());
        }
    }
}
