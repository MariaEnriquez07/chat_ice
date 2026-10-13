package client;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Salida de consola thread-safe.
 *
 * <p>El hilo principal lee comandos (bloqueado en readLine) mientras los hilos
 * de Ice imprimen eventos asíncronos. Para que no se mezclen, toda impresión
 * pasa por un único candado y, después de cada evento, se vuelve a dibujar el
 * prompt. Los colores ANSI se desactivan con {@code -Pcolor=false}.
 */
final class Console {

    private static final Object LOCK = new Object();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final PrintStream OUT = System.out;

    static final String RESET = "\u001B[0m";
    static final String BOLD = "\u001B[1m";
    static final String DIM = "\u001B[2m";
    static final String RED = "\u001B[31m";
    static final String GREEN = "\u001B[32m";
    static final String YELLOW = "\u001B[33m";
    static final String BLUE = "\u001B[34m";
    static final String MAGENTA = "\u001B[35m";
    static final String CYAN = "\u001B[36m";

    private static volatile boolean color = true;
    private static volatile String prompt = "> ";

    private Console() {}

    static void setColor(boolean enabled) {
        color = enabled;
    }

    static void setPrompt(String p) {
        prompt = p;
    }

    static String c(String code, String text) {
        return color ? code + text + RESET : text;
    }

    static String now() {
        return LocalTime.now().format(TIME);
    }

    /** Evento asíncrono: limpia la línea del prompt, imprime y lo redibuja. */
    static void event(String text) {
        synchronized (LOCK) {
            OUT.print(color ? "\r\u001B[2K" : "\r");
            OUT.println(text);
            OUT.print(prompt);
            OUT.flush();
        }
    }

    /** Respuesta directa a un comando (el prompt se imprime al leer el siguiente). */
    static void println(String text) {
        synchronized (LOCK) {
            OUT.println(text);
        }
    }

    static void info(String text) {
        println(c(CYAN, "* ") + text);
    }

    static void ok(String text) {
        println(c(GREEN, "[ok] ") + text);
    }

    static void error(String text) {
        println(c(RED, "[error] " + text));
    }

    static void printPrompt() {
        synchronized (LOCK) {
            OUT.print(prompt);
            OUT.flush();
        }
    }
}
