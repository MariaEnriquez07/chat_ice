package server;

import common.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relay centralizado de audio (RF-06, estrategia "SFU ligero").
 *
 * <p>Un único {@link DatagramSocket} recibe la voz de todos los participantes.
 * Con el {@code ssrc} de la cabecera identifica al emisor y su llamada, y
 * reenvía el datagrama SIN modificar a los demás participantes de esa misma
 * llamada (nunca de vuelta al emisor, así no hay eco del servidor).
 *
 * <p>Por cada paquete que entra salen N-1. El audio no se mezcla aquí: cada
 * cliente mezcla los flujos que recibe.
 */
final class AudioRelay implements Runnable {

    private final DatagramSocket socket;
    private final CallManager calls;
    private final Thread thread;
    private volatile boolean running = true;
    private final AtomicLong packetsIn = new AtomicLong();
    private final AtomicLong packetsOut = new AtomicLong();

    AudioRelay(int port, CallManager calls) throws SocketException {
        this.socket = new DatagramSocket(new InetSocketAddress(port));
        this.socket.setReceiveBufferSize(1 << 20);
        this.calls = calls;
        this.thread = new Thread(this, "audio-relay");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
        Log.info("Relay de audio UDP escuchando en el puerto " + socket.getLocalPort());
    }

    @Override
    public void run() {
        byte[] buf = new byte[Protocol.MAX_DATAGRAM + 64];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        long lastReport = System.currentTimeMillis();
        while (running) {
            try {
                in.setLength(buf.length);
                socket.receive(in);
                int len = in.getLength();
                if (len < Protocol.AUDIO_HEADER) {
                    continue;
                }
                CallManager.Participant src = calls.participant(Protocol.readSsrc(buf));
                if (src == null || !sameHost(in.getAddress(), src.user.remoteHost)) {
                    continue; // ssrc desconocido o suplantado: se descarta
                }
                // Aprendemos la dirección real (útil si hay NAT o el puerto cambió)
                InetSocketAddress from = (InetSocketAddress) in.getSocketAddress();
                if (!from.equals(src.address)) {
                    src.address = from;
                }
                if (len == Protocol.AUDIO_HEADER) {
                    continue; // keep-alive sin audio
                }
                packetsIn.incrementAndGet();
                for (CallManager.Participant dst : src.conference.participants.values()) {
                    if (dst == src) {
                        continue;
                    }
                    socket.send(new DatagramPacket(buf, 0, len, dst.address));
                    packetsOut.incrementAndGet();
                }
                long now = System.currentTimeMillis();
                if (now - lastReport > 30_000) {
                    lastReport = now;
                    Log.info("Relay audio: " + packetsIn.get() + " paquetes recibidos, "
                            + packetsOut.get() + " reenviados");
                }
            } catch (IOException e) {
                if (running) {
                    Log.warn("Relay de audio: " + e.getMessage());
                }
            }
        }
    }

    private static boolean sameHost(InetAddress addr, String host) {
        return Protocol.normalizeHost(addr.getHostAddress()).equals(Protocol.normalizeHost(host));
    }

    void close() {
        running = false;
        socket.close();
    }
}
