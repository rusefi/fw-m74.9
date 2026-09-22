package com.rusefi.m749;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Command-line variant of the M74.9 tab: scan PCAN adapters and read ECU identification. */
public final class M749Cli {
    private M749Cli() {
    }

    public static void main(String[] args) {
        boolean listOnly = false;
        String requested = null;
        for (String arg : args) {
            if ("--list".equals(arg)) {
                listOnly = true;
            } else if ("-h".equals(arg) || "--help".equals(arg)) {
                usage(System.out);
                return;
            } else if (arg.startsWith("-") || requested != null) {
                usage(System.err);
                System.exit(2);
            } else {
                requested = arg;
            }
        }
        int exit;
        try {
            exit = run(requested, listOnly, M749Monitor.pcanBackend(), System.out::println);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = 1;
        } catch (IOException | RuntimeException | LinkageError e) {
            System.out.println(e instanceof LinkageError
                    ? "PCAN native library unavailable. Install the PCAN driver and matching PCAN-Basic/JNI libraries."
                    : "PCAN scan failed: " + e.getMessage());
            exit = 1;
        }
        System.exit(exit);
    }

    static int run(String requested, boolean listOnly, M749Monitor.Backend backend, Consumer<String> out)
            throws IOException, InterruptedException {
        List<PcanDevice.Channel> channels = backend.scan();
        if (channels.isEmpty()) {
            out.accept("PCAN not detected. Connect a PCAN adapter and check the driver installation.");
            return 1;
        }
        out.accept("PCAN detected: " + channels);
        if (listOnly) {
            return 0;
        }
        List<PcanDevice.Channel> candidates = new ArrayList<>();
        for (PcanDevice.Channel channel : channels) {
            if (requested == null ? channel.available : channel.handle.name().equalsIgnoreCase(requested)) {
                candidates.add(channel);
            }
        }
        if (candidates.isEmpty()) {
            out.accept(requested == null ? "All detected channels are in use by another application."
                    : "Channel " + requested + " not found among detected channels.");
            return requested == null ? 1 : 2;
        }
        // The driver can report phantom channels (e.g. ISA with no driver), so
        // without an explicit channel keep trying until one yields an ECU.
        for (PcanDevice.Channel channel : candidates) {
            out.accept("Querying M74.9 via " + channel.handle + " at 500 kbit/s (7E0 / 7E8)");
            try {
                backend.identify(channel, out);
                return 0;
            } catch (IOException | RuntimeException e) {
                out.accept("Identification via " + channel.handle + " failed: " + e.getMessage());
            }
        }
        out.accept("No channel produced an M74.9 identification.");
        return 1;
    }

    private static void usage(PrintStream out) {
        out.println("M74.9 identification tool: reads VIN, IDs and metadata over PCAN (500 kbit/s, 7E0/7E8)");
        out.println("Usage: m749 [channel]   query via the given channel, e.g. PCAN_USBBUS1,");
        out.println("                        or try every available channel when omitted");
        out.println("       m749 --list     only scan and list PCAN channels");
    }
}
