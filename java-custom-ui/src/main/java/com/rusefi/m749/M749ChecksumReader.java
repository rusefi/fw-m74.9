package com.rusefi.m749;

import com.rusefi.uds.M74_9_SeedKeyCalculator;
import java.io.IOException;
import static com.rusefi.m749.M749Identification.bytes;

/** Read flash through the I865 loader's FF01 checksum comparison. */
final class M749ChecksumReader {
    private final M749Uploader.Connection connection;

    M749ChecksumReader(M749Uploader.Connection connection) {
        this.connection = connection;
    }

    static void requireAddress(int address) {
        if (address < 0x08000000 || address >= 0x083F0000) {
            throw new IllegalArgumentException("Address must be in 0x08000000..0x083EFFFF");
        }
    }

    boolean matches(int address, int length, int sum) throws IOException, InterruptedException {
        requireAddress(address);
        if (length <= 0 || (long) address + length > 0x083F0000L || sum < 0 || sum > 65535) {
            throw new IllegalArgumentException("Invalid checksum range or sum");
        }
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedException(); }
        byte[] q = bytes(0x31, 1, 0xFF, 1, 0x44, address >>> 24, address >>> 16,
                address >>> 8, address, length >>> 24, length >>> 16, length >>> 8,
                length, sum >>> 8, sum);
        byte[] r = connection.exchange(q, bytes(0x71, 1, 0xFF, 1), 5_000);
        if (r.length != 5 || !UdsClient.startsWith(r, bytes(0x71, 1, 0xFF, 1)) ||
                (r[4] != 0 && r[4] != 1)) {
            throw new IOException("Invalid FF01 checksum response");
        }
        return r[4] == 0;
    }

    int readByte(int address) throws IOException, InterruptedException {
        requireAddress(address);
        for (int candidate = 0; candidate < 256; candidate++) {
            if (matches(address, 1, candidate)) {
                rejectWrongCandidate(address, candidate);
                return candidate;
            }
        }
        throw new IOException(String.format("No matching byte at 0x%08X", address));
    }

    void verifyByte(int address, int value) throws IOException, InterruptedException {
        if (value < 0 || value > 255) { throw new IllegalArgumentException("Invalid byte"); }
        if (!matches(address, 1, value)) {
            throw new IOException(String.format("Saved byte differs at 0x%08X; pair file left unchanged", address));
        }
        rejectWrongCandidate(address, value);
    }

    private void rejectWrongCandidate(int address, int value) throws IOException, InterruptedException {
        if (matches(address, 1, (value + 1) & 255)) {
            throw new IOException("FF01 accepted an incorrect candidate; byte not trusted");
        }
    }

    int readWord(int address) throws IOException, InterruptedException {
        int result = 0;
        for (int i = 0; i < 4; i++) { result |= readByte(address + i) << (8 * i); }
        return result;
    }

    void authenticate() throws IOException, InterruptedException {
        byte[] r = connection.exchange(bytes(0x27, 1, 0), bytes(0x67, 1), 5_000);
        if (r.length != 6) { throw new IOException("Invalid loader seed response"); }
        int seed = (r[2] & 255) << 24 | (r[3] & 255) << 16 | (r[4] & 255) << 8 | r[5] & 255;
        if (seed != 0) {
            int key = M74_9_SeedKeyCalculator.Uds_Security_CalcKey(
                    M74_9_SeedKeyCalculator.BOOTLOADER_SECRET, seed, 0);
            r = connection.exchange(bytes(0x27, 2, key >>> 24, key >>> 16, key >>> 8, key), bytes(0x67, 2), 5_000);
            if (r.length != 2) { throw new IOException("Invalid loader authentication response"); }
        }
    }

    void checkProfile() throws IOException, InterruptedException {
        int[] addresses = {0x0822DFFC, 0x08201E2C, 0x08201D84, 0x08204B7C};
        String[] values = {"94b8b6d7", "2de9f04184b004460d4617461e4601f0",
                "70b506460d46144601f024fd012801d0", "08b50a4b1b68fff7e7ff012807d0fff7"};
        for (int n = 0; n < addresses.length; n++) {
            for (int i = 0; i < values[n].length(); i += 2) {
                if (!matches(addresses[n] + i / 2, 1, Integer.parseInt(values[n].substring(i, i + 2), 16))) {
                    throw new IOException("I865 loader compatibility check failed");
                }
            }
        }
    }

    /** An accepted session transition can reset the application into its loader. */
    void prepareRead() throws IOException, InterruptedException {
        byte[] r = connection.exchange(bytes(0x22, 0xF1, 0x86), bytes(0x62, 0xF1, 0x86), 5_000);
        if (r.length != 4) { throw new IOException("Invalid active-session response"); }
        if (r[3] != 2) {
            r = connection.exchange(bytes(0x10, 2), bytes(0x50, 2), 5_000);
            if (r.length != 6) { throw new IOException("Invalid programming-session response"); }
            connection.pause(1_000);
        }
        authenticate();
        checkProfile();
    }
}
