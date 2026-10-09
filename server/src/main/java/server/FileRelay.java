package server;

import chat.ChatException;
import chat.FileMeta;
import chat.FileTransferException;
import chat.UserNotFoundException;
import common.Protocol;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Transferencia de archivos por bloques sobre Ice (RF-04).
 *
 * <p>Protocolo: begin (metadatos) → N × chunk (sequence&lt;byte&gt;) → end (SHA-256).
 * El servidor no guarda el archivo: reenvía cada bloque a los destinatarios.
 *
 * <p>Control de flujo: {@link #chunk} espera (con tope de tiempo) a que los
 * destinatarios confirmen el bloque antes de responder al emisor. Así un
 * archivo grande nunca se acumula entero en la memoria del servidor.
 */
final class FileRelay implements UserRegistry.Listener {

    private static final long FORWARD_TIMEOUT_MS = 15_000;

    private static final class Transfer {
        final FileMeta meta;
        final ConnectedUser sender;
        final List<ConnectedUser> recipients;
        int nextIndex;
        long received;

        Transfer(FileMeta meta, ConnectedUser sender, List<ConnectedUser> recipients) {
            this.meta = meta;
            this.sender = sender;
            this.recipients = new CopyOnWriteArrayList<>(recipients);
        }
    }

    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final UserRegistry users;
    private final RoomRegistry rooms;

    FileRelay(UserRegistry users, RoomRegistry rooms) {
        this.users = users;
        this.rooms = rooms;
    }

    String begin(ConnectedUser sender, String target, boolean toRoom, String fileName,
                 long totalSize, int chunkSize, int totalChunks) throws ChatException {
        String name = sanitize(fileName);
        if (totalSize < 0 || totalSize > Protocol.MAX_FILE_SIZE) {
            throw new FileTransferException("Tamaño de archivo no permitido (máx. 1 GB)");
        }
        if (chunkSize <= 0 || chunkSize > Protocol.MAX_CHUNK_SIZE) {
            throw new FileTransferException("Tamaño de bloque inválido: " + chunkSize);
        }
        long expectedChunks = (totalSize + chunkSize - 1) / chunkSize;
        if (expectedChunks != totalChunks) {
            throw new FileTransferException("totalChunks no coincide con tamaño/bloque (esperado "
                    + expectedChunks + ")");
        }

        List<ConnectedUser> recipients = new ArrayList<>();
        String roomName = "";
        if (toRoom) {
            RoomRegistry.Room r = rooms.requireMember(sender, target);
            roomName = r.name;
            for (String key : r.members) {
                ConnectedUser m = users.get(key);
                if (m != null && m != sender) {
                    recipients.add(m);
                }
            }
            if (recipients.isEmpty()) {
                throw new FileTransferException("No hay otros miembros conectados en #" + r.name);
            }
        } else {
            ConnectedUser dest = users.get(target);
            if (dest == null) {
                throw new UserNotFoundException("El usuario '" + target + "' no está conectado");
            }
            if (dest == sender) {
                throw new FileTransferException("No puedes enviarte archivos a ti mismo");
            }
            recipients.add(dest);
        }

        String id = UUID.randomUUID().toString();
        FileMeta meta = new FileMeta(id, name, totalSize, chunkSize, totalChunks, sender.nick, roomName);
        Transfer t = new Transfer(meta, sender, recipients);
        transfers.put(id, t);
        for (ConnectedUser r : recipients) {
            users.notify(r, cb -> cb.fileStartedAsync(meta));
        }
        Log.info("Archivo '" + name + "' (" + totalSize + " B, " + totalChunks + " bloques) de "
                + sender.nick + " → " + (toRoom ? "#" + roomName : target));
        return id;
    }

    void chunk(ConnectedUser sender, String id, int index, byte[] data) throws FileTransferException {
        Transfer t = owned(sender, id);
        int len = data == null ? 0 : data.length;
        synchronized (t) {
            if (index != t.nextIndex) {
                throw new FileTransferException("Bloque fuera de orden: llegó " + index
                        + ", se esperaba " + t.nextIndex);
            }
            if (index >= t.meta.totalChunks || len == 0 || len > t.meta.chunkSize
                    || t.received + len > t.meta.totalSize) {
                throw new FileTransferException("Bloque " + index + " con tamaño inválido (" + len + " B)");
            }
            t.nextIndex++;
            t.received += len;
        }

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (ConnectedUser r : t.recipients) {
            pending.add(users.notify(r, cb -> cb.fileChunkAsync(id, index, data))
                    .exceptionally(ex -> {
                        t.recipients.remove(r); // destinatario caído: se excluye
                        return null;
                    }));
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                    .get(FORWARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.warn("Reenvío lento del bloque " + index + " de " + t.meta.fileName + ": " + e);
        }
        if (t.recipients.isEmpty()) {
            transfers.remove(id);
            throw new FileTransferException("Ningún destinatario sigue conectado; transferencia cancelada");
        }
    }

    void end(ConnectedUser sender, String id, String sha256) throws FileTransferException {
        Transfer t = owned(sender, id);
        synchronized (t) {
            if (t.nextIndex != t.meta.totalChunks || t.received != t.meta.totalSize) {
                throw new FileTransferException("Transferencia incompleta: " + t.nextIndex + "/"
                        + t.meta.totalChunks + " bloques, " + t.received + "/" + t.meta.totalSize + " B");
            }
        }
        transfers.remove(id);
        for (ConnectedUser r : t.recipients) {
            users.notify(r, cb -> cb.fileFinishedAsync(id, sha256 == null ? "" : sha256));
        }
        Log.info("Archivo '" + t.meta.fileName + "' entregado a " + t.recipients.size() + " destinatario(s)");
    }

    void cancel(ConnectedUser sender, String id, String reason) {
        Transfer t = transfers.get(id);
        if (t != null && t.sender == sender && transfers.remove(id, t)) {
            abort(t, reason);
        }
    }

    private void abort(Transfer t, String reason) {
        for (ConnectedUser r : t.recipients) {
            users.notify(r, cb -> cb.fileAbortedAsync(t.meta.transferId, reason));
        }
        Log.info("Transferencia de '" + t.meta.fileName + "' cancelada: " + reason);
    }

    private Transfer owned(ConnectedUser sender, String id) throws FileTransferException {
        Transfer t = transfers.get(id);
        if (t == null || t.sender != sender) {
            throw new FileTransferException("Transferencia desconocida: " + id);
        }
        return t;
    }

    /** Evita rutas maliciosas ("../../etc/passwd") quedándose solo con el nombre. */
    static String sanitize(String fileName) throws FileTransferException {
        if (fileName == null || fileName.isBlank()) {
            throw new FileTransferException("Nombre de archivo vacío");
        }
        String base;
        try {
            base = Paths.get(fileName.replace('\\', '/')).getFileName().toString();
        } catch (RuntimeException e) {
            base = fileName;
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (base.startsWith(".")) {
            base = base.substring(1);
        }
        if (base.isBlank()) {
            throw new FileTransferException("Nombre de archivo inválido");
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    @Override
    public void userLeft(ConnectedUser u) {
        for (Transfer t : new ArrayList<>(transfers.values())) {
            if (t.sender == u) {
                if (transfers.remove(t.meta.transferId, t)) {
                    abort(t, u.nick + " se desconectó");
                }
            } else {
                t.recipients.remove(u);
            }
        }
    }
}
