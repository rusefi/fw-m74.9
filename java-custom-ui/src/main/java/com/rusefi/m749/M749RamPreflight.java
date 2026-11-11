package com.rusefi.m749;

import java.io.IOException;
import java.util.function.Consumer;

/** Read-only eligibility check before replacing application software. */
final class M749RamPreflight {
    private final M749ChecksumReader reader;

    M749RamPreflight(M749ChecksumReader reader) {
        this.reader = reader;
    }

    void check(Consumer<String> out) throws IOException, InterruptedException {
        int option = read(0x1FFFC010, 2);
        int access = read(0x1FFFC000, 2);
        int mcu = read(0xE0042000, 4);
        String state = String.format("RAM preflight: EOPB0=%04X access=%04X MCU=%08X", option, access, mcu);
        out.accept(state);
        if (mcu != 0x70084540) {
            throw new IOException(state + "; unsupported MCU identity");
        }
        int data = option & 255;
        // Match m749PrepareRam: an already sufficient valid setting needs no
        // option write, including when access protection or SLIB is enabled.
        if ((data ^ (option >>> 8)) == 255 && (data & 7) <= 2) {
            out.accept("RAM preflight passed: existing RAM setting is sufficient; options remain unchanged");
            return;
        }
        if (option != 0xFFFF) {
            throw new IOException(state + "; RAM option is non-erased and insufficient or invalid. " +
                    "Automatic RAM setup requires erased EOPB0=FFFF and access=5AA5; " +
                    "prepare a supported RAM configuration before uploading. Options were not changed.");
        }
        if (access != 0x5AA5) {
            throw new IOException(state + "; access protection blocks automatic RAM setup (requires access=5AA5). " +
                    "Options were not changed; clearing protection erases main flash.");
        }
        int slib = read(0x40023CCC, 4);
        if ((slib & 8) != 0) {
            throw new IOException(state + String.format(" SLIB=%08X; SLIB restriction blocks automatic RAM setup", slib));
        }
        out.accept("RAM preflight passed: erased RAM option is eligible for first-boot setup to 384 KiB; " +
                "firmware image checks still apply");
    }

    private int read(int address, int length) throws IOException, InterruptedException {
        int value = 0;
        for (int i = 0; i < length; i++) {
            boolean found = false;
            // Erased options are common; try FF first, then every other value.
            for (int attempt = 0; attempt < 256; attempt++) {
                int candidate = (attempt + 255) & 255;
                if (reader.ramPreflightByteMatches(address + i, candidate)) {
                    if (reader.ramPreflightByteMatches(address + i, candidate ^ 1)) {
                        throw new IOException("RAM preflight accepted a wrong byte candidate; value not trusted");
                    }
                    value |= candidate << (8 * i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException(String.format("RAM preflight found no matching byte at 0x%08X", address + i));
            }
        }
        return value;
    }
}
