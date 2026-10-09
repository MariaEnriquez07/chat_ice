package client;

import chat.FileMeta;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconstrucción de archivos recibidos por bloques (RF-04).
 *
 * <p>Cada bloque se escribe en su posición exacta ({@code índice × tamañoBloque})
 * de un archivo temporal {@code .part}. Al terminar se verifica tamaño y
 * SHA-256; solo si coinciden se renombra al nombre definitivo. Así un archivo
 * truncado o corrupto nunca queda con el nombre final.
 */
final class FileReceiver {

    private static final class Incoming {
        final FileMeta meta;
        final Path temp;
        final RandomAccessFile raf;
        final BitSet received = new BitSet();
        int count;
        long bytes;
        int lastQuarter;
        String expectedSha;
        boolean finished;

        Incoming(FileMeta meta, Path temp, RandomAccessFile raf) {
            this.meta = meta;
            this.temp = temp;
            this.raf = raf;
        }
    }

    private final ConcurrentHashMap<String, Incoming> incoming = new ConcurrentHashMap<>();
    private final Path baseDir;
    private volatile String nick = "anon";

    FileReceiver(Path baseDir) {
        this.baseDir = baseDir;
    }

    void setNick(String nick) {
        this.nick = nick;
    }

    private Path dir() throws IOException {
        Path d = baseDir.resolve(nick);
        Files.createDirectories(d);
        return d;
    }

    void onStarted(FileMeta meta) {
        try {
            Path temp = dir().resolve("." + meta.transferId + ".part");
            RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw");
            raf.setLength(meta.totalSize);
            incoming.put(meta.transferId, new Incoming(meta, temp, raf));
            String from = meta.room.isEmpty() ? meta.sender : meta.sender + " en #" + meta.room;
            Console.event(Console.c(Console.YELLOW, "[archivo] Recibiendo '" + meta.fileName + "' ("
                    + FileSender.human(meta.totalSize) + ") de " + from + "..."));
        } catch (IOException e) {
            Console.event(Console.c(Console.RED, "[error] No se pudo preparar '" + meta.fileName + "': " + e.getMessage()));
        }
    }

    void onChunk(String transferId, int index, byte[] data) {
        Incoming in = incoming.get(transferId);
        if (in == null) {
            return;
        }
        synchronized (in) {
            try {
                if (index < 0 || index >= in.meta.totalChunks || data.length > in.meta.chunkSize) {
                    fail(in, "bloque " + index + " inválido");
                    return;
                }
                in.raf.seek((long) index * in.meta.chunkSize);
                in.raf.write(data);
                if (!in.received.get(index)) {
                    in.received.set(index);
                    in.count++;
                    in.bytes += data.length;
                }
                int quarter = in.count * 4 / in.meta.totalChunks;
                if (in.meta.totalChunks > 4 && quarter > in.lastQuarter && quarter < 4) {
                    in.lastQuarter = quarter;
                    Console.event(Console.c(Console.DIM, "  << " + in.meta.fileName + ": " + quarter * 25 + "%"));
                }
                tryComplete(in);
            } catch (IOException e) {
                fail(in, e.getMessage());
            }
        }
    }

    void onFinished(String transferId, String sha256) {
        Incoming in = incoming.get(transferId);
        if (in == null) {
            return;
        }
        synchronized (in) {
            in.finished = true;
            in.expectedSha = sha256;
            tryComplete(in);
        }
    }

    void onAborted(String transferId, String reason) {
        Incoming in = incoming.remove(transferId);
        if (in == null) {
            return;
        }
        synchronized (in) {
            closeAndDelete(in);
        }
        Console.event(Console.c(Console.RED, "[archivo] Transferencia de '" + in.meta.fileName
                + "' cancelada: " + reason));
    }

    /** Debe llamarse con el candado de 'in'. */
    private void tryComplete(Incoming in) {
        if (!in.finished || in.count != in.meta.totalChunks) {
            return; // faltan bloques o aún no llega el fin
        }
        incoming.remove(in.meta.transferId);
        try {
            in.raf.close();
            if (Files.size(in.temp) != in.meta.totalSize || in.bytes != in.meta.totalSize) {
                throw new IOException("tamaño incorrecto (truncado)");
            }
            String actual = sha256(in.temp);
            if (in.expectedSha != null && !in.expectedSha.isEmpty() && !actual.equalsIgnoreCase(in.expectedSha)) {
                throw new IOException("SHA-256 no coincide: archivo corrupto");
            }
            Path target = uniqueName(in.temp.getParent(), in.meta.fileName);
            Files.move(in.temp, target, StandardCopyOption.REPLACE_EXISTING);
            Console.event(Console.c(Console.GREEN, "[archivo] '" + in.meta.fileName + "' recibido de "
                    + in.meta.sender + " -> " + target.toAbsolutePath() + " (SHA-256 verificado)"));
        } catch (IOException e) {
            closeAndDelete(in);
            Console.event(Console.c(Console.RED, "[error] Archivo '" + in.meta.fileName + "' descartado: "
                    + e.getMessage()));
        }
    }

    private void fail(Incoming in, String reason) {
        incoming.remove(in.meta.transferId);
        closeAndDelete(in);
        Console.event(Console.c(Console.RED, "[error] Recepción de '" + in.meta.fileName + "' falló: " + reason));
    }

    private static void closeAndDelete(Incoming in) {
        try {
            in.raf.close();
        } catch (IOException ignored) {
            // ya cerrado
        }
        try {
            Files.deleteIfExists(in.temp);
        } catch (IOException ignored) {
            // se deja el .part
        }
    }

    /** "foto.png" → "foto (1).png" si ya existe. El nombre ya viene saneado del servidor. */
    private static Path uniqueName(Path dir, String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path p = dir.resolve(safe);
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        for (int i = 1; Files.exists(p); i++) {
            p = dir.resolve(base + " (" + i + ")" + ext);
        }
        return p;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return FileSender.hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Al cerrar sesión se descartan las recepciones a medias. */
    void abortAll() {
        for (String id : incoming.keySet()) {
            Incoming in = incoming.remove(id);
            if (in != null) {
                synchronized (in) {
                    closeAndDelete(in);
                }
            }
        }
    }
}
