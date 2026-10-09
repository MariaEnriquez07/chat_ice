package server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;
import common.Protocol;

import java.io.File;
import java.net.SocketException;

/**
 * Arranque del servidor.
 *
 * <pre>
 *   ./gradlew runServer                    (puertos por defecto 10000 TCP / 10001 UDP)
 *   ./gradlew runServer -Pport=12000 -PaudioPort=12001
 * </pre>
 * La configuración de Ice se lee de {@code config.server} y puede
 * sobreescribirse con argumentos {@code --Ice.Xxx=valor}.
 */
public final class ServerMain {

    private ServerMain() {}

    public static void main(String[] args) {
        Properties props = Util.createProperties();
        if (new File("config.server").isFile()) {
            props.load("config.server");
        }
        String[] rest = props.parseIceCommandLineOptions(args);
        rest = props.parseCommandLineOptions("Chat", rest);

        int port = props.getPropertyAsIntWithDefault("Chat.Port", Protocol.DEFAULT_ICE_PORT);
        int audioPort = props.getPropertyAsIntWithDefault("Chat.AudioPort", Protocol.DEFAULT_AUDIO_RELAY_PORT);
        setDefault(props, "ChatAdapter.Endpoints", "tcp -p " + port);
        setDefault(props, "Ice.IPv6", "0");
        setDefault(props, "Ice.MessageSizeMax", "2048");
        setDefault(props, "Ice.ThreadPool.Server.Size", "4");
        setDefault(props, "Ice.ThreadPool.Server.SizeMax", "32");
        setDefault(props, "Ice.ACM.Server.Timeout", "30");
        setDefault(props, "Ice.ACM.Server.Close", "4");

        InitializationData init = new InitializationData();
        init.properties = props;

        try (Communicator ic = Util.initialize(init)) {
            Runtime.getRuntime().addShutdownHook(new Thread(ic::shutdown));

            ObjectAdapter adapter;
            try {
                adapter = ic.createObjectAdapter("ChatAdapter");
            } catch (com.zeroc.Ice.SocketException e) {
                System.err.println("No se pudo abrir el puerto TCP " + port
                        + " (¿ya hay un servidor corriendo?): " + e.getCause());
                return;
            }
            UserRegistry users = new UserRegistry(adapter);
            RoomRegistry rooms = new RoomRegistry(users);
            FileRelay files = new FileRelay(users, rooms);
            CallManager calls = new CallManager(users, rooms, audioPort);
            // Orden de limpieza al desconectarse: llamadas → archivos → salas
            users.addListener(calls);
            users.addListener(files);
            users.addListener(rooms);

            AudioRelay relay;
            try {
                relay = new AudioRelay(audioPort, calls);
            } catch (SocketException e) {
                System.err.println("No se pudo abrir el puerto UDP " + audioPort + ": " + e.getMessage());
                return;
            }

            ServerContext ctx = new ServerContext(users, rooms, files, calls);
            adapter.add(new ChatServerI(ctx), Util.stringToIdentity(Protocol.SERVER_IDENTITY));
            adapter.activate();
            relay.start();

            Log.info("Servidor de chat listo. Ice: " + props.getProperty("ChatAdapter.Endpoints")
                    + " | UDP relay: " + audioPort + " | Ctrl+C para detener");
            ic.waitForShutdown();

            relay.close();
            calls.shutdown();
            Log.info("Servidor detenido");
        }
    }

    private static void setDefault(Properties props, String key, String value) {
        if (props.getProperty(key).isEmpty()) {
            props.setProperty(key, value);
        }
    }
}
