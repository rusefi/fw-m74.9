package com.rusefi.m749;

import com.rusefi.io.can.slcan.SlcanPortScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Complete main-flash backup through the bundled RAM helper. */
public final class M749ReadFlashCli {
    private M749ReadFlashCli() { }

    interface TransportFactory {
        RawCanTransport open(Options options) throws IOException;
    }

    static final class Options {
        Path output;
        String slcan, channel, pair, immo;
        int baud = 115200, bus = 1, chunk = 1024, block = 16, stmin = -1;
        int address = M749RamHelper.BASE, length = M749RamHelper.SIZE;
        boolean resume, running, reset;
    }

    static Options parse(String[] args) {
        Options o = new Options();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!seen.add(option)) { throw new IllegalArgumentException("Duplicate option " + option); }
            switch (option) {
                case "--resume": o.resume = true; continue;
                case "--helper-running": o.running = true; continue;
                case "--reset-after": o.reset = true; continue;
                case "--read-flash":
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) { o.output = Path.of(args[++i]); }
                    continue;
            }
            if (++i >= args.length) { throw new IllegalArgumentException("Missing value for " + option); }
            String value = args[i];
            if (value.startsWith("--")) { throw new IllegalArgumentException("Missing value for " + option); }
            switch (option) {
                case "--slcan": o.slcan = value; break;
                case "--channel": o.channel = value; break;
                case "--serial-baud": o.baud = Integer.decode(value); break;
                case "--slcan-bus": o.bus = Integer.decode(value); break;
                case "--chunk-size": o.chunk = Integer.decode(value); break;
                case "--block-size": o.block = Integer.decode(value); break;
                case "--stmin": o.stmin = Integer.decode(value); break;
                case "--start": o.address = Integer.decode(value); break;
                case "--length": o.length = Integer.decode(value); break;
                case "--pair-file": o.pair = value; break;
                case "--immo-backup": o.immo = value; break;
                default: throw new IllegalArgumentException("Unknown read option " + option);
            }
        }
        if (!seen.contains("--read-flash") || o.slcan != null && o.channel != null) {
            throw new IllegalArgumentException("Use --read-flash with at most one --slcan PORT or --channel PCAN channel");
        }
        if (o.output == null && o.resume) { throw new IllegalArgumentException("--resume requires the original output filename"); }
        if (o.output == null) {
            o.output = Path.of("m749-full-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
                    .withZone(ZoneOffset.UTC).format(Instant.now()) + ".bin");
        }
        if (o.slcan == null && o.channel == null) { o.slcan = "auto"; }
        if (!seen.contains("--length")) { o.length = M749RamHelper.BASE + M749RamHelper.SIZE - o.address; }
        M749RamHelper.requireRange(o.address, o.length);
        if (o.chunk < 1 || o.chunk > 4080 || o.block < 0 || o.block > 255 ||
                o.baud < 9600 || o.baud > 4_000_000 || o.bus < 1 || o.bus > 3 ||
                (seen.contains("--stmin") && (o.stmin < 0 || o.stmin > 127))) {
            throw new IllegalArgumentException("Invalid block, flow control, serial baud or bus");
        }
        if (o.slcan == null && (seen.contains("--serial-baud") || seen.contains("--slcan-bus"))) {
            throw new IllegalArgumentException("Serial settings require --slcan");
        }
        if ("auto".equalsIgnoreCase(o.slcan) && o.baud != 115200) {
            throw new IllegalArgumentException("SLCAN auto scan uses 115200; select --slcan PORT for a different serial baud");
        }
        if (o.pair != null && o.immo != null || o.running && (o.pair != null || o.immo != null)) {
            throw new IllegalArgumentException("Choose one authorization credential, only when launching the helper");
        }
        // Allow for timestamped ASCII frames and serial start/stop bits.
        if (o.stmin < 0) { o.stmin = o.slcan == null ? 1 : Math.max(3, (270000 + o.baud - 1) / o.baud); }
        return o;
    }

    static int execute(String[] args, Consumer<String> out) throws IOException, InterruptedException {
        return execute(args, o -> open(o, out), out);
    }

    static int identify(String[] args, Consumer<String> out) throws IOException, InterruptedException {
        return identify(args, o -> open(o, out), out);
    }

    static int identify(String[] args, TransportFactory factory, Consumer<String> out) throws IOException, InterruptedException {
        String usage = "Usage: m749-cli --identify [--slcan PORT|auto | --channel CHANNEL|auto] " +
                "[--serial-baud BAUD] [--slcan-bus 1..3]";
        java.util.ArrayList<String> transportArgs = new java.util.ArrayList<>();
        transportArgs.add("--read-flash");
        boolean identify = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) { out.accept(usage); return 0; }
            if (arg.equals("--identify") && !identify) { identify = true; continue; }
            if (!(arg.equals("--slcan") || arg.equals("--channel") || arg.equals("--serial-baud") ||
                    arg.equals("--slcan-bus")) || i + 1 >= args.length) {
                out.accept(usage); return 2;
            }
            transportArgs.add(arg);
            transportArgs.add(args[++i]);
        }
        if (!identify) { out.accept(usage); return 2; }
        Options o;
        try { o = parse(transportArgs.toArray(new String[0])); }
        catch (IllegalArgumentException e) { out.accept(e.getMessage()); out.accept(usage); return 2; }
        try (RawCanTransport transport = factory.open(o)) {
            M749EcuProbe.identify(new UdsClient(transport, o.block, o.stmin), out);
        }
        return 0;
    }

    static int execute(String[] args, TransportFactory factory, Consumer<String> out) throws IOException, InterruptedException {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) { usage(out); return 0; }
        }
        Options o;
        try { o = parse(args); }
        catch (IllegalArgumentException e) { out.accept(e.getMessage()); usage(out); return 2; }
        byte[] helper = M749RamHelper.load();
        M749Immo authorization = o.pair != null ? M749PairFile.load(Path.of(o.pair)).credential() :
                o.immo == null ? null : M749Immo.load(Path.of(o.immo));
        // Resource, credential and file/resume validation precede all adapter access.
        try (FlashReadFile file = new FlashReadFile(o.output, o.address, o.length, o.resume)) {
            out.accept(String.format("Main-flash backup %08X..%08X -> %s", o.address,
                    o.address + o.length - 1, o.output));
            out.accept("Receive flow control: BS=" + o.block + ", STmin=" + o.stmin + " ms; chunk=" + o.chunk);
            boolean published = false;
            try (RawCanTransport transport = factory.open(o)) {
                if (authorization != null) { authorization.authorize(transport, out); }
                M749RamHelper reader = new M749RamHelper(new UdsClient(transport, o.block, o.stmin), o.stmin);
                file.identify(reader.start(helper, o.running, out));
                String hash = M749FlashReader.read(reader, file, o.chunk, out);
                published = true;
                out.accept("Backup complete: " + o.output + " SHA-256 " + hash);
                if (o.reset) {
                    reader.reset();
                    out.accept("Reset acknowledged; application return is not checked");
                } else {
                    out.accept("RAM helper remains active; use --helper-running for another read. Application return is not checked.");
                }
            } catch (IOException | InterruptedException e) {
                if (published) {
                    out.accept("Backup is complete. Reset or adapter cleanup failed; the saved binary remains valid.");
                } else {
                    out.accept("Read stopped. Saved progress remains in the output sidecars; no failed block is certified.");
                    out.accept("No reset is requested after failure. Use --resume and, if the helper is still active, --helper-running.");
                }
                throw e;
            }
        }
        return 0;
    }

    static RawCanTransport open(Options o, Consumer<String> out) throws IOException {
        if (o.slcan != null) {
            String port = o.slcan;
            if (port.equalsIgnoreCase("auto")) {
                out.accept("Scanning serial ports for SLCAN (skipping detected TunerStudio consoles)");
                port = selectSlcan(SlcanPortScanner.scanOnce(SlcanPortScanner.Probes.REAL), out);
            }
            out.accept("Using SLCAN " + port);
            return SlcanTransport.open(port, o.baud, o.bus, out);
        }
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IOException("PCAN requires native Windows Java; use --slcan on this platform");
        }
        PcanDevice device = new PcanDevice();
        List<PcanDevice.Channel> channels = device.scan();
        String selected = o.channel;
        if (selected.equalsIgnoreCase("auto")) {
            List<String> available = new java.util.ArrayList<>();
            for (PcanDevice.Channel channel : channels) { if (channel.available) { available.add(channel.handle.name()); } }
            selected = selectOnly(available, "PCAN", "--channel");
        }
        for (PcanDevice.Channel channel : channels) {
            if (channel.handle.name().equalsIgnoreCase(selected)) {
                if (!channel.available) { throw new IOException("PCAN channel is in use: " + o.channel); }
                out.accept("Using PCAN " + channel.handle.name());
                return device.open(channel);
            }
        }
        throw new IOException("PCAN channel not found: " + o.channel);
    }

    static String selectSlcan(List<SlcanPortScanner.Result> results, Consumer<String> out) throws IOException {
        if (Thread.currentThread().isInterrupted()) { throw new IOException("SLCAN scan interrupted"); }
        List<String> candidates = new java.util.ArrayList<>();
        for (SlcanPortScanner.Result result : results) {
            out.accept(result.toString());
            if (result.type == SlcanPortScanner.Type.SLCAN) { candidates.add(result.port); }
        }
        return selectOnly(candidates, "SLCAN", "--slcan");
    }

    static String selectOnly(List<String> candidates, String kind, String option) throws IOException {
        if (candidates.isEmpty()) { throw new IOException("No available " + kind + " adapter found; connect one or specify " + option); }
        if (candidates.size() != 1) { throw new IOException("Multiple " + kind + " adapters found: " + candidates + "; choose " + option); }
        return candidates.get(0);
    }

    private static void usage(Consumer<String> out) {
        out.accept("m749-cli --read-flash [OUTPUT.bin] [--slcan PORT|auto | --channel PCAN_USBBUS1|auto]");
        out.accept("Defaults: timestamped m749-full-*.bin in the current directory, automatic SLCAN scan.");
        out.accept("  [--resume] [--helper-running] [--reset-after]");
        out.accept("  [--chunk-size 1..4080] [--block-size 0..255] [--stmin 0..127]");
        out.accept("  [--serial-baud 115200] [--slcan-bus 1..3] [--start 0x08000000] [--length 0x3F0000]");
        out.accept("  [--pair-file ECU.pair | --immo-backup PAIRED_FULLFLASH.bin]");
        out.accept("Default: 4032 KiB main flash, session-60 RAM helper, two matching reads per block.");
        out.accept("SLCAN works on supported desktop OSes; PCAN requires Windows Java and matching PEAK libraries.");
        out.accept("Partial output/checkpoint are resumable. Completed output is never overwritten.");
    }
}
