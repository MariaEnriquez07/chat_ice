package client;

import chat.ChatException;
import chat.SessionPrx;
import com.zeroc.Ice.LocalException;
import common.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Envío de archivos por bloques (RF-04).
 *
 * <p>Nunca se manda el archivo completo en una sola invocación: con un video
 * de 80 MB Ice lanzaría {@code MemoryLimitException} porque supera
 * {@code Ice.MessageSizeMax}. Se envía en bloques de 64 KB, uno tras otro,
 * con su índice; al final se envía el SHA-256 para verificar integridad.
 *
 * <p>El envío corre en un hilo aparte para no congelar la consola.
 */
final class FileSender {

    private final ChatClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-sender");
        t.setDaemon(true);
        return t;
    });

    FileSender(ChatClient client) {
        this.client = client;
    }

    /** @param target nickname o "#sala" */
    void send(String target, Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            Console.error("No se puede leer el archivo: " + path.toAbsolutePath());
            return;
        }
        SessionPrx session = client.session();
        boolean toRoom = target.startsWith("#");
        String dest = toRoom ? target.substring(1) : target;
        worker.submit(() -> transfer(session, dest, toRoom, path));
        Console.info("Enviando '" + path.getFileName() + "' a " + (toRoom ? "#" : "") + dest + " en segundo plano...");
    }

    private void transfer(SessionPrx session, String dest, boolean toRoom, Path path) {
        String transferId = null;
        String label = path.getFileName().toString();
        try {
            long size = Files.size(path);
            int chunkSize = Protocol.CHUNK_SIZE;
            int totalChunks = (int) ((size + chunkSize - 1) / chunkSize);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long start = System.nanoTime();

            transferId = session.beginFileTransfer(dest, toRoom, label, size, chunkSize, totalChunks);

            byte[] buffer = new byte[chunkSize];
            int lastPercent = -1;
            try (InputStream in = Files.newInputStream(path)) {
                for (int i = 0; i < totalChunks; i++) {
                    int n = readFully(in, buffer);
                    if (n <= 0) {
                        throw new IOException("El archivo cambió de tamaño durante el envío");
                    }
                    byte[] data = n == chunkSize ? buffer.clone() : Arrays.copyOf(buffer, n);
                    sha.update(data);
                    session.sendFileChunk(transferId, i, data);

                    int percent = (int) ((i + 1) * 100L / totalChunks);
                    if (totalChunks > 4 && percent / 25 != lastPercent / 25 && percent < 100) {
                        lastPercent = percent;
                        Console.event(Console.c(Console.DIM, "  >> " + label + ": " + percent + "%"));
                    }
                }
            }
            session.endFileTransfer(transferId, hex(sha.digest()));
            double secs = (System.nanoTime() - start) / 1e9;
            Console.event(Console.c(Console.GREEN, "[ok] Archivo '" + label + "' enviado ("
                    + human(size) + ", " + totalChunks + " bloques, " + String.format("%.1f s", secs) + ")"));
        } catch (ChatException e) {
            Console.event(Console.c(Console.RED, "[error] Envío de '" + label + "': " + e.reason));
            cancel(session, transferId);
        } catch (IOException | NoSuchAlgorithmException e) {
            Console.event(Console.c(Console.RED, "[error] Leyendo '" + label + "': " + e.getMessage()));
            cancel(session, transferId);
        } catch (LocalException e) {
            Console.event(Console.c(Console.RED, "[error] Conexión durante el envío: " + e));
        }
    }

    private static void cancel(SessionPrx session, String transferId) {
        if (transferId == null) {
            return;
        }
        try {
            session.cancelFileTransfer(transferId);
        } catch (LocalException ignored) {
            // si no hay conexión, el servidor la cancelará al detectar la caída
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    void shutdown() {
        worker.shutdownNow();
    }
}
