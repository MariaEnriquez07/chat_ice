package client;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;
import common.Protocol;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Arranque del cliente.
 *
 * <pre>
 *   ./gradlew runClient                                   (servidor en localhost)
 *   ./gradlew runClient -Phost=192.168.1.20 -Pnick=ana
 * </pre>
 * Argumentos: --host, --port, --nick, --udp-port (0 = libre), --downloads, --color
 */
public final class ClientMain {

    private ClientMain() {}

    public static void main(String[] args) {
        Properties props = Util.createProperties();
        if (new File("config.client").isFile()) {
            props.load("config.client");
        }
        String[] rest = props.parseIceCommandLineOptions(args);

        String host = "localhost";
        int port = Protocol.DEFAULT_ICE_PORT;
        String nick = null;
        int udpPort = 0;
        String downloads = "downloads";
        boolean color = !System.getProperty("os.name", "").toLowerCase().contains("win");
        for (int i = 0; i < rest.length; i++) {
            String a = rest[i];
            String v = i + 1 < rest.length ? rest[i + 1] : "";
            switch (a) {
                case "--host": host = v; i++; break;
                case "--port": port = Integer.parseInt(v); i++; break;
                case "--nick": nick = v; i++; break;
                case "--udp-port": udpPort = Integer.parseInt(v); i++; break;
                case "--downloads": downloads = v; i++; break;
                case "--color": color = Boolean.parseBoolean(v); i++; break;
                default:
                    if (!a.isBlank()) {
                        System.err.println("Argumento ignorado: " + a);
                    }
            }
        }
        Console.setColor(color);

        setDefault(props, "Ice.IPv6", "0");
        setDefault(props, "Ice.MessageSizeMax", "2048");
        // Heartbeats para que el servidor detecte caídas; nunca cerrar por inactividad
        // (si el cliente cerrara la conexión, el servidor perdería el canal de callbacks).
        setDefault(props, "Ice.ACM.Client.Timeout", "10");
        setDefault(props, "Ice.ACM.Client.Heartbeat", "3");
        setDefault(props, "Ice.ACM.Client.Close", "0");
        setDefault(props, "Ice.Default.InvocationTimeout", "20000");
        setDefault(props, "Ice.Warn.Connections", "0");

        InitializationData init = new InitializationData();
        init.properties = props;

        try (Communicator ic = Util.initialize(init)) {
            CallbackI callback = new CallbackI();
            ChatClient client;
            try {
                client = new ChatClient(ic, host, port, callback);
            } catch (LocalException e) {
                System.err.println("No se pudo conectar al servidor " + host + ":" + port + " -> "
                        + e.getClass().getSimpleName() + ". ¿Está corriendo ./gradlew runServer?");
                return;
            }

            FileReceiver receiver = new FileReceiver(Paths.get(downloads));
            FileSender sender = new FileSender(client);
            CallController calls = new CallController(client, udpPort);
            callback.setFileReceiver(receiver);
            callback.setCallController(calls);
            Cli cli = new Cli(client, sender, receiver, calls);

            client.setOnConnectionLost(() -> {
                calls.onDisconnected();
                receiver.abortAll();
                Console.event(Console.c(Console.RED,
                        "[error] Se perdió la conexión con el servidor. Usa /login <nick> para reconectar."));
            });

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                calls.onDisconnected();
                client.logout();
            }));

            Console.info("Conectado a " + host + ":" + port + " (IP local " + client.localIp() + ")");
            cli.run(nick);

            cli.logout();
            sender.shutdown();
            Console.println("Hasta luego.");
        } catch (IOException e) {
            System.err.println("Error leyendo la consola: " + e.getMessage());
        }
        System.exit(0);
    }

    private static void setDefault(Properties props, String key, String value) {
        if (props.getProperty(key).isEmpty()) {
            props.setProperty(key, value);
        }
    }
}
