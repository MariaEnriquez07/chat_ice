package common;

import java.nio.ByteBuffer;

/**
 * Constantes compartidas por cliente y servidor.
 *
 * <p>Formato de un datagrama de voz (big-endian):
 * <pre>
 *   0      4      8                         8 + FRAME_BYTES
 *   +------+------+--------------------------+
 *   | ssrc | seq  | PCM 16 bits mono 16 kHz  |
 *   +------+------+--------------------------+
 * </pre>
 * Un datagrama de solo cabecera (8 bytes) es un "keep-alive": no lleva audio y
 * sirve para que el relay conozca la dirección real del cliente.
 */
public final class Protocol {

    private Protocol() {}

    // ------------------------------------------------------------- Ice / TCP
    public static final int DEFAULT_ICE_PORT = 10000;
    public static final String SERVER_IDENTITY = "ChatServer";

    // -------------------------------------------------------------- archivos
    /** Tamaño de bloque: 64 KB, muy por debajo de Ice.MessageSizeMax (1 MB por defecto). */
    public static final int CHUNK_SIZE = 64 * 1024;
    /** Máximo bloque aceptado por el servidor (defensa ante clientes mal hechos). */
    public static final int MAX_CHUNK_SIZE = 256 * 1024;
    /** Tamaño máximo de archivo aceptado (1 GB). */
    public static final long MAX_FILE_SIZE = 1024L * 1024 * 1024;

    // ----------------------------------------------------------------- audio
    public static final int DEFAULT_AUDIO_RELAY_PORT = 10001;
    public static final float SAMPLE_RATE = 16000f;
    public static final int SAMPLE_BITS = 16;
    public static final int FRAME_MS = 20;
    /** 16000 muestras/s * 0.020 s = 320 muestras * 2 bytes = 640 bytes. */
    public static final int FRAME_SAMPLES = (int) (SAMPLE_RATE * FRAME_MS / 1000);
    public static final int FRAME_BYTES = FRAME_SAMPLES * (SAMPLE_BITS / 8);
    public static final int AUDIO_HEADER = 8;
    public static final int MAX_DATAGRAM = AUDIO_HEADER + FRAME_BYTES;

    public static int readSsrc(byte[] packet) {
        return ByteBuffer.wrap(packet, 0, 4).getInt();
    }

    public static int readSeq(byte[] packet) {
        return ByteBuffer.wrap(packet, 4, 4).getInt();
    }

    public static void writeHeader(byte[] packet, int ssrc, int seq) {
        ByteBuffer.wrap(packet, 0, AUDIO_HEADER).putInt(ssrc).putInt(seq);
    }

    /** Normaliza una dirección IPv4 mapeada en IPv6 ("::ffff:10.0.0.5" -> "10.0.0.5"). */
    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.startsWith("::ffff:")) {
            h = h.substring(7);
        }
        return h;
    }
}
