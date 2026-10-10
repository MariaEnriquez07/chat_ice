package client;

import common.Protocol;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Motor de voz sobre UDP (RF-05 / RF-06). Ice NO interviene aquí.
 *
 * <p>Tres hilos:
 * <ul>
 *   <li><b>captura</b>: lee 20 ms del micrófono ({@link TargetDataLine}),
 *       antepone la cabecera [ssrc|seq] y lo envía en un {@link DatagramPacket}
 *       a cada destino (el otro cliente en 1 a 1, o el relay en grupales).</li>
 *   <li><b>recepción</b>: hilo dedicado bloqueado en {@code socket.receive}.
 *       Guarda cada trama en un pequeño buffer de jitter por emisor (ssrc).</li>
 *   <li><b>reproducción</b>: cada 20 ms toma una trama de cada emisor, las
 *       MEZCLA (suma con saturación) y las escribe en los altavoces.</li>
 * </ul>
 *
 * <p>UDP no retransmite: si un paquete se pierde se reproduce silencio en ese
 * hueco y se sigue adelante (baja latencia antes que fidelidad).
 */
final class AudioEngine {

    static final AudioFormat FORMAT = new AudioFormat(Protocol.SAMPLE_RATE, Protocol.SAMPLE_BITS, 1, true, false);

    /** Máx. tramas en cola por emisor (8 × 20 ms = 160 ms). Si se llena se descarta la más vieja. */
    private static final int JITTER_CAPACITY = 8;
    /** Tramas que se acumulan antes de empezar a reproducir un emisor (40 ms). */
    private static final int PREBUFFER = 2;
    private static final long SOURCE_IDLE_MS = 3000;
    /** -Dchat.testTone=true: sin micrófono, envía un tono de prueba. */
    private static final boolean TEST_TONE = Boolean.getBoolean("chat.testTone");

    /** Buffer de jitter de un emisor. */
    private static final class Source {
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(JITTER_CAPACITY);
        volatile int lastSeq = Integer.MIN_VALUE;
        volatile long lastSeen = System.currentTimeMillis();
        boolean primed;

        void offer(int seq, byte[] frame) {
            lastSeen = System.currentTimeMillis();
            int last = lastSeq;
            if (last != Integer.MIN_VALUE && seq <= last && last - seq < 500) {
                return; // duplicado o llegó tarde (desordenado): se descarta
            }
            lastSeq = seq;
            while (!queue.offer(frame)) {
                queue.poll(); // cola llena: descartamos lo más viejo para no acumular latencia
            }
        }
    }

    private DatagramSocket socket;
    private volatile boolean running;
    private volatile boolean muted;
    private volatile List<InetSocketAddress> targets = List.of();
    private volatile List<InetAddress> allowedSources = List.of();
    private final Map<Integer, Source> sources = new ConcurrentHashMap<>();
    private int ssrc;
    private int seq;
    private TargetDataLine mic;
    private SourceDataLine speaker;
    private final List<Thread> threads = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();

    /** Paquetes de voz enviados / recibidos en la llamada actual. */
    String stats() {
        return "UDP enviados=" + sent.get() + " recibidos=" + received.get() + " emisores=" + sources.size();
    }

    /**
     * Abre el socket UDP local (antes de la señalización, para conocer el puerto).
     * @param port 0 = cualquier puerto libre
     * @return puerto local que hay que publicar por Ice
     */
    synchronized int open(int port) throws SocketException {
        if (socket == null || socket.isClosed()) {
            socket = new DatagramSocket(new InetSocketAddress(port));
            socket.setSoTimeout(500);
            socket.setReceiveBufferSize(1 << 18);
        }
        return socket.getLocalPort();
    }

    synchronized boolean isRunning() {
        return running;
    }

    /**
     * Arranca el flujo de voz.
     * @param destinations a quién enviar (peer en 1 a 1, relay en grupal)
     * @param mySsrc id de flujo (aleatorio en 1 a 1, asignado por el servidor en grupal)
     */
    synchronized void start(List<InetSocketAddress> destinations, int mySsrc) throws SocketException {
        if (running) {
            stop();
        }
        open(0);
        this.targets = List.copyOf(destinations);
        List<InetAddress> allowed = new ArrayList<>();
        for (InetSocketAddress d : destinations) {
            allowed.add(d.getAddress());
        }
        this.allowedSources = allowed;
        this.ssrc = mySsrc != 0 ? mySsrc : ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        this.seq = 0;
        this.muted = false;
        this.sources.clear();
        this.sent.set(0);
        this.received.set(0);
        this.running = true;

        threads.clear();
        threads.add(daemon(this::receiveLoop, "udp-receive"));
        threads.add(daemon(this::playbackLoop, "audio-playback"));
        threads.add(daemon(this::captureLoop, "audio-capture"));
        threads.forEach(Thread::start);
    }

    /** Detiene hilos, libera micrófono/altavoces y cierra el socket UDP. */
    synchronized void stop() {
        if (!running && socket == null) {
            return;
        }
        running = false;
        if (mic != null) {
            mic.stop();
            mic.close();
        }
        if (speaker != null) {
            speaker.stop();
            speaker.close();
        }
        if (socket != null) {
            socket.close();
        }
        for (Thread t : threads) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
        mic = null;
        speaker = null;
        socket = null;
        sources.clear();
    }

    boolean toggleMute() {
        muted = !muted;
        return muted;
    }

    boolean isMuted() {
        return muted;
    }

    // ------------------------------------------------------------- captura
    private void captureLoop() {
        byte[] packet = new byte[Protocol.MAX_DATAGRAM];
        byte[] keepAlive = new byte[Protocol.AUDIO_HEADER];
        TargetDataLine line = null;
        try {
            line = AudioSystem.getTargetDataLine(FORMAT);
            line.open(FORMAT, Protocol.FRAME_BYTES * 4);
            line.start();
            synchronized (this) {
                if (!running) {   // colgaron mientras abríamos el micrófono
                    line.close();
                    return;
                }
                mic = line;
            }
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            line = null;
            if (TEST_TONE) {
                Console.event(Console.c(Console.YELLOW, "[audio] Sin micrófono: enviando tono de prueba de 440 Hz"));
                toneLoop(packet);
                return;
            }
            Console.event(Console.c(Console.YELLOW, "[audio] Micrófono no disponible ("
                    + e.getMessage() + "): solo podrás escuchar."));
        }

        long lastKeepAlive = 0;
        while (running) {
            long now = System.currentTimeMillis();
            if (line == null || muted) {
                // Sin audio que enviar: keep-alive cada segundo para que el relay
                // conozca nuestra dirección y podamos seguir escuchando.
                if (now - lastKeepAlive >= 1000) {
                    lastKeepAlive = now;
                    Protocol.writeHeader(keepAlive, ssrc, seq);
                    sendToAll(keepAlive, keepAlive.length);
                }
                if (line == null) {
                    sleep(Protocol.FRAME_MS);
                    continue;
                }
            }
            int read = readFrame(line, packet);
            if (read <= 0) {
                continue;
            }
            if (muted) {
                continue; // se descarta lo capturado mientras está en mute
            }
            Protocol.writeHeader(packet, ssrc, seq++);
            sendToAll(packet, Protocol.AUDIO_HEADER + read);
        }
    }

    /**
     * Modo de prueba (-PtestTone=true): genera un tono de 440 Hz cuando no hay
     * micrófono, útil para probar la red en equipos sin micrófono.
     */
    private void toneLoop(byte[] packet) {
        double phase = 0;
        double step = 2 * Math.PI * 440 / Protocol.SAMPLE_RATE;
        long next = System.nanoTime();
        while (running) {
            for (int i = 0; i < Protocol.FRAME_SAMPLES; i++) {
                short v = (short) (Math.sin(phase) * 6000);
                phase += step;
                packet[Protocol.AUDIO_HEADER + 2 * i] = (byte) v;
                packet[Protocol.AUDIO_HEADER + 2 * i + 1] = (byte) (v >> 8);
            }
            Protocol.writeHeader(packet, ssrc, seq++);
            if (!muted) {
                sendToAll(packet, Protocol.MAX_DATAGRAM);
            } else if (seq % 50 == 0) {
                sendToAll(packet, Protocol.AUDIO_HEADER); // keep-alive
            }
            next += Protocol.FRAME_MS * 1_000_000L;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                sleep(wait / 1_000_000L);
            }
        }
    }

    private static int readFrame(TargetDataLine line, byte[] packet) {
        int off = Protocol.AUDIO_HEADER;
        int total = 0;
        while (total < Protocol.FRAME_BYTES) {
            int n = line.read(packet, off + total, Protocol.FRAME_BYTES - total);
            if (n <= 0) {
                break; // línea cerrada
            }
            total += n;
        }
        return total;
    }

    private void sendToAll(byte[] data, int len) {
        DatagramSocket s = socket;
        if (s == null) {
            return;
        }
        for (InetSocketAddress dst : targets) {
            try {
                s.send(new DatagramPacket(data, 0, len, dst));
                if (len > Protocol.AUDIO_HEADER) {
                    sent.incrementAndGet();
                }
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    // ----------------------------------------------------------- recepción
    private void receiveLoop() {
        byte[] buf = new byte[Protocol.MAX_DATAGRAM + 64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            DatagramSocket s = socket;
            if (s == null) {
                return;
            }
            try {
                p.setLength(buf.length);
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return; // socket cerrado al colgar
            }
            int len = p.getLength();
            if (len <= Protocol.AUDIO_HEADER || !allowedSources.contains(p.getAddress())) {
                continue; // keep-alive o paquete de un desconocido
            }
            int from = Protocol.readSsrc(buf);
            if (from == ssrc) {
                continue; // nunca reproducir nuestra propia voz (evita eco)
            }
            received.incrementAndGet();
            int payload = Math.min(len - Protocol.AUDIO_HEADER, Protocol.FRAME_BYTES);
            byte[] frame = new byte[Protocol.FRAME_BYTES];
            System.arraycopy(buf, Protocol.AUDIO_HEADER, frame, 0, payload);
            sources.computeIfAbsent(from, k -> new Source()).offer(Protocol.readSeq(buf), frame);
        }
    }

    // -------------------------------------------------------- reproducción
    private void playbackLoop() {
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, Protocol.FRAME_BYTES * 6);
            line.start();
            synchronized (this) {
                if (!running) {
                    line.close();
                    return;
                }
                speaker = line;
            }
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            Console.event(Console.c(Console.YELLOW, "[audio] Altavoces no disponibles (" + e.getMessage() + ")."));
            line = null;
        }

        int[] acc = new int[Protocol.FRAME_SAMPLES];
        byte[] out = new byte[Protocol.FRAME_BYTES];
        while (running) {
            java.util.Arrays.fill(acc, 0);
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<Integer, Source>> it = sources.entrySet().iterator();
            while (it.hasNext()) {
                Source src = it.next().getValue();
                if (now - src.lastSeen > SOURCE_IDLE_MS) {
                    it.remove(); // emisor que dejó de hablar o salió de la llamada
                    continue;
                }
                if (!src.primed) {
                    if (src.queue.size() < PREBUFFER) {
                        continue;
                    }
                    src.primed = true;
                }
                byte[] frame = src.queue.poll();
                if (frame == null) {
                    src.primed = false; // se vació: volvemos a acumular
                    continue;
                }
                for (int i = 0; i < Protocol.FRAME_SAMPLES; i++) {
                    acc[i] += (short) ((frame[2 * i] & 0xff) | (frame[2 * i + 1] << 8));
                }
            }
            for (int i = 0; i < Protocol.FRAME_SAMPLES; i++) {
                int v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, acc[i]));
                out[2 * i] = (byte) v;
                out[2 * i + 1] = (byte) (v >> 8);
            }
            if (line != null) {
                line.write(out, 0, out.length); // bloquea ~20 ms: marca el ritmo
            } else {
                sleep(Protocol.FRAME_MS);
            }
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
