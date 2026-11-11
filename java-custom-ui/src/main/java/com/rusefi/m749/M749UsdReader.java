package com.rusefi.m749;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

/** Raw USD backup through the same RAM helper used for main flash. */
final class M749UsdReader {
    static final int BASE = 0x1FFFC000, SIZE = 4096;

    static Path outputFor(Path flash) {
        return flash.resolveSibling(flash.getFileName() + ".usd.bin");
    }

    static String read(M749RamHelper helper, FlashReadFile file, int chunk, Consumer<String> out)
            throws IOException, InterruptedException {
        if (file.address != BASE || file.length != SIZE) throw new IllegalArgumentException("Invalid USD output");
        if (chunk < 1 || chunk > M749RamHelper.MAX_BLOCK) throw new IllegalArgumentException("Invalid USD chunk size");
        out.accept("Reading USD 1FFFC000..1FFFCFFF through RAM helper; keep ECU power and CAN connected");
        if (file.completed() > 0) out.accept("Verifying saved USD bytes against the connected ECU before resuming");
        for (int offset = 0; offset < file.completed(); offset += chunk) {
            int count = Math.min(chunk, file.completed() - offset);
            if (!Arrays.equals(file.saved(offset, count), helper.readUsd(BASE + offset, count))) {
                throw new IOException(String.format("Saved USD bytes differ at %08X; backup was not extended", BASE + offset));
            }
        }
        while (file.completed() < SIZE) {
            int address = BASE + file.completed();
            int count = Math.min(chunk, SIZE - file.completed());
            byte[] first = helper.readUsd(address, count);
            if (!Arrays.equals(first, helper.readUsd(address, count))) {
                throw new IOException(String.format("Repeated USD read differs at %08X; block was not saved", address));
            }
            file.append(first);
            out.accept(String.format("USD verified %d/%d bytes (%.1f%%)", file.completed(), SIZE,
                    100.0 * file.completed() / SIZE));
        }
        return file.finish();
    }
}
