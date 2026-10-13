package client;

import chat.ChatException;
import chat.RoomInfo;
import com.zeroc.Ice.LocalException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Interfaz de línea de comandos (opción A).
 *
 * <p>El hilo principal solo LEE comandos; los mensajes que llegan del servidor
 * los imprime el hilo de Ice a través de {@link Console}, de modo que la
 * lectura y la impresión están desacopladas.
 */
final class Cli {

    private static final String HELP = String.join("\n",
            "",
            "  SESIÓN",
            "    /login <nick>              Iniciar sesión",
            "    /logout                    Cerrar sesión (sin salir del programa)",
            "    /users                     Usuarios conectados",
            "    /quit                      Salir",
            "  MENSAJES",
            "    /msg <nick> <texto>        Mensaje privado",
            "    /create <sala>             Crear sala (y unirse)",
            "    /rooms                     Listar salas",
            "    /join <sala>               Unirse a una sala (queda como sala activa)",
            "    /leave <sala>              Abandonar sala",
            "    /members <sala>            Miembros de una sala",
            "    /say <sala> <texto>        Mensaje a una sala",
            "    /use <sala>                Cambiar la sala activa",
            "    <texto sin />              Se envía a la sala activa",
            "  ARCHIVOS",
            "    /sendfile <nick> <ruta>    Enviar archivo a un usuario",
            "    /sendfile #<sala> <ruta>   Enviar archivo a una sala",
            "  LLAMADAS (voz por UDP)",
            "    /call <nick>               Llamada 1 a 1",
            "    /accept   /reject          Contestar o rechazar",
            "    /gcall <sala>              Iniciar o unirse a la llamada grupal",
            "    /mute                      Silenciar / reactivar micrófono",
            "    /hangup                    Colgar o salir de la llamada grupal",
            "    /status                    Estado actual",
            "");

    private final ChatClient client;
    private final FileSender sender;
    private final FileReceiver receiver;
    private final CallController calls;
    private volatile String activeRoom;

    Cli(ChatClient client, FileSender sender, FileReceiver receiver, CallController calls) {
        this.client = client;
        this.sender = sender;
        this.receiver = receiver;
        this.calls = calls;
    }

    void run(String initialNick) throws IOException {
        Console.println(Console.c(Console.BOLD, "=== Chat Ice + Voz UDP ===") + "  (/help para ver comandos)");
        if (initialNick != null && !initialNick.isBlank()) {
            login(initialNick);
        } else {
            Console.info("Inicia sesión con /login <nickname>");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            Console.printPrompt();
            String line = in.readLine();
            if (line == null) {
                break; // EOF (Ctrl+D / Ctrl+Z)
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (!handle(line)) {
                    break;
                }
            } catch (IllegalStateException e) {
                Console.error(e.getMessage());
            } catch (ChatException e) {
                Console.error(e.reason);
            } catch (LocalException e) {
                Console.error("Error de comunicación con el servidor: " + e.getClass().getSimpleName());
            }
        }
    }

    /** @return false para terminar el programa */
    private boolean handle(String line) throws ChatException {
        if (!line.startsWith("/")) {
            if (activeRoom == null) {
                Console.error("No hay sala activa. Usa /join <sala> o /msg <nick> <texto>");
            } else {
                say(activeRoom, line);
            }
            return true;
        }
        String[] p = line.split("\\s+", 3);
        String cmd = p[0].toLowerCase();
        String a1 = p.length > 1 ? p[1] : null;
        String rest = p.length > 2 ? p[2] : null;

        switch (cmd) {
            case "/help":
            case "/?":
                Console.println(HELP);
                break;
            case "/login":
                need(a1, "/login <nick>");
                login(a1);
                break;
            case "/logout":
                logout();
                break;
            case "/quit":
            case "/exit":
                return false;
            case "/users":
                Console.info("Conectados: " + String.join(", ", client.session().listUsers()));
                break;
            case "/msg":
                need(rest, "/msg <nick> <texto>");
                long ts = client.session().sendPrivateMessage(a1, rest);
                Console.println(Console.c(Console.DIM, "  -> " + a1 + ": " + rest + "  [entregado al servidor "
                        + java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                        .toLocalTime().withNano(0) + "]"));
                break;
            case "/create":
                need(a1, "/create <sala>");
                client.session().createRoom(room(a1));
                activeRoom = room(a1);
                Console.ok("Sala #" + activeRoom + " creada; eres miembro y es tu sala activa");
                break;
            case "/rooms":
                listRooms();
                break;
            case "/join":
                need(a1, "/join <sala>");
                client.session().joinRoom(room(a1));
                activeRoom = room(a1);
                Console.ok("Te uniste a #" + activeRoom + " (sala activa). Escribe sin '/' para hablar");
                break;
            case "/leave":
                need(a1, "/leave <sala>");
                client.session().leaveRoom(room(a1));
                if (room(a1).equalsIgnoreCase(activeRoom)) {
                    activeRoom = null;
                }
                Console.ok("Saliste de #" + room(a1));
                break;
            case "/members":
                need(a1, "/members <sala>");
                Console.info("#" + room(a1) + ": " + String.join(", ", client.session().listRoomMembers(room(a1))));
                break;
            case "/say":
            case "/r":
                need(rest, "/say <sala> <texto>");
                say(room(a1), rest);
                break;
            case "/use":
                need(a1, "/use <sala>");
                activeRoom = room(a1);
                Console.info("Sala activa: #" + activeRoom);
                break;
            case "/sendfile":
                need(rest, "/sendfile <nick|#sala> <ruta>");
                sendFile(a1, rest);
                break;
            case "/call":
                need(a1, "/call <nick>");
                calls.call(a1);
                break;
            case "/accept":
                calls.accept();
                break;
            case "/reject":
                calls.reject();
                break;
            case "/hangup":
                calls.hangup();
                break;
            case "/gcall":
                String r = a1 != null ? room(a1) : activeRoom;
                need(r, "/gcall <sala>");
                calls.joinConference(r);
                break;
            case "/mute":
                calls.toggleMute();
                break;
            case "/status":
                Console.info((client.isLoggedIn() ? "Sesión: " + client.nick() : "Sin sesión")
                        + " | sala activa: " + (activeRoom == null ? "-" : "#" + activeRoom)
                        + " | voz: " + calls.status());
                break;
            default:
                Console.error("Comando desconocido: " + cmd + " (usa /help)");
        }
        return true;
    }

    private void login(String nick) {
        try {
            client.login(nick);
            Console.ok("Sesión iniciada como " + Console.c(Console.BOLD, client.nick())
                    + ". Conectados: " + String.join(", ", client.session().listUsers()));
        } catch (ChatException e) {
            Console.error("Login rechazado: " + e.reason);
        }
    }

    void logout() {
        if (!client.isLoggedIn()) {
            return;
        }
        calls.onDisconnected();
        receiver.abortAll();
        client.logout();
        activeRoom = null;
        Console.info("Sesión cerrada");
    }

    private void say(String roomName, String text) throws ChatException {
        int n = client.session().sendRoomMessage(roomName, text);
        Console.println(Console.c(Console.DIM, "  -> #" + roomName + " (" + n + " destinatario"
                + (n == 1 ? "" : "s") + ")"));
    }

    private void listRooms() {
        RoomInfo[] rooms = client.session().listRooms();
        if (rooms.length == 0) {
            Console.info("No hay salas. Crea una con /create <sala>");
            return;
        }
        StringBuilder sb = new StringBuilder("Salas:");
        for (RoomInfo r : rooms) {
            sb.append(String.format("%n    #%-20s %2d miembro(s)  creador: %s%s", r.name, r.memberCount, r.owner,
                    r.inVoiceCall > 0 ? "  [voz: " + r.inVoiceCall + "]" : ""));
        }
        Console.info(sb.toString());
    }

    private void sendFile(String target, String rawPath) {
        String pathText = rawPath.trim();
        if (pathText.length() > 1 && pathText.startsWith("\"") && pathText.endsWith("\"")) {
            pathText = pathText.substring(1, pathText.length() - 1);
        }
        Path path = Paths.get(pathText);
        sender.send(target, path);
    }

    private static String room(String name) {
        return name.startsWith("#") ? name.substring(1) : name;
    }

    private static void need(String value, String usage) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Uso: " + usage);
        }
    }
}
