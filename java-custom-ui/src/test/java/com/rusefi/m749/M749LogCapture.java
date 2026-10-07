package com.rusefi.m749;

import com.devexperts.logging.Logging;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import static org.junit.jupiter.api.Assertions.fail;

/** Observe the same logger used by the console through a real temporary log file. */
final class M749LogCapture implements AutoCloseable {
    private final Path path;
    private final Logger logger;
    private final FileHandler handler;

    M749LogCapture(Path path) throws IOException {
        this.path = path;
        Logging.getLogging(M749Panel.class); // Initialize the console logging backend first.
        logger = Logger.getLogger(M749Panel.class.getName());
        handler = new FileHandler(path.toString());
        handler.setFormatter(new SimpleFormatter());
        logger.addHandler(handler);
    }

    String text() throws IOException {
        handler.flush();
        return Files.readString(path);
    }

    void await(String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (text().contains(expected)) return;
            Thread.sleep(10);
        }
        fail("Missing saved log message: " + expected + "\n" + text());
    }

    @Override public void close() {
        logger.removeHandler(handler);
        handler.close();
    }
}
