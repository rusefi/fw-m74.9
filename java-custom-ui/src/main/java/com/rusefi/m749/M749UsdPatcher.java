package com.rusefi.m749;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.Consumer;
import static com.rusefi.m749.M749Identification.bytes;

/** Explicit I862 option maintenance, separate from firmware upload. */
final class M749UsdPatcher {
    static final int SNAPSHOT = 0x20019400, COMMAND = 0x2001B400, REPORT = 0x2001B440;
    static final int APP_CRC = 0xB167C266, BOOT_CRC = 0xD7B6B894, MCU = 0x70084540;
    static final String SHA256 = "b112975af46925c3ca1f651a18a97fd68d733f38b3fa7d050eea3852428b213e";
    static final String SNAPSHOT_FILE = "usd-before.bin", REPORT_FILE = "inspection.bin";
    static final String ATTEMPT_FILE = "apply-requested.txt";
    private final M749Uploader.Connection connection;
    private final M749RamHelper helper;
    private final Consumer<String> out;

    M749UsdPatcher(M749Uploader.Connection connection, int stmin, Consumer<String> out) {
        this.connection = connection;
        this.helper = new M749RamHelper(connection, stmin);
        this.out = out;
    }

    static byte[] load() throws IOException {
        try (InputStream in = M749UsdPatcher.class.getResourceAsStream("/usd_patcher_2001ba00.bin")) {
            if (in == null) throw new IOException("Bundled USD patcher is missing");
            byte[] data = in.readAllBytes();
            if (data.length != 1804 || !FlashReadFile.hash(data).equals(SHA256)) {
                throw new IOException("Bundled USD patcher size/SHA-256 mismatch");
            }
            return data;
        }
    }

    static int crc(byte[] data) { return M749Image.crc32(data, data.length, -1); }
    static int word(byte[] data, int index) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(index * 4);
    }

    static byte[] command(byte[] snapshot) throws IOException {
        if (snapshot.length != 4096) throw new IOException("A complete 4096-byte USD snapshot is required");
        ByteBuffer b = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        int old = b.getShort(16) & 65535;
        if ((b.getShort(0) & 65535) != 65535 || (old != 0x00FF && old != 0x05FA)) {
            throw new IOException("USD patch requires protected FAP=FFFF and EOPB0=00FF or 05FA");
        }
        for (int offset = 0; offset < snapshot.length; offset += 2) {
            int value = b.getShort(offset) & 65535;
            boolean reserved = offset == 0x12 || offset >= 0x1C && offset < 0x34 || offset >= 0x44 && offset < 0x4C;
            if (!reserved && value != 65535 && ((value >>> 8) ^ (value & 255)) != 255) {
                throw new IOException(String.format("Unsupported USD encoding at offset %03X", offset));
            }
        }
        ByteBuffer c = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : new int[]{0x55445341, 1, crc(snapshot), old, 0x05FA, 0xE7419B2C, 0x18BE64D3}) c.putInt(v);
        c.putInt(crc(Arrays.copyOf(c.array(), 28)));
        return c.array();
    }

    static void report(byte[] data) throws IOException {
        if (data.length != 64 || word(data, 0) != 0x55445352 || word(data, 1) != 1 ||
                word(data, 15) != crc(Arrays.copyOf(data, 60))) throw new IOException("Invalid USD report header/CRC");
    }

    static void inspected(byte[] snapshot, byte[] data) throws IOException {
        byte[] cmd = command(snapshot);
        report(data);
        if (word(data, 2) != 0 || word(data, 3) != 1 || word(data, 6) != crc(snapshot) ||
                word(data, 8) != word(cmd, 3) || word(data, 9) != 0x05FA || word(data, 10) != MCU ||
                word(data, 11) != BOOT_CRC || word(data, 12) != 0 || word(data, 13) != 0 || word(data, 14) != APP_CRC) {
            throw new IOException("USD inspection did not qualify this snapshot and target");
        }
    }

    static byte[] readInspection(Path directory) throws IOException {
        byte[] snapshot = Files.readAllBytes(directory.resolve(SNAPSHOT_FILE));
        inspected(snapshot, Files.readAllBytes(directory.resolve(REPORT_FILE)));
        return snapshot;
    }

    static void saveNew(Path path, byte[] data) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) channel.write(b);
            channel.force(true);
        }
    }

    private byte[] query(byte[] q, byte[] prefix, long timeout) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        byte[] r = connection.exchange(q, prefix, timeout);
        if (!UdsClient.startsWith(r, prefix)) throw new IOException("Unexpected USD maintenance response");
        return r;
    }

    private void requireApplication() throws IOException, InterruptedException {
        byte[] session = query(bytes(0x22, 0xF1, 0x86), bytes(0x62, 0xF1, 0x86), 5_000);
        if (session.length != 4 || (session[3] != 1 && session[3] != 3 && session[3] != 0x60)) {
            throw new IOException("USD patcher requires the running I862 OEM application; resident-loader launch is unavailable");
        }
        byte[] identity = query(bytes(0x22, 0xF1, 0x89), bytes(0x62, 0xF1, 0x89), 5_000);
        String software = new String(identity, 3, identity.length - 3, java.nio.charset.StandardCharsets.US_ASCII)
                .replace("\0", "").trim();
        if (!software.equals("I862BA02_w2309v4")) throw new IOException("USD patcher requires I862BA02_w2309v4; received " + software);
        // The payload also computes both complete CRC domains before any USD mutation.
        helper.enterApplication(out);
    }

    private void stage(byte[] payload) throws IOException, InterruptedException {
        if (!Arrays.equals(payload, load())) throw new IOException("USD patcher integrity check failed");
        helper.writeRam(REPORT, new byte[64]);
        helper.writeRam(M749BootDiagnostic.ADDRESS, new byte[64]);
        helper.writeRam(0x2001BA00, payload);
    }

    private void launch(int entry) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        byte[] prefix = bytes(0x71, 1, 0xF0, 0);
        byte[] reply = connection.exchangeLongRunning(
                bytes(0x31, 1, 0xF0, 0, entry >>> 24, entry >>> 16, entry >>> 8, entry), prefix, 60_000);
        if (!UdsClient.startsWith(reply, prefix)) throw new IOException("Unexpected USD routine response");
    }

    private byte[] stableRecord(int address, int size) throws IOException, InterruptedException {
        byte[] data = helper.readPatchRecord(address, size);
        if (!Arrays.equals(data, helper.readPatchRecord(address, size))) throw new IOException("USD inspection record changed during read");
        return data;
    }

    void inspect(Path directory, byte[] payload) throws IOException, InterruptedException {
        requireApplication();
        stage(payload);
        out.accept("Inspecting USD; no option erase or programming is requested");
        launch(0x2001BA00);
        helper.startInSession(out);
        byte[] record = stableRecord(REPORT, 64);
        report(record);
        if (word(record, 2) != 0) {
            saveNew(directory.resolve("inspection-rejected.bin"), record);
            throw new IOException(String.format("USD inspection rejected: status %02X, stage %d, offset %03X, "
                    + "EOPB0=%04X, destructive=%d. Result saved as inspection-rejected.bin; reader helper remains active.",
                    word(record, 2), word(record, 3), word(record, 4), word(record, 8), word(record, 12)));
        }
        byte[] snapshot = new byte[4096];
        for (int offset = 0; offset < snapshot.length; offset += 1024) {
            System.arraycopy(stableRecord(SNAPSHOT + offset, 1024), 0, snapshot, offset, 1024);
        }
        inspected(snapshot, record);
        saveNew(directory.resolve(SNAPSHOT_FILE), snapshot);
        saveNew(directory.resolve(REPORT_FILE), record);
        out.accept("USD inspection saved: " + directory + "; SHA-256 " + FlashReadFile.hash(snapshot));
        // All backup bytes are durable before this explicit return to the application.
        helper.reset();
        connection.pause(1_000);
        byte[] session = query(bytes(0x22, 0xF1, 0x86), bytes(0x62, 0xF1, 0x86), 5_000);
        if (session.length != 4 || session[3] != 1) throw new IOException("Inspection is saved, but OEM application return was not confirmed");
        out.accept("OEM application session 01 confirmed; inspection does not authorize apply");
    }

    int apply(Path directory, byte[] snapshot, byte[] payload) throws IOException, InterruptedException {
        byte[] cmd = command(snapshot);
        if (Files.exists(directory.resolve(ATTEMPT_FILE))) throw new IOException("An apply attempt is already recorded; verify its outcome, do not replay it");
        requireApplication();
        stage(payload);
        helper.writeRam(COMMAND, cmd);
        // A durable marker prevents a second launch with the same inspection after an ambiguous outcome.
        saveNew(directory.resolve(ATTEMPT_FILE), ("snapshot.sha256=" + FlashReadFile.hash(snapshot) + "\npayload.sha256=" + SHA256 + "\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.accept("Applying USD patch once. Keep power connected; do not reset or retry on a lost reply.");
        try {
            launch(0x2001BA04);
        } catch (UdsClient.Timeout e) {
            out.accept("Apply outcome unconfirmed. Successful apply resets into the loader without a final reply. "
                    + "Use --verify-usd-patch with this inspection directory; no launch was retried.");
            return 3;
        } catch (IOException | InterruptedException e) {
            out.accept("Apply request failed or was interrupted; outcome may be unknown. Do not replay or power-cycle; verify ECU state.");
            throw e;
        }
        // A returning payload has not performed the successful erase/restore/reset path.
        helper.startInSession(out);
        byte[] record = stableRecord(REPORT, 64);
        report(record);
        saveNew(directory.resolve("apply-return.bin"), record);
        if (word(record, 2) == 2 && word(record, 6) == crc(snapshot) && word(record, 8) == 0x05FA &&
                word(record, 9) == 0x05FA && word(record, 10) == MCU && word(record, 11) == BOOT_CRC &&
                word(record, 14) == APP_CRC && word(record, 12) == 0 && word(record, 13) == 0 && word(record, 3) == 1) {
            out.accept("RAM option was already configured; no USD erase. Reader helper remains active.");
            return 0;
        }
        throw new IOException(String.format("USD patch did not complete: status %02X; reader helper remains active", word(record, 2)));
    }

    void verify(Path directory, byte[] snapshot) throws IOException, InterruptedException {
        byte[] session = query(bytes(0x22, 0xF1, 0x86), bytes(0x62, 0xF1, 0x86), 5_000);
        if (session.length != 4 || session[3] != 2) throw new IOException("Verification requires the ECU already in loader session 02; no reset or session transition sent");
        M749ChecksumReader reader = new M749ChecksumReader(connection);
        reader.authenticate();
        if (reader.checkProfile() != M749TargetProfile.I865) throw new IOException("Wrong loader for USD patch verification");
        byte[] record = new byte[64];
        for (int i = 0; i < record.length; i++) {
            boolean found = false;
            for (int candidate = 0; candidate < 256; candidate++) {
                if (reader.diagnosticByteMatches(M749BootDiagnostic.ADDRESS + i, candidate)) {
                    if (reader.diagnosticByteMatches(M749BootDiagnostic.ADDRESS + i, (candidate + 1) & 255))
                        throw new IOException("USD result accepted a wrong byte candidate");
                    record[i] = (byte) candidate; found = true; break;
                }
            }
            if (!found) throw new IOException("USD result changed during read");
        }
        report(record);
        byte[] expected = snapshot.clone(); expected[16] = (byte) 0xFA; expected[17] = 5;
        if (word(record, 2) != 1 || word(record, 3) != 6 || word(record, 6) != crc(snapshot) ||
                word(record, 7) != crc(expected) || word(record, 8) != word(command(snapshot), 3) ||
                word(record, 9) != 0x05FA || word(record, 10) != MCU || word(record, 11) != BOOT_CRC ||
                word(record, 12) != 1 || word(record, 13) != 0 || word(record, 14) != APP_CRC)
            throw new IOException("Retained USD report does not certify this patch; no reset or retry requested");
        out.accept("Checking every USD byte against the saved snapshot with only EOPB0 changed");
        for (int i = 0; i < expected.length; i++) {
            int value = expected[i] & 255;
            if (!reader.usdPatchByteMatches(M749UsdReader.BASE + i, value) ||
                    reader.usdPatchByteMatches(M749UsdReader.BASE + i, (value + 1) & 255))
                throw new IOException(String.format("USD verification failed at offset %03X", i));
            if ((i + 1) % 256 == 0) out.accept("USD verified: " + (i + 1) + "/4096 bytes");
        }
        // Recheck the complete record after the full-page comparison to reject a changing result.
        for (int i = 0; i < record.length; i++) {
            int value = record[i] & 255;
            if (!reader.diagnosticByteMatches(M749BootDiagnostic.ADDRESS + i, value) ||
                    reader.diagnosticByteMatches(M749BootDiagnostic.ADDRESS + i, (value + 1) & 255))
                throw new IOException("Retained USD report changed during verification");
        }
        saveNew(directory.resolve("verification.bin"), record);
        out.accept("Verified all 4096 USD bytes: EOPB0=05FA, FAP and other options preserved. "
                + "The retained record matches this snapshot; its age is not established. Verification requested no reset or session change; the ECU may return to its default session on its own. Cold boot is not checked.");
    }
}
