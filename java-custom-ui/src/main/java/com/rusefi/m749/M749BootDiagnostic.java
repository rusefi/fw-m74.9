package com.rusefi.m749;

import java.io.IOException;
import java.util.function.LongSupplier;

/** Bounded read-only inspection of the versioned reset-retained boot record. */
final class M749BootDiagnostic {
    static final int ADDRESS = 0x20000040, MAGIC = 0x3144424D, VERSION_SIZE = 0x00010040;
    private final M749ChecksumReader reader;

    M749BootDiagnostic(M749Uploader.Connection connection) {
        this(connection, System::nanoTime);
    }

    M749BootDiagnostic(M749Uploader.Connection connection, LongSupplier now) {
        long deadline = now.getAsLong() + 15_000_000_000L;
        reader = new M749ChecksumReader(new M749Uploader.Connection() {
            int requests;
            public byte[] exchange(byte[] q, byte[] prefix, long timeout) throws IOException, InterruptedException {
                long remaining = (deadline - now.getAsLong()) / 1_000_000;
                if (remaining <= 0 || ++requests > 18000) { throw new IOException("15-second diagnostic budget exhausted"); }
                return connection.exchange(q, prefix, Math.min(timeout, remaining));
            }
            public void pause(long milliseconds) throws InterruptedException { connection.pause(milliseconds); }
        });
    }

    private boolean header() throws IOException, InterruptedException {
        for (int i = 0; i < 8; i++) {
            int value = ((i < 4 ? MAGIC : VERSION_SIZE) >>> (8 * (i % 4))) & 255;
            if (!reader.diagnosticByteMatches(ADDRESS + i, value)) { return false; }
            if (reader.diagnosticByteMatches(ADDRESS + i, (value + 1) & 255)) {
                throw new IOException("Boot diagnostic byte comparison accepted a wrong candidate");
            }
        }
        return true;
    }

    private int word(int index) throws IOException, InterruptedException {
        int result = 0;
        for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
            int address = ADDRESS + index * 4 + byteIndex;
            boolean found = false;
            for (int candidate = 0; candidate < 256; candidate++) {
                if (reader.diagnosticByteMatches(address, candidate)) {
                    if (reader.diagnosticByteMatches(address, (candidate + 1) & 255)) {
                        throw new IOException("Boot diagnostic accepted a wrong byte candidate");
                    }
                    result |= candidate << (8 * byteIndex);
                    found = true;
                    break;
                }
            }
            if (!found) { throw new IOException("Boot diagnostic byte changed during read"); }
        }
        return result;
    }

    Integer sequence() throws IOException, InterruptedException {
        return header() ? word(4) : null;
    }

    String describe(M749TargetProfile expectedProfile, int imageCrc, Integer previousSequence)
            throws IOException, InterruptedException {
        reader.authenticate();
        if (reader.checkProfile() != expectedProfile) { throw new IOException("Loader profile changed; record not read"); }
        if (!header()) { return "Boot diagnostic not present (older firmware or record lost)"; }
        int[] words = new int[16]; words[0] = MAGIC; words[1] = VERSION_SIZE;
        for (int i = 2; i < words.length; i++) { words[i] = word(i); }
        if (!header() || word(4) != words[4]) { throw new IOException("Boot diagnostic changed during read"); }
        return decode(words, imageCrc, previousSequence);
    }

    static String decode(int[] w, int imageCrc, Integer previousSequence) throws IOException {
        if (w.length != 16 || w[0] != MAGIC || w[1] != VERSION_SIZE) { throw new IOException("Invalid boot diagnostic header"); }
        int checksum = MAGIC;
        for (int i = 1; i < 15; i++) { checksum = Integer.rotateLeft(checksum, 5) ^ w[i]; }
        if (checksum != w[15]) { throw new IOException("Boot diagnostic checksum mismatch (torn/stale record)"); }
        if (w[3] != imageCrc) { return "Boot diagnostic belongs to a different software CRC; ignored"; }
        if (previousSequence != null && previousSequence == w[4]) { return "Boot diagnostic unchanged since before reset; stale record ignored"; }
        return String.format("Boot diagnostic: %s; sequence=%s%s; image=%08X MCU=%08X EOPB0=%04X access=%04X "
                        + "SLIB=%08X flashStatus=%08X entryMarker=%08X entryToken=%08X "
                        + "computed software/calibration/loader=%08X/%08X/%08X checksFlags=%08X",
                reason(w[2]), Integer.toUnsignedString(w[4]), previousSequence == null ? " (freshness unverified)" : " (new boot)",
                w[3], w[5], w[6] & 65535, w[6] >>> 16, w[7], w[8], w[9], w[10], w[11], w[12], w[13], w[14]);
    }

    private static String reason(int code) {
        switch (code) {
            case 1: return "bootstrap entered; later outcome unknown";
            case 2: return "RAM ready; later startup outcome unknown";
            case 3: return "RAM option programmed; reload requested";
            case 4: return "application activation completed; later outcome unknown";
            case 16: return "unsupported MCU identity";
            case 17: return "non-erased/insufficient RAM option";
            case 18: return "RAM option blocked by access protection";
            case 19: return "RAM option blocked by SLIB restriction";
            case 20: return "image CRC/profile/vector checks failed or interrupted";
            case 21: return "boot marker/token rejected";
            case 22: return "flash controller busy before option write";
            case 23: return "option unlock failed";
            case 24: return "option programming timeout";
            case 25: return "option programming status error";
            case 26: return "RAM option readback mismatch";
            case 27: return "other option bytes changed";
            case 28: return "application marker activation failed";
            default: return "unknown reason " + code;
        }
    }
}
