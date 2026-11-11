package com.rusefi.m749;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** USD maintenance commands with offline validation before adapter access. */
final class M749UsdPatchCli {
    static final Set<String> MODES = Set.of("--inspect-usd", "--patch-usd", "--verify-usd-patch");
    interface TransportFactory { RawCanTransport open(M749ConnectionOptions options) throws IOException; }
    static final class Options extends M749ConnectionOptions {
        String mode;
        Path directory;
        boolean confirm, dryRun;
    }

    static Options parse(String[] args) {
        Options o = new Options();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!seen.add(arg)) throw new IllegalArgumentException("Duplicate option " + arg);
            if (arg.equals("--confirm-usd-erase")) { o.confirm = true; continue; }
            if (arg.equals("--dry-run")) { o.dryRun = true; continue; }
            if (++i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for " + arg);
            if (MODES.contains(arg)) {
                if (o.mode != null) throw new IllegalArgumentException("Choose one USD maintenance operation");
                o.mode = arg; o.directory = Path.of(args[i]);
            } else if (M749ConnectionOptions.FLAGS.contains(arg)) o.accept(arg, args[i]);
            else throw new IllegalArgumentException("Unknown USD maintenance option " + arg);
        }
        if (o.mode == null) throw new IllegalArgumentException("Choose a USD maintenance operation");
        boolean apply = o.mode.equals("--patch-usd");
        if ((!apply && (o.confirm || o.dryRun)) || (apply && o.confirm == o.dryRun)) {
            throw new IllegalArgumentException("--patch-usd requires either --dry-run or --confirm-usd-erase");
        }
        if (o.dryRun && o.specified()) throw new IllegalArgumentException("Offline dry run does not accept connection options");
        o.validate();
        return o;
    }

    static int execute(String[] args, Consumer<String> out) throws IOException, InterruptedException {
        return execute(args, o -> M749ConnectionOptions.open(o, out), out);
    }

    static int execute(String[] args, TransportFactory factory, Consumer<String> out) throws IOException, InterruptedException {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) { usage(out); return 0; }
        Options o;
        try { o = parse(args); }
        catch (IllegalArgumentException e) { out.accept(e.getMessage()); usage(out); return 2; }
        byte[] payload = M749UsdPatcher.load();
        M749RamHelper.load();
        byte[] snapshot = o.mode.equals("--inspect-usd") ? null : M749UsdPatcher.readInspection(o.directory);
        if (o.dryRun) {
            out.accept(String.format("USD patch inputs valid: snapshot CRC %08X; target EOPB0=05FA; payload SHA-256 %s",
                    M749UsdPatcher.crc(snapshot), M749UsdPatcher.SHA256));
            out.accept("Dry run complete; no adapter opened. Live application, protection and saved-option checks remain mandatory.");
            return 0;
        }
        if (o.mode.equals("--patch-usd") && Files.exists(o.directory.resolve(M749UsdPatcher.ATTEMPT_FILE)))
            throw new IOException("Apply already attempted with this inspection; use --verify-usd-patch, not another apply");
        if (o.mode.equals("--verify-usd-patch") && Files.exists(o.directory.resolve("verification.bin")))
            throw new IOException("Verification output already exists; existing record will not be overwritten");
        if (snapshot == null) {
            try {
                Files.createDirectory(o.directory);
            } catch (FileAlreadyExistsException e) {
                throw new IOException("USD inspection destination already exists: " + o.directory
                        + ". Choose a new directory name; no adapter was opened.", e);
            } catch (IOException e) {
                throw new IOException("Cannot create USD inspection directory " + o.directory
                        + ". Choose a new directory inside an existing writable folder; no adapter was opened. "
                        + e.getMessage(), e);
            }
        }
        out.accept("USD maintenance supports only the exact I862 OEM application; apply and power-cycle retention passed on one unit.");
        try (RawCanTransport transport = factory.open(o)) {
            M749UsdPatcher patcher = new M749UsdPatcher(o.client(transport, out), o.stmin, out);
            switch (o.mode) {
                case "--inspect-usd": patcher.inspect(o.directory, payload); return 0;
                case "--patch-usd": return patcher.apply(o.directory, snapshot, payload);
                case "--verify-usd-patch": patcher.verify(o.directory, snapshot); return 0;
                default: throw new AssertionError(o.mode);
            }
        }
    }

    static void usage(Consumer<String> out) {
        out.accept("m749-cli --inspect-usd NEW_DIRECTORY [connection options]");
        out.accept("m749-cli --patch-usd INSPECTION_DIRECTORY --dry-run");
        out.accept("m749-cli --patch-usd INSPECTION_DIRECTORY --confirm-usd-erase [connection options]");
        out.accept("m749-cli --verify-usd-patch INSPECTION_DIRECTORY [connection options]");
        out.accept("Inspect saves the full options and a checked result, then resets the reader back to OEM application.");
        out.accept("Apply erases/restores USD with FAP preserved; keep stable power. It cannot recover from power loss.");
        out.accept("Supported target: I862BA02, MCU 70084540, loader D7B6B894, application B167C266, FAP=FFFF.");
        out.accept("A failed application or resident loader cannot launch this patcher; OEM restoration is separate.");
        out.accept("Exit 3: apply outcome unconfirmed; verify, never automatically retry. Verification requests no reset.");
        M749ConnectionOptions.usage(out);
    }
}
