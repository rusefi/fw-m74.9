package com.rusefi.m749;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Identification, offline image validation and OEM-loader firmware upload. */
public final class M749Cli {
    private M749Cli() {
    }

    interface UploadAction {
        void upload(String channel, M749Image image, boolean verifyBytes, M749Immo immo, Consumer<String> out)
                throws IOException, InterruptedException;
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = execute(args, M749Monitor.pcanBackend(), M749Cli::upload, System.out::println);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted; no further requests sent. Check ECU state before retrying.");
            exit = 1;
        } catch (IOException | RuntimeException | LinkageError e) {
            System.err.println(e instanceof LinkageError
                    ? "PCAN native library unavailable. Install the PCAN driver and matching PCAN-Basic/JNI libraries."
                    : e.getMessage());
            exit = 1;
        }
        System.exit(exit);
    }

    static int execute(String[] args, M749Monitor.Backend backend, UploadAction uploader, Consumer<String> out)
            throws IOException, InterruptedException {
        boolean list = false;
        boolean dryRun = false;
        boolean calibration = false;
        boolean verifyBytes = false;
        String channel = null;
        String file = null;
        String immoBackup = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help": case "-h": usage(out); return 0;
                case "--list": list = true; break;
                case "--dry-run": dryRun = true; break;
                case "--calibration": calibration = true; break;
                case "--verify-bytes": verifyBytes = true; break;
                case "--immo-backup":
                    if (++i == args.length || immoBackup != null) { usage(out); return 2; }
                    immoBackup = args[i];
                    break;
                case "--upload":
                    if (++i == args.length || file != null) { usage(out); return 2; }
                    file = args[i];
                    break;
                case "--channel":
                    if (++i == args.length || channel != null) { usage(out); return 2; }
                    channel = args[i];
                    break;
                default:
                    if (arg.startsWith("-") || channel != null) { usage(out); return 2; }
                    channel = arg;
            }
        }
        if ((file == null && (dryRun || calibration || verifyBytes || immoBackup != null)) ||
                (list && (file != null || channel != null)) || (file != null && !dryRun && channel == null)) {
            usage(out);
            return 2;
        }
        if (file == null) {
            return run(channel, list, backend, out);
        }
        // Parse all input and prove the complete CRC domain before native library/device access.
        M749Image image = M749Image.load(Path.of(file), calibration ? M749Image.Domain.CALIBRATION : M749Image.Domain.SOFTWARE);
        image.requireActivationSupport();
        M749Immo immo = immoBackup == null ? null : M749Immo.load(Path.of(immoBackup));
        image.describe(out);
        if (immo != null) {
            out.accept("Paired I865 IMMO backup validated; normal CAN authorization enabled");
        }
        if (dryRun) {
            out.accept("Dry run complete; no adapter was opened. Target compatibility and retained-domain CRC remain device checks.");
            return 0;
        }
        uploader.upload(channel, image, verifyBytes, immo, out);
        return 0;
    }

    private static void upload(String requested, M749Image image, boolean verifyBytes, M749Immo immo, Consumer<String> out)
            throws IOException, InterruptedException {
        PcanDevice device = new PcanDevice();
        for (PcanDevice.Channel channel : device.scan()) {
            if (channel.handle.name().equalsIgnoreCase(requested)) {
                if (!channel.available) {
                    throw new IOException("Channel " + requested + " is in use");
                }
                out.accept("Uploading through " + channel.handle + " at 500 kbit/s (7E0 / 7E8)");
                try (RawCanTransport transport = device.open(channel)) {
                    if (immo != null) {
                        immo.authorize(transport, out);
                    }
                    new M749Uploader(new UdsClient(transport), out).upload(image, verifyBytes);
                }
                return;
            }
        }
        throw new IOException("Requested PCAN channel not found: " + requested);
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
                List<String> summary = backend.identify(channel, out);
                for (String line : summary) {
                    out.accept(line);
                }
                return 0;
            } catch (IOException | RuntimeException e) {
                out.accept("Identification via " + channel.handle + " failed: " + e.getMessage());
            }
        }
        out.accept("No channel produced an M74.9 identification.");
        return 1;
    }

    private static void usage(Consumer<String> out) {
        out.accept("M74.9 PCAN CLI (500 kbit/s, 7E0/7E8; I865 OEM resident loader)");
        out.accept("Usage: m749-cli [channel]                 identify ECU; tries available channels when omitted");
        out.accept("       m749-cli --list                    list PCAN channels");
        out.accept("       m749-cli --upload FILE --dry-run   validate addressed HEX/SREC without hardware");
        out.accept("       m749-cli --upload FILE --channel PCAN_USBBUS1 [--calibration] [--verify-bytes]");
        out.accept("                  [--immo-backup PAIRED_FULLFLASH.bin]");
        out.accept("--immo-backup enables normal I865 CAN authorization; cycle bench power when the listener reports ready.");
        out.accept("Software requires the M749ACT1 activation ABI. Calibration is a separate complete CRC domain.");
        out.accept("--upload erases/programs the selected domain, preserves OEM programming metadata, and activates.");
        out.accept("Default verification: per-block sum plus application-side CRCs; --verify-bytes adds slow byte comparisons.");
    }
}
