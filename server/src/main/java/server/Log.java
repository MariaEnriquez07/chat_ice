package server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Bitácora mínima del servidor (thread-safe). */
final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    static synchronized void info(String msg) {
        System.out.println("[" + LocalTime.now().format(FMT) + "] " + msg);
    }

    static synchronized void warn(String msg) {
        System.out.println("[" + LocalTime.now().format(FMT) + "] WARN " + msg);
    }
}
